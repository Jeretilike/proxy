import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var glasses: GlassesManager

    @State private var apiKey = AppSettings.anthropicAPIKey
    @AppStorage(SettingsKey.playerColor) private var playerColor = PieceColor.white.rawValue
    @AppStorage(SettingsKey.handsFree) private var handsFree = true
    @AppStorage(SettingsKey.detectTurn) private var detectTurn = true
    @AppStorage(SettingsKey.speakEvaluation) private var speakEvaluation = false
    @AppStorage(SettingsKey.thinkTimeMilliseconds) private var thinkTime = 1500
    @AppStorage(SettingsKey.claudeModel) private var model = ClaudeModelOption.opus.rawValue
    @AppStorage(SettingsKey.recognitionEffort) private var effort = "medium"
    @AppStorage(SettingsKey.useGlassesPhoto) private var useGlassesPhoto = true

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
                }

                Section {
                    Toggle("Hands-free analysis", isOn: $handsFree)
                    Toggle("Only speak on my turn", isOn: $detectTurn)
                    Toggle("Speak the evaluation", isOn: $speakEvaluation)
                    Toggle("Use full photo from glasses", isOn: $useGlassesPhoto)
                } header: {
                    Text("Glasses")
                } footer: {
                    Text("Hands-free: when the view holds still on a changed board, the app reads it and speaks the best move through the glasses. \"Only speak on my turn\" stays quiet after you make your own move. A full photo is sharper than a video frame but takes a few seconds longer.")
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
                    SecureField("sk-ant-…", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: apiKey) { _, newValue in AppSettings.anthropicAPIKey = newValue }
                    Picker("Model", selection: $model) {
                        ForEach(ClaudeModelOption.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                    Picker("Reading effort", selection: $effort) {
                        Text("Low (fastest)").tag("low")
                        Text("Medium").tag("medium")
                        Text("High (most careful)").tag("high")
                    }
                } header: {
                    Text("Board recognition (Claude API)")
                } footer: {
                    Text("Photos are sent to Anthropic's API to read the pieces. Get a key at console.anthropic.com. It's stored in the iOS keychain on this phone.")
                }

                Section {
                    LabeledContent("Engine", value: "Stockfish 17 (on device)")
                    LabeledContent("Neural networks", value: StockfishEngine.networksAvailable ? "Bundled" : "Missing")
                } header: {
                    Text("About")
                } footer: {
                    Text("Stockfish is free software licensed under the GNU GPL v3 (stockfishchess.org). Chess engine integration by ChessKitEngine.")
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
