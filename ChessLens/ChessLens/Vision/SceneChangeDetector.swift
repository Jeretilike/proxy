import CoreGraphics
import Foundation
import UIKit

/// Decides when the camera has settled on a new view worth sending for recognition, so
/// hands-free mode only calls the vision API after something on the board changed.
struct SceneChangeDetector {
    /// Average per-pixel difference (0–255) below which two consecutive frames count as still.
    var stillThreshold: Double = 7
    /// Average difference from the last analysed view above which the scene counts as new.
    var changeThreshold: Double = 9
    /// How long the view must hold still before it is analysed.
    var settleTime: TimeInterval = 1.2

    private var previous: [UInt8]?
    private var stillSince: Date?
    private var lastAnalyzed: [UInt8]?

    /// Feeds one frame; returns true when the view has been still for `settleTime` and
    /// differs from the last view that was analysed.
    mutating func shouldAnalyze(_ image: UIImage, now: Date = Date()) -> Bool {
        guard let thumb = Self.thumbnail(image) else { return false }
        defer { previous = thumb }

        guard let previous, Self.difference(previous, thumb) < stillThreshold else {
            stillSince = nil
            return false
        }
        let since = stillSince ?? now
        stillSince = since
        guard now.timeIntervalSince(since) >= settleTime else { return false }

        if let lastAnalyzed, Self.difference(lastAnalyzed, thumb) < changeThreshold { return false }
        return true
    }

    /// Records the frame that was just sent for analysis.
    mutating func markAnalyzed(_ image: UIImage) {
        lastAnalyzed = Self.thumbnail(image)
    }

    mutating func reset() {
        previous = nil
        stillSince = nil
        lastAnalyzed = nil
    }

    private static let side = 24

    /// A tiny grayscale copy of the image; small enough to compare cheaply on every frame.
    static func thumbnail(_ image: UIImage) -> [UInt8]? {
        guard let cgImage = image.cgImage else { return nil }
        var pixels = [UInt8](repeating: 0, count: side * side)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side, space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.interpolationQuality = .low
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        return drawn ? pixels : nil
    }

    static func difference(_ a: [UInt8], _ b: [UInt8]) -> Double {
        guard a.count == b.count, !a.isEmpty else { return .infinity }
        var total = 0
        for i in 0..<a.count { total += abs(Int(a[i]) - Int(b[i])) }
        return Double(total) / Double(a.count)
    }
}
