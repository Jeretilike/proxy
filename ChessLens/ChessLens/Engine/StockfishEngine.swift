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
        case .startTimeout: return "Stockfish did not start."
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
    private var pending: CheckedContinuation<EngineAnalysis, Error>?
    private var latestInfo: EngineResponse.Info?

    func start() async throws {
        if let engine, await engine.isRunning { return }
        guard Self.networksAvailable else { throw EngineError.missingNetworks }

        let engine = Engine(type: .stockfish)
        self.engine = engine
        await engine.start(coreCount: ProcessInfo.processInfo.activeProcessorCount)

        if let stream = await engine.responseStream {
            listenTask?.cancel()
            listenTask = Task { [weak self] in
                for await response in stream {
                    await self?.handle(response)
                }
            }
        }

        // ChessKitEngine flips `isRunning` once the UCI handshake and option setup finish.
        for _ in 0..<200 {
            if await engine.isRunning {
                await engine.send(command: .setoption(id: "Hash", value: "64"))
                return
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw EngineError.startTimeout
    }

    func analyze(fen: String, movetimeMilliseconds: Int) async throws -> EngineAnalysis {
        try await start()
        guard let engine else { throw EngineError.startTimeout }
        guard pending == nil else { throw EngineError.busy }
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
