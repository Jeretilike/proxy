import Observation
import UIKit

/// Drives the pipeline: photo → board recognition (Claude vision) → Stockfish → spoken move.
@Observable
@MainActor
final class AnalyzerViewModel {
    enum Phase: Equatable {
        case idle
        case capturing
        case reading
        case thinking
        case waitingForOpponent
        case done
        case failed(String)
    }

    var phase: Phase = .idle
    var photo: UIImage?
    var board: ChessBoard?
    var sideToMove: PieceColor = .white
    var analysis: EngineAnalysis?
    var recognitionNotes = ""

    let glasses: GlassesManager

    var isWorking: Bool { [.capturing, .reading, .thinking].contains(phase) }

    var statusText: String {
        switch phase {
        case .idle:
            return glasses.isStreaming && AppSettings.handsFree
                ? "Look at the board and hold still" : "Ready"
        case .capturing: return "Taking photo…"
        case .reading: return "Reading the board…"
        case .thinking: return "Stockfish is thinking…"
        case .waitingForOpponent: return "Your move is on the board — waiting for your opponent"
        case .done: return "Done"
        case .failed(let message): return message
        }
    }

    var bestMoveText: String? {
        guard let analysis, let board, let move = UCIMove(analysis.bestMove) else { return nil }
        return board.shortNotation(for: move)
    }

    var spokenMoveText: String? {
        guard let analysis, let board, let move = UCIMove(analysis.bestMove) else { return nil }
        return board.spokenDescription(for: move)
    }

    /// Evaluation from White's point of view, e.g. "+0.85" or "#-3".
    var evaluationText: String? {
        guard let analysis else { return nil }
        let sign = sideToMove == .white ? 1 : -1
        if let mate = analysis.mate { return "#\(mate * sign)" }
        guard let cp = analysis.centipawns else { return nil }
        return String(format: "%+.2f", cp * Double(sign) / 100)
    }

    var lineText: String? {
        guard let analysis, var position = board else { return nil }
        var parts: [String] = []
        for uci in analysis.principalVariation.prefix(8) {
            guard let move = UCIMove(uci) else { break }
            parts.append(position.shortNotation(for: move))
            position = position.applying(move)
        }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    var fen: String? { board?.fen(sideToMove: sideToMove) }

    @ObservationIgnored private var detector = SceneChangeDetector()
    @ObservationIgnored private var lastFrameCheck = Date.distantPast
    @ObservationIgnored private var lastRecognition = Date.distantPast
    /// The last board seen in hands-free mode, used to tell whose turn it is.
    @ObservationIgnored private var previousBoard: ChessBoard?

    init(glasses: GlassesManager) {
        self.glasses = glasses
        glasses.onFrame = { [weak self] frame in self?.handleFrame(frame) }
    }

    // MARK: Hands-free

    private func handleFrame(_ frame: UIImage) {
        guard AppSettings.handsFree, !isWorking else { return }
        let now = Date()
        guard now.timeIntervalSince(lastFrameCheck) >= 0.3 else { return }
        lastFrameCheck = now
        guard detector.shouldAnalyze(frame, now: now),
              now.timeIntervalSince(lastRecognition) >= 4
        else { return }
        Task { await analyzeFromGlasses(automatic: true) }
    }

    func resetHandsFree() {
        detector.reset()
        previousBoard = nil
        if phase == .waitingForOpponent { phase = .idle }
    }

    // MARK: Entry points

    func analyzeFromGlasses(automatic: Bool = false) async {
        guard !isWorking else { return }
        guard glasses.isStreaming else {
            phase = .failed("Start the glasses camera first.")
            return
        }
        phase = .capturing
        lastRecognition = Date()
        if let frame = glasses.latestFrame { detector.markAnalyzed(frame) }
        let image = AppSettings.useGlassesPhoto ? await glasses.capturePhoto() : glasses.latestFrame
        guard let image else {
            phase = .failed("No image from the glasses yet.")
            return
        }
        await analyze(image: image, automatic: automatic)
    }

    func analyze(image: UIImage, automatic: Bool = false) async {
        photo = image
        phase = .reading
        let playerColor = AppSettings.playerColor
        let recognizer = BoardRecognizer(
            apiKey: AppSettings.anthropicAPIKey,
            model: AppSettings.claudeModel,
            effort: AppSettings.recognitionEffort
        )

        let recognized: RecognizedBoard
        do {
            recognized = try await recognizer.recognize(image, playerColor: playerColor)
        } catch {
            fail(error.localizedDescription, speak: automatic ? "I couldn't read the board." : nil)
            return
        }
        recognitionNotes = recognized.notes
        let newBoard = recognized.board

        if automatic, AppSettings.detectTurn, let previous = previousBoard {
            if previous == newBoard {
                // Same position as last time (e.g. the wearer looked away and back): stay quiet.
                phase = analysis == nil ? .idle : .done
                return
            }
            if newBoard.colorThatMoved(since: previous) == playerColor {
                // The wearer just made their move; wait for the opponent's reply.
                previousBoard = newBoard
                board = newBoard
                sideToMove = playerColor.opposite
                analysis = nil
                phase = .waitingForOpponent
                return
            }
        }

        previousBoard = newBoard
        board = newBoard
        sideToMove = playerColor
        await runEngine(speak: true)
    }

    // MARK: Corrections

    func setPiece(_ piece: Piece?, at square: Square) {
        board?[square] = piece
        boardWasEdited()
    }

    func rotateBoard() {
        board = board?.rotated
        boardWasEdited()
    }

    func toggleSideToMove() {
        sideToMove = sideToMove.opposite
        Task { await runEngine(speak: false) }
    }

    func applyFEN(_ text: String) {
        guard let parsed = ChessBoard(fen: text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            phase = .failed("That FEN couldn't be parsed.")
            return
        }
        board = parsed
        let fields = text.split(separator: " ")
        if fields.count > 1 { sideToMove = fields[1] == "b" ? .black : .white }
        boardWasEdited()
    }

    func speakAgain() {
        speakResult()
    }

    private func boardWasEdited() {
        previousBoard = board
        analysis = nil
        Task { await runEngine(speak: false) }
    }

    // MARK: Engine

    func runEngine(speak: Bool) async {
        guard let board else { return }
        let issues = board.validationIssues(sideToMove: sideToMove)
        guard issues.isEmpty else {
            analysis = nil
            fail("Board looks wrong: " + issues.joined(separator: " ") + " Tap squares to fix it.",
                 speak: speak ? "The board didn't read correctly. Check your phone." : nil)
            return
        }

        phase = .thinking
        do {
            analysis = try await StockfishEngine.shared.analyze(
                fen: board.fen(sideToMove: sideToMove),
                movetimeMilliseconds: AppSettings.thinkTimeMilliseconds
            )
            phase = .done
            if speak { speakResult() }
        } catch {
            analysis = nil
            fail(error.localizedDescription, speak: speak ? error.localizedDescription : nil)
        }
    }

    private func speakResult() {
        guard let move = spokenMoveText, let analysis else { return }
        var sentence = move
        if let mate = analysis.mate, mate > 0 {
            sentence += ". Mate in \(mate)"
        } else if AppSettings.speakEvaluation, let cp = analysis.centipawns {
            sentence += String(format: ". Evaluation %+.1f", cp / 100)
        }
        Speaker.shared.say(sentence)
    }

    private func fail(_ message: String, speak: String?) {
        phase = .failed(message)
        if let speak { Speaker.shared.say(speak) }
    }
}
