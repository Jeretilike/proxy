import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var glasses: GlassesManager

    @AppStorage(SettingsKey.playerColor) private var playerColor = PieceColor.white.rawValue
    @AppStorage(SettingsKey.handsFree) private var handsFree = true
    @AppStorage(SettingsKey.announceOpponent) private var announceOpponent = true
    @AppStorage(SettingsKey.speakEvaluation) private var speakEvaluation = false
    @AppStorage(SettingsKey.thinkTimeMilliseconds) private var thinkTime = 1500
    @AppStorage(SettingsKey.checkIntervalSeconds) private var checkInterval = 5.0

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("I'm playing", selection: $playerColor) {
                        Text("White").tag(PieceColor.white.rawValue)
                        Text("Black").tag(PieceColor.black.rawValue)
                    }
                    Stepper(value: $thinkTime, in: 500...10_000, step: 500) {
                        LabeledContent("Stockfish think time", value: String(format: "%.1f s", Double(thinkTime) / 1000))
                    }
                } header: {
                    Text("Game")
                } footer: {
                    Text("Your colour sets which side of the board is nearest to you and when the app speaks the best move.")
                }

                Section {
                    Toggle("Hands-free tracking", isOn: $handsFree)
                    Stepper(value: $checkInterval, in: 2...15, step: 1) {
                        LabeledContent("Check the board every", value: "\(Int(checkInterval)) s")
                    }
                    Toggle("Say the opponent's move", isOn: $announceOpponent)
                    Toggle("Say the evaluation", isOn: $speakEvaluation)
                } header: {
                    Text("Glasses")
                } footer: {
                    Text("Hands-free: the app checks the board whenever the view holds still, and at least every few seconds. Each check looks for a move and verifies every square against the game. When it sees your opponent's move, it says it and then your best reply through the glasses.")
                }

                Section {
                    LabeledContent("Status", value: glasses.statusText)
                    if glasses.isRegistered {
                        Button("Disconnect from Meta AI", role: .destructive) { glasses.disconnect() }
                    } else {
                        Button("Connect glasses") { glasses.connect() }
                    }
                } header: {
                    Text("Ray-Ban Meta")
                }

                Section {
                    LabeledContent("Engine", value: "Stockfish 17 (on device)")
                    LabeledContent("Neural networks", value: StockfishEngine.networksAvailable ? "Bundled" : "Missing")
                    LabeledContent("Board reading", value: "On device")
                } header: {
                    Text("About")
                } footer: {
                    Text("Everything runs on this iPhone; no images leave the phone. Stockfish is free software under the GNU GPL v3 (stockfishchess.org), bundled via ChessKitEngine.")
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
