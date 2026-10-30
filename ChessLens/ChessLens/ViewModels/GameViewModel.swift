import CoreGraphics
import Foundation
import Observation
import UIKit

/// Follows a game played on a physical board: calibrate once on a known position, then each
/// time the view settles, detect the move(s) played, and when it's the wearer's turn ask
/// Stockfish for the best move and speak it.
@Observable
@MainActor
final class GameViewModel {
    enum TrackingState: Equatable {
        case notCalibrated
        case watching
        case thinking
        case lost
        case unsure(String)
        case gameOver(String)
    }

    struct CalibrationDraft: Identifiable {
        let id = UUID()
        var frame: CGImage
        var corners: BoardCorners
    }

    var state: TrackingState = .notCalibrated
    var position: ChessPosition = .start
    private(set) var history: [ChessPosition] = []
    private(set) var moveLog: [String] = []
    var suggestion: EngineAnalysis?
    var lastEvent: String?
    var selectedSquare: Square?
    /// Where the board is in the latest checked frame (unit coordinates), for the preview overlay.
    var corners: BoardCorners?
    var calibration: CalibrationDraft?

    let glasses: GlassesManager

    var playerColor: PieceColor { AppSettings.playerColor }
    var isPlayersTurn: Bool { position.sideToMove == playerColor }
    var isCalibrated: Bool { corners != nil }

    var statusText: String {
        switch state {
        case .notCalibrated:
            return glasses.isStreaming
                ? "Set up the position, look at the board, then tap Calibrate."
                : "Start the glasses camera, then calibrate the board."
        case .watching:
            return isPlayersTurn ? "Your move." : "Watching for your opponent's move…"
        case .thinking: return "Stockfish is thinking…"
        case .lost: return "Board not found. Look at the whole board, or recalibrate."
        case .unsure(let message): return message
        case .gameOver(let result): return result
        }
    }

    var suggestionText: String? {
        guard let suggestion, let move = UCIMove(suggestion.bestMove) else { return nil }
        return position.board.shortNotation(for: move)
    }

    var spokenSuggestion: String? {
        guard let suggestion, let move = UCIMove(suggestion.bestMove) else { return nil }
        return position.board.spokenDescription(for: move)
    }

    /// Evaluation from White's point of view, e.g. "+0.85" or "#-3".
    var evaluationText: String? {
        guard let suggestion else { return nil }
        let sign = position.sideToMove == .white ? 1 : -1
        if let mate = suggestion.mate { return "#\(mate * sign)" }
        guard let cp = suggestion.centipawns else { return nil }
        return String(format: "%+.2f", cp * Double(sign) / 100)
    }

    var lineText: String? {
        guard let suggestion else { return nil }
        var walker = position
        var parts: [String] = []
        for uci in suggestion.principalVariation.prefix(8) {
            guard let move = UCIMove(uci) else { break }
            parts.append(walker.board.shortNotation(for: move))
            walker = walker.applying(move)
        }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    @ObservationIgnored private let tracker = BoardTracker()
    @ObservationIgnored private var sceneDetector = SceneChangeDetector()
    @ObservationIgnored private var lastFrameCheck = Date.distantPast
    @ObservationIgnored private var isChecking = false
    @ObservationIgnored private var lostCount = 0
    @ObservationIgnored private var lastWarning = Date.distantPast
    @ObservationIgnored private var engineTask: Task<Void, Never>?

    init(glasses: GlassesManager) {
        self.glasses = glasses
        glasses.onFrame = { [weak self] frame in self?.handleFrame(frame) }
    }

    // MARK: Calibration

    func beginCalibration() async {
        guard let frame = glasses.latestFrame?.uprightCGImage() else {
            state = .unsure("Start the glasses camera and look at the board first.")
            return
        }
        let guess = await tracker.suggestCorners(in: frame, playerColor: playerColor)
        calibration = CalibrationDraft(frame: frame, corners: calibration?.corners ?? guess)
    }

    /// Grabs a fresh frame for the calibration screen, keeping the corners already placed.
    func refreshCalibrationFrame() {
        guard let frame = glasses.latestFrame?.uprightCGImage(), var draft = calibration else { return }
        draft.frame = frame
        calibration = draft
    }

    func detectCornersAgain() async {
        guard let draft = calibration else { return }
        let guess = await tracker.suggestCorners(in: draft.frame, playerColor: playerColor)
        calibration?.corners = guess
    }

    func finishCalibration(_ draft: CalibrationDraft) async {
        calibration = nil
        guard await tracker.calibrate(frame: draft.frame, corners: draft.corners, board: position.board) else {
            state = .unsure("Those corners didn't work. Calibrate again.")
            return
        }
        corners = draft.corners
        sceneDetector.reset()
        lostCount = 0
        positionChanged(announcement: nil, speak: true)
    }

    // MARK: Game control

    func newGame() {
        engineTask?.cancel()
        position = .start
        history = []
        moveLog = []
        suggestion = nil
        lastEvent = nil
        selectedSquare = nil
        corners = nil
        state = .notCalibrated
        Task { await tracker.reset() }
    }

    func undo() {
        guard let previous = history.popLast() else { return }
        if !moveLog.isEmpty { moveLog.removeLast() }
        position = previous
        lastEvent = "Took back the last move."
        resyncTracker()
        positionChanged(announcement: nil, speak: false)
    }

    /// Tap a piece of the side to move, then its destination, to enter a move the camera missed.
    func tap(_ square: Square) {
        if let from = selectedSquare {
            selectedSquare = nil
            let matches = position.legalMoves().filter { $0.from == from && $0.to == square }
            if let move = matches.first(where: { $0.promotion == nil || $0.promotion == .queen }) {
                record([move], announce: false)
                resyncTracker()
                return
            }
        }
        if position.board[square]?.color == position.sideToMove { selectedSquare = square }
    }

    /// Replaces the position (piece edits, FEN, side to move). History is kept for undo.
    func setPosition(_ newPosition: ChessPosition) {
        history.append(position)
        moveLog.append("(edit)")
        position = newPosition
        selectedSquare = nil
        resyncTracker()
        positionChanged(announcement: nil, speak: false)
    }

    func setPiece(_ piece: Piece?, at square: Square) {
        var board = position.board
        board[square] = piece
        setPosition(ChessPosition(board: board, sideToMove: position.sideToMove))
    }

    func toggleSideToMove() {
        setPosition(ChessPosition(board: position.board, sideToMove: position.sideToMove.opposite))
    }

    func applyFEN(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let board = ChessBoard(fen: trimmed) else {
            state = .unsure("That FEN couldn't be read.")
            return
        }
        let fields = trimmed.split(separator: " ")
        let side: PieceColor = fields.count > 1 && fields[1] == "b" ? .black : .white
        setPosition(ChessPosition(board: board, sideToMove: side))
    }

    func speakAgain() {
        guard let move = spokenSuggestion else { return }
        Speaker.shared.say("Play " + move)
    }

    // MARK: Watching the board

    private func handleFrame(_ frame: UIImage) {
        guard AppSettings.handsFree, isCalibrated, !isChecking, calibration == nil else { return }
        if case .gameOver = state { return }
        let now = Date()
        guard now.timeIntervalSince(lastFrameCheck) >= 0.25 else { return }
        lastFrameCheck = now
        guard sceneDetector.shouldAnalyze(frame, now: now) else { return }
        sceneDetector.markAnalyzed(frame, now: now)
        Task { await check(frame) }
    }

    /// Looks for a move on the board right now (also used by the "Check now" button).
    func checkNow() {
        guard let frame = glasses.latestFrame, !isChecking else { return }
        Task { await check(frame) }
    }

    private func check(_ frame: UIImage) async {
        guard let image = frame.uprightCGImage() else { return }
        isChecking = true
        defer { isChecking = false }

        let startPosition = position
        guard let first = await observe(image, from: startPosition) else { return }
        guard case .moves(let moves, let result) = first else {
            handle(first)
            return
        }

        // Confirm with a second frame before trusting a move, so one bad frame can't corrupt the game.
        try? await Task.sleep(nanoseconds: 600_000_000)
        guard let secondFrame = glasses.latestFrame?.uprightCGImage(),
              let second = await observe(secondFrame, from: startPosition),
              case .moves(_, let confirmed) = second, confirmed.board == result.board,
              position == startPosition
        else { return }

        await tracker.commitPending(board: result.board)
        record(moves, announce: true)
    }

    /// Aligns the frame and runs move detection off the main thread. Nil when the board wasn't found.
    private func observe(_ image: CGImage, from start: ChessPosition) async -> MoveDetection? {
        switch await tracker.observe(image) {
        case .notCalibrated:
            return nil
        case .lost:
            lostCount += 1
            state = .lost
            if lostCount == 4 { warn("I lost the board. Look at the whole board.") }
            return nil
        case .observed(let observation, let newCorners):
            lostCount = 0
            corners = newCorners
            if state == .lost { state = .watching }
            let player = playerColor
            return await Task.detached(priority: .userInitiated) {
                MoveDetector.detect(from: start, observation: observation, playerColor: player)
            }.value
        }
    }

    private func handle(_ detection: MoveDetection) {
        switch detection {
        case .noChange:
            // Keep the reference fresh as lighting and viewing angle drift.
            Task { [position] in await tracker.commitPending(board: position.board) }
            if case .unsure = state { state = .watching }
        case .unclear:
            state = .unsure("Something changed but it doesn't match a move. If a move was played, tap it on the board.")
        case .ambiguous:
            state = .unsure("Not sure which move was played. Tap it on the board.")
            warn("I didn't catch that move.")
        case .moves:
            break
        }
    }

    // MARK: Moves and suggestions

    private func record(_ moves: [UCIMove], announce: Bool) {
        var walker = position
        var opponentMoves: [String] = []
        for move in moves {
            moveLog.append(walker.board.shortNotation(for: move))
            if walker.sideToMove != playerColor {
                opponentMoves.append(walker.board.spokenDescription(for: move))
            }
            history.append(walker)
            walker = walker.applying(move)
        }
        position = walker
        selectedSquare = nil
        lastEvent = moves.count == 1
            ? "Played \(moveLog.last ?? "")"
            : "Played \(moveLog.suffix(moves.count).joined(separator: ", "))"

        let announcement = announce && AppSettings.announceOpponent && !opponentMoves.isEmpty
            ? "Opponent: " + opponentMoves.joined(separator: ", then ")
            : nil
        positionChanged(announcement: announcement, speak: announce)
    }

    private func positionChanged(announcement: String?, speak: Bool) {
        engineTask?.cancel()
        suggestion = nil

        if position.legalMoves().isEmpty {
            let result = position.board.isInCheck(position.sideToMove) ? "Checkmate." : "Stalemate."
            state = .gameOver(result)
            if speak { Speaker.shared.say([announcement, result].compactMap { $0 }.joined(separator: ". ")) }
            return
        }
        guard isPlayersTurn else {
            state = isCalibrated ? .watching : .notCalibrated
            return
        }

        let issues = position.board.validationIssues(sideToMove: position.sideToMove)
        guard issues.isEmpty else {
            state = .unsure("The position looks impossible: " + issues.joined(separator: " "))
            return
        }

        state = .thinking
        let analyzed = position
        engineTask = Task {
            do {
                let analysis = try await StockfishEngine.shared.analyze(
                    fen: analyzed.fen, movetimeMilliseconds: AppSettings.thinkTimeMilliseconds)
                guard !Task.isCancelled, analyzed == position else { return }
                suggestion = analysis
                state = isCalibrated ? .watching : .notCalibrated
                if speak { Speaker.shared.say([announcement, sentence(for: analysis)].compactMap { $0 }.joined(separator: ". ")) }
            } catch {
                guard !Task.isCancelled, analyzed == position else { return }
                state = .unsure(error.localizedDescription)
            }
        }
    }

    private func sentence(for analysis: EngineAnalysis) -> String? {
        guard let move = UCIMove(analysis.bestMove) else { return nil }
        var text = "Play " + position.board.spokenDescription(for: move)
        if let mate = analysis.mate, mate > 0 {
            text += ". Mate in \(mate)"
        } else if AppSettings.speakEvaluation, let cp = analysis.centipawns {
            text += String(format: ". Evaluation %+.1f", cp / 100)
        }
        return text
    }

    private func resyncTracker() {
        guard isCalibrated, let frame = glasses.latestFrame?.uprightCGImage() else { return }
        let board = position.board
        Task {
            if let newCorners = await tracker.resync(frame: frame, board: board) {
                corners = newCorners
            } else {
                state = .lost
            }
        }
    }

    private func warn(_ text: String) {
        guard Date().timeIntervalSince(lastWarning) > 20 else { return }
        lastWarning = Date()
        Speaker.shared.say(text)
    }
}
