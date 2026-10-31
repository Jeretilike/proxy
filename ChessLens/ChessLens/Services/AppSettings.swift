import Foundation

/// UserDefaults keys shared by `SettingsView` (via @AppStorage) and the game logic.
enum SettingsKey {
    static let playMode = "playMode"
    static let playerColor = "playerColor"
    static let handsFree = "handsFree"
    static let announceOpponent = "announceOpponent"
    static let speakEvaluation = "speakEvaluation"
    static let thinkTimeMilliseconds = "thinkTimeMilliseconds"
    static let checkIntervalSeconds = "checkIntervalSeconds"
}

/// Where the game is played: on a physical board seen through the glasses, or on the screen.
enum PlayMode: String, CaseIterable, Identifiable {
    case glasses
    case screen

    var id: String { rawValue }
    var label: String { self == .glasses ? "Glasses" : "Screen" }
}

enum AppSettings {
    private static var defaults: UserDefaults { .standard }

    static func registerDefaults() {
        defaults.register(defaults: [
            SettingsKey.playMode: PlayMode.glasses.rawValue,
            SettingsKey.playerColor: PieceColor.white.rawValue,
            SettingsKey.handsFree: true,
            SettingsKey.announceOpponent: true,
            SettingsKey.speakEvaluation: false,
            SettingsKey.thinkTimeMilliseconds: 1500,
            SettingsKey.checkIntervalSeconds: 5,
        ])
    }

    static var playMode: PlayMode {
        get { PlayMode(rawValue: defaults.string(forKey: SettingsKey.playMode) ?? "") ?? .glasses }
        set { defaults.set(newValue.rawValue, forKey: SettingsKey.playMode) }
    }
    static var playerColor: PieceColor {
        PieceColor(rawValue: defaults.string(forKey: SettingsKey.playerColor) ?? "") ?? .white
    }
    static var handsFree: Bool { defaults.bool(forKey: SettingsKey.handsFree) }
    static var announceOpponent: Bool { defaults.bool(forKey: SettingsKey.announceOpponent) }
    static var speakEvaluation: Bool { defaults.bool(forKey: SettingsKey.speakEvaluation) }
    /// How often the glasses check the board even if the view never holds still.
    static var checkIntervalSeconds: Double { max(2, defaults.double(forKey: SettingsKey.checkIntervalSeconds)) }
    static var thinkTimeMilliseconds: Int { max(200, defaults.integer(forKey: SettingsKey.thinkTimeMilliseconds)) }
}
