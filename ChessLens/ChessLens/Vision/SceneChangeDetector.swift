import CoreGraphics
import Foundation
import UIKit

/// Decides when the camera has settled on a new view worth checking, so hands-free mode
/// only compares the board once the wearer's head and hands are still.
struct SceneChangeDetector {
    /// Average per-pixel difference (0–255) between consecutive frames below which the view is still.
    var stillThreshold: Double = 6
    /// Average difference from the last checked view that counts as a change on its own.
    var changeThreshold: Double = 3
    /// How long the view must hold still before it is checked.
    var settleTime: TimeInterval = 1.0
    /// While the view stays still, re-check this often anyway (checks are cheap and on-device).
    var recheckInterval: TimeInterval = 4

    private var previous: [UInt8]?
    private var stillSince: Date?
    private var lastAnalyzed: [UInt8]?
    private var lastAnalyzedAt: Date?
    private var movedSinceAnalysis = true

    /// Feeds one frame; returns true once the view has been still for `settleTime` after any
    /// movement (a hand making a move, the wearer's head turning), after a visible change, or
    /// every `recheckInterval` while nothing seems to happen.
    mutating func shouldAnalyze(_ image: UIImage, now: Date = Date()) -> Bool {
        guard let thumb = Self.thumbnail(image) else { return false }
        defer { previous = thumb }

        guard let previous, Self.difference(previous, thumb) < stillThreshold else {
            stillSince = nil
            movedSinceAnalysis = true
            return false
        }
        let since = stillSince ?? now
        stillSince = since
        guard now.timeIntervalSince(since) >= settleTime else { return false }

        if movedSinceAnalysis || lastAnalyzed == nil { return true }
        if let lastAnalyzed, Self.difference(lastAnalyzed, thumb) >= changeThreshold { return true }
        if let lastAnalyzedAt, now.timeIntervalSince(lastAnalyzedAt) >= recheckInterval { return true }
        return false
    }

    /// Records the frame that was just checked.
    mutating func markAnalyzed(_ image: UIImage, now: Date = Date()) {
        lastAnalyzed = Self.thumbnail(image)
        lastAnalyzedAt = now
        movedSinceAnalysis = false
    }

    mutating func reset() {
        previous = nil
        stillSince = nil
        lastAnalyzed = nil
        lastAnalyzedAt = nil
        movedSinceAnalysis = true
    }

    private static let side = 24

    /// A tiny grayscale copy of the image; small enough to compare cheaply on every frame.
    static func thumbnail(_ image: UIImage) -> [UInt8]? {
        guard let cgImage = image.uprightCGImage() else { return nil }
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
