import Foundation

/// UserDefaults keys shared by `SettingsView` (via @AppStorage) and the analyzer.
enum SettingsKey {
    static let playerColor = "playerColor"
    static let handsFree = "handsFree"
    static let detectTurn = "detectTurn"
    static let speakEvaluation = "speakEvaluation"
    static let thinkTimeMilliseconds = "thinkTimeMilliseconds"
    static let claudeModel = "claudeModel"
    static let recognitionEffort = "recognitionEffort"
    static let useGlassesPhoto = "useGlassesPhoto"
}

enum ClaudeModelOption: String, CaseIterable, Identifiable {
    case opus = "claude-opus-5-5"
    case sonnet = "claude-sonnet-5-5"
    case haiku = "claude-haiku-5-5"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .opus: return "Claude Opus 5.5 (most accurate)"
        case .sonnet: return "Claude Sonnet 5.5 (faster)"
        case .haiku: return "Claude Haiku 5.5 (fastest, cheapest)"
        }
    }
}

enum AppSettings {
    private static var defaults: UserDefaults { .standard }

    static func registerDefaults() {
        defaults.register(defaults: [
            SettingsKey.playerColor: PieceColor.white.rawValue,
            SettingsKey.handsFree: true,
            SettingsKey.detectTurn: true,
            SettingsKey.speakEvaluation: false,
            SettingsKey.thinkTimeMilliseconds: 1500,
            SettingsKey.claudeModel: ClaudeModelOption.opus.rawValue,
            SettingsKey.recognitionEffort: "medium",
            SettingsKey.useGlassesPhoto: true,
        ])
    }

    static var playerColor: PieceColor {
        PieceColor(rawValue: defaults.string(forKey: SettingsKey.playerColor) ?? "") ?? .white
    }
    static var handsFree: Bool { defaults.bool(forKey: SettingsKey.handsFree) }
    static var detectTurn: Bool { defaults.bool(forKey: SettingsKey.detectTurn) }
    static var speakEvaluation: Bool { defaults.bool(forKey: SettingsKey.speakEvaluation) }
    static var thinkTimeMilliseconds: Int { max(200, defaults.integer(forKey: SettingsKey.thinkTimeMilliseconds)) }
    static var claudeModel: String { defaults.string(forKey: SettingsKey.claudeModel) ?? ClaudeModelOption.opus.rawValue }
    static var recognitionEffort: String { defaults.string(forKey: SettingsKey.recognitionEffort) ?? "medium" }
    static var useGlassesPhoto: Bool { defaults.bool(forKey: SettingsKey.useGlassesPhoto) }

    static let apiKeyAccount = "anthropic-api-key"
    static var anthropicAPIKey: String {
        get { KeychainStore.read(apiKeyAccount) ?? "" }
        set { KeychainStore.write(newValue.trimmingCharacters(in: .whitespacesAndNewlines), for: apiKeyAccount) }
    }
}
