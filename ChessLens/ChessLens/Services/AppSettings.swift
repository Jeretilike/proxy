import Foundation

/// UserDefaults keys shared by `SettingsView` (via @AppStorage) and the game logic.
enum SettingsKey {
    static let playerColor = "playerColor"
    static let handsFree = "handsFree"
    static let announceOpponent = "announceOpponent"
    static let speakEvaluation = "speakEvaluation"
    static let thinkTimeMilliseconds = "thinkTimeMilliseconds"
}

enum AppSettings {
    private static var defaults: UserDefaults { .standard }

    static func registerDefaults() {
        defaults.register(defaults: [
            SettingsKey.playerColor: PieceColor.white.rawValue,
            SettingsKey.handsFree: true,
            SettingsKey.announceOpponent: true,
            SettingsKey.speakEvaluation: false,
            SettingsKey.thinkTimeMilliseconds: 1500,
        ])
    }

    static var playerColor: PieceColor {
        PieceColor(rawValue: defaults.string(forKey: SettingsKey.playerColor) ?? "") ?? .white
    }
    static var handsFree: Bool { defaults.bool(forKey: SettingsKey.handsFree) }
    static var announceOpponent: Bool { defaults.bool(forKey: SettingsKey.announceOpponent) }
    static var speakEvaluation: Bool { defaults.bool(forKey: SettingsKey.speakEvaluation) }
    static var thinkTimeMilliseconds: Int { max(200, defaults.integer(forKey: SettingsKey.thinkTimeMilliseconds)) }
}
