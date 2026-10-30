import CoreGraphics
import Foundation
import Observation
import UIKit

/// Runs a game in one of two modes:
/// - **Glasses:** finds the physical board by itself, then each time the view settles detects
///   the move(s) played; on the wearer's turn Stockfish's best move is spoken.
/// - **Screen:** both sides' moves are tapped on the on-screen board and the best move is
///   spoken through the phone.
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
    var mode: PlayMode = AppSettings.playMode {
        didSet {
            guard mode != oldValue else { return }
            AppSettings.playMode = mode
            modeDidChange()
        }
    }
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
                ? "Looking for the board… Set up the position shown below and look at the whole board."
                : "Start the glasses camera to begin."
        case .watching:
            if mode == .screen {
                return isPlayersTurn ? "Your move: tap it on the board, or tap Play it." : "Enter your opponent's move on the board."
            }
            return isPlayersTurn ? "Your move." : "Watching for your opponent's move…"
        case .thinking: return "Stockfish is thinking…"
        case .lost: return "Board not found. Look at the whole board."
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
    @ObservationIgnored private var isLocating = false
    @ObservationIgnored private var lastLocateAttempt = Date.distantPast
    @ObservationIgnored private var candidateCorners: BoardCorners?

    /// What to show when nothing is happening.
    private var idleState: TrackingState { mode == .screen || isCalibrated ? .watching : .notCalibrated }

    init(glasses: GlassesManager) {
        self.glasses = glasses
        glasses.onFrame = { [weak self] frame in self?.handleFrame(frame) }
        if mode == .screen {
            state = .watching
            positionChanged(announcement: nil, speak: false)
        }
    }

    /// The player's colour decides which side of the board is nearest and when to speak.
    func playerColorChanged() {
        candidateCorners = nil
        positionChanged(announcement: nil, speak: false)
    }

    private func modeDidChange() {
        selectedSquare = nil
        candidateCorners = nil
        state = idleState
        positionChanged(announcement: nil, speak: false)
    }

    // MARK: Calibration

    func beginCalibration() async {
        guard let frame = glasses.latestFrame?.uprightCGImage() else {
            state = .unsure("Start the glasses camera and look at the board first.")
            return
        }
        let guess = await tracker.suggestCorners(in: frame, board: position.board, playerColor: playerColor)
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
        let guess = await tracker.suggestCorners(in: draft.frame, board: position.board, playerColor: playerColor)
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
        candidateCorners = nil
        state = idleState
        Task { await tracker.reset() }
        // On screen, start right away (if you're White the first move is spoken). With the
        // glasses, the app first finds the board, then speaks.
        if mode == .screen { positionChanged(announcement: nil, speak: true) }
    }

    func undo() {
        guard let previous = history.popLast() else { return }
        if !moveLog.isEmpty { moveLog.removeLast() }
        position = previous
        lastEvent = "Took back the last move."
        resyncTracker()
        positionChanged(announcement: nil, speak: false)
    }

    /// Tap a piece of the side to move, then its destination: how moves are played in screen
    /// mode, and how a move the camera missed is entered in glasses mode.
    func tap(_ square: Square) {
        if let from = selectedSquare {
            selectedSquare = nil
            let matches = position.legalMoves().filter { $0.from == from && $0.to == square }
            if let move = matches.first(where: { $0.promotion == nil || $0.promotion == .queen }) {
                record([move], announceOpponent: false, speak: true)
                resyncTracker()
                return
            }
        }
        if position.board[square]?.color == position.sideToMove { selectedSquare = square }
    }

    /// Plays the suggested move on the on-screen board.
    func playSuggestion() {
        guard let suggestion, let move = UCIMove(suggestion.bestMove),
              position.legalMoves().contains(move) else { return }
        record([move], announceOpponent: false, speak: true)
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
        guard mode == .glasses, calibration == nil else { return }
        if case .gameOver = state { return }
        guard isCalibrated else {
            locateIfDue(frame)
            return
        }
        guard AppSettings.handsFree, !isChecking else { return }
        let now = Date()
        guard now.timeIntervalSince(lastFrameCheck) >= 0.25 else { return }
        lastFrameCheck = now
        guard sceneDetector.shouldAnalyze(frame, now: now) else { return }
        sceneDetector.markAnalyzed(frame, now: now)
        Task { await check(frame) }
    }

    // MARK: Automatic calibration

    private func locateIfDue(_ frame: UIImage) {
        let now = Date()
        guard !isLocating, now.timeIntervalSince(lastLocateAttempt) >= 1 else { return }
        lastLocateAttempt = now
        Task { await locateBoard(in: frame) }
    }

    /// Looks for a board showing the current position and starts tracking once two frames in a
    /// row agree on where it is, so a lucky fit on some other rectangle can't start a game.
    private func locateBoard(in frame: UIImage) async {
        guard let image = frame.uprightCGImage() else { return }
        isLocating = true
        defer { isLocating = false }

        let board = position.board
        guard let found = await tracker.locateBoard(in: image, board: board, playerColor: playerColor) else {
            candidateCorners = nil
            return
        }
        guard let previous = candidateCorners, previous.distance(to: found.corners) < 0.04 else {
            candidateCorners = found.corners
            return
        }
        candidateCorners = nil
        guard !isCalibrated, calibration == nil, mode == .glasses, position.board == board,
              await tracker.calibrate(frame: image, corners: found.corners, board: board)
        else { return }

        corners = found.corners
        sceneDetector.reset()
        lostCount = 0
        positionChanged(announcement: "Board found", speak: true)
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
        record(moves, announceOpponent: true, speak: true)
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
            if state == .lost { state = idleState }
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
            if case .unsure = state { state = idleState }
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

    private func record(_ moves: [UCIMove], announceOpponent: Bool, speak: Bool) {
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

        let announcement = announceOpponent && AppSettings.announceOpponent && !opponentMoves.isEmpty
            ? "Opponent: " + opponentMoves.joined(separator: ", then ")
            : nil
        positionChanged(announcement: announcement, speak: speak)
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
            state = idleState
            if speak, let announcement { Speaker.shared.say(announcement) }
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
                state = idleState
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
        guard mode == .glasses, isCalibrated, let frame = glasses.latestFrame?.uprightCGImage() else { return }
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
