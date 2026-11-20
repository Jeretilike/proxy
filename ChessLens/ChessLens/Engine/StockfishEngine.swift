import ChessKitEngine
import Foundation

struct EngineAnalysis: Equatable {
    /// Best move in UCI notation, e.g. "g1f3".
    let bestMove: String
    /// Score in centipawns from the side to move's point of view.
    let centipawns: Double?
    /// Mate in N moves from the side to move's point of view (negative: getting mated).
    let mate: Int?
    let depth: Int?
    let principalVariation: [String]
}

enum EngineError: LocalizedError {
    case missingNetworks
    case startTimeout
    case busy
    case noLegalMoves
    case searchTimeout

    var errorDescription: String? {
        switch self {
        case .missingNetworks:
            return "Stockfish's neural network files are missing from the app bundle. Rebuild with the GitHub workflow, which downloads them."
        case .startTimeout: return "Stockfish didn't start in time. It will try again on the next move; if this keeps happening, close and reopen the app."
        case .busy: return "Stockfish is already analysing a position."
        case .noLegalMoves: return "No legal moves: the game is over (checkmate or stalemate)."
        case .searchTimeout: return "Stockfish did not answer in time."
        }
    }
}

/// Runs Stockfish (via ChessKitEngine) inside the app and answers one search at a time.
actor StockfishEngine {
    static let shared = StockfishEngine()

    /// The NNUE files ChessKitEngine's Stockfish build loads from the main bundle.
    static let networkNames = ["nn-1111cefa1111", "nn-37f18f62d772"]

    static var networksAvailable: Bool {
        networkNames.allSatisfy { Bundle.main.url(forResource: $0, withExtension: "nnue") != nil }
    }

    private var engine: Engine?
    private var listenTask: Task<Void, Never>?
    private var startTask: Task<Void, Error>?
    private var isReady = false
    private var receivedReadyOK = false
    private var pending: CheckedContinuation<EngineAnalysis, Error>?
    private var latestInfo: EngineResponse.Info?

    /// Starts Stockfish once. Concurrent callers share the same start-up: ChessKitEngine wires
    /// Stockfish to process-wide stdin/stdout, so a second `Engine` would tear down the first.
    func start() async throws {
        if isReady { return }
        if let startTask { return try await startTask.value }
        let task = Task { try await self.launch() }
        startTask = task
        do {
            try await task.value
            isReady = true
        } catch {
            startTask = nil // a later call retries, reusing the same engine
            throw error
        }
    }

    private func launch() async throws {
        guard Self.networksAvailable else { throw EngineError.missingNetworks }

        let engine: Engine
        if let existing = self.engine {
            // An earlier attempt timed out: restart the UCI handshake on the same engine.
            engine = existing
            await engine.send(command: .uci)
        } else {
            engine = Engine(type: .stockfish)
            self.engine = engine
            await engine.start(coreCount: ProcessInfo.processInfo.activeProcessorCount)
            if let stream = await engine.responseStream {
                listenTask = Task { [weak self] in
                    for await response in stream {
                        await self?.handle(response)
                    }
                }
            }
        }

        // ChessKitEngine flips `isRunning` after the uci → uciok → isready → readyok handshake.
        var waited = 0
        while await !engine.isRunning {
            guard waited < 600 else { throw EngineError.startTimeout } // 30 s
            try await Task.sleep(nanoseconds: 50_000_000)
            waited += 1
        }

        // ChessKitEngine then sends its setup options, including the neural network files. Give
        // those a moment to be written, then wait until Stockfish has processed everything:
        // a search started before the networks load makes Stockfish exit.
        try await Task.sleep(nanoseconds: 300_000_000)
        await engine.send(command: .setoption(id: "Hash", value: "64"))
        receivedReadyOK = false
        await engine.send(command: .isready)
        waited = 0
        while !receivedReadyOK {
            guard waited < 1500 else { throw EngineError.startTimeout } // 30 s
            try await Task.sleep(nanoseconds: 20_000_000)
            waited += 1
        }
    }

    func analyze(fen: String, movetimeMilliseconds: Int) async throws -> EngineAnalysis {
        try await start()
        guard let engine else { throw EngineError.startTimeout }
        // A newer position replaces any search still running: stop it and let its bestmove
        // arrive (its caller ignores the stale answer) so replies can't get mixed up.
        var waited = 0
        while pending != nil {
            if waited % 50 == 0 { await engine.send(command: .stop) }
            try await Task.sleep(nanoseconds: 20_000_000)
            waited += 1
            if waited > 500 { throw EngineError.busy }
        }
        latestInfo = nil

        let timeout = UInt64(movetimeMilliseconds + 15_000) * 1_000_000
        let watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: timeout)
            await self?.failPending(EngineError.searchTimeout)
        }
        defer { watchdog.cancel() }

        return try await withCheckedThrowingContinuation { continuation in
            pending = continuation
            Task {
                await engine.send(command: .position(.fen(fen)))
                await engine.send(command: .go(movetime: movetimeMilliseconds))
            }
        }
    }

    private func failPending(_ error: Error) {
        guard let pending else { return }
        self.pending = nil
        pending.resume(throwing: error)
        Task { [engine] in await engine?.send(command: .stop) }
    }

    private func handle(_ response: EngineResponse) {
        switch response {
        case .readyok:
            receivedReadyOK = true
        case let .info(info):
            // Keep the main line's latest report; other info lines (currmove, hashfull…) lack a pv.
            if let pv = info.pv, !pv.isEmpty, (info.multipv ?? 1) == 1 {
                latestInfo = info
            }
        case let .bestmove(move, _):
            guard let pending else { return }
            self.pending = nil
            if move == "(none)" || move == "0000" || move.isEmpty {
                pending.resume(throwing: EngineError.noLegalMoves)
                return
            }
            let info = latestInfo
            pending.resume(returning: EngineAnalysis(
                bestMove: move,
                centipawns: info?.score?.cp,
                mate: info?.score?.mate,
                depth: info?.depth,
                principalVariation: info?.pv ?? [move]
            ))
        default:
            break
        }
    }
}
