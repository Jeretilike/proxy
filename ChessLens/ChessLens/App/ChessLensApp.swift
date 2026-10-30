import MWDATCore
import SwiftUI

@main
struct ChessLensApp: App {
    @State private var glasses: GlassesManager
    @State private var game: GameViewModel

    init() {
        AppSettings.registerDefaults()
        do {
            try Wearables.configure()
        } catch {
            NSLog("[ChessLens] Failed to configure Meta Wearables SDK: \(error)")
        }
        let glasses = GlassesManager()
        _glasses = State(initialValue: glasses)
        _game = State(initialValue: GameViewModel(glasses: glasses))
    }

    var body: some Scene {
        WindowGroup {
            ContentView(glasses: glasses, game: game)
                .onOpenURL { glasses.handle(url: $0) }
                .task {
                    // Load Stockfish and its networks up front so the first answer is quick.
                    try? await StockfishEngine.shared.start()
                }
        }
    }
}
