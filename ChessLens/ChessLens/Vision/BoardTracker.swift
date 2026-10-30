import CoreImage
import CoreImage.CIFilterBuiltins
import simd
import UIKit
import Vision

/// The four corners of the 64-square playing area in unit coordinates of the camera frame
/// (0…1, origin top-left), so they survive the glasses switching stream resolution.
struct BoardCorners: Equatable {
    var a1: CGPoint
    var h1: CGPoint
    var h8: CGPoint
    var a8: CGPoint

    init(a1: CGPoint, h1: CGPoint, h8: CGPoint, a8: CGPoint) {
        self.a1 = a1
        self.h1 = h1
        self.h8 = h8
        self.a8 = a8
    }

    /// Points in the order a1, h1, h8, a8.
    init(_ points: [CGPoint]) {
        self.init(a1: points[0], h1: points[1], h8: points[2], a8: points[3])
    }

    var points: [CGPoint] { [a1, h1, h8, a8] }

    func map(_ transform: (CGPoint) -> CGPoint) -> BoardCorners {
        BoardCorners(points.map(transform))
    }

    func translated(dx: CGFloat, dy: CGFloat) -> BoardCorners {
        map { CGPoint(x: $0.x + dx, y: $0.y + dy) }
    }

    /// Labels a quadrilateral seen from the player's seat: the near edge is the bottom of the image.
    /// `quad` is bottom-left, bottom-right, top-right, top-left.
    static func seated(_ quad: [CGPoint], playerColor: PieceColor) -> BoardCorners {
        playerColor == .white
            ? BoardCorners(a1: quad[0], h1: quad[1], h8: quad[2], a8: quad[3])
            : BoardCorners(a1: quad[2], h1: quad[3], h8: quad[0], a8: quad[1])
    }

    /// Where a point on the board lands in the frame. `u` runs a→h and `v` runs rank 1→8, both 0…1.
    /// (Heckbert's square-to-quadrilateral projective mapping.)
    func point(u: CGFloat, v: CGFloat) -> CGPoint {
        let (x0, y0) = (a1.x, a1.y), (x1, y1) = (h1.x, h1.y)
        let (x2, y2) = (h8.x, h8.y), (x3, y3) = (a8.x, a8.y)
        let dx3 = x0 - x1 + x2 - x3, dy3 = y0 - y1 + y2 - y3
        if abs(dx3) < 1e-9, abs(dy3) < 1e-9 {
            return CGPoint(x: x0 + (x1 - x0) * u + (x3 - x0) * v, y: y0 + (y1 - y0) * u + (y3 - y0) * v)
        }
        let dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2
        let det = dx1 * dy2 - dx2 * dy1
        guard abs(det) > 1e-12 else { return a1 }
        let g = (dx3 * dy2 - dx2 * dy3) / det
        let h = (dx1 * dy3 - dx3 * dy1) / det
        let a = x1 - x0 + g * x1, b = x3 - x0 + h * x3
        let d = y1 - y0 + g * y1, e = y3 - y0 + h * y3
        let w = g * u + h * v + 1
        return CGPoint(x: (a * u + b * v + x0) / w, y: (d * u + e * v + y0) / w)
    }

    /// Rejects corner sets that fly off the frame or collapse.
    var isPlausible: Bool {
        guard points.allSatisfy({ (-0.25...1.25).contains($0.x) && (-0.25...1.25).contains($0.y) }) else { return false }
        // Shoelace area in unit coordinates.
        var area: CGFloat = 0
        for i in 0..<4 {
            let p = points[i], q = points[(i + 1) % 4]
            area += p.x * q.y - q.x * p.y
        }
        return abs(area) / 2 > 0.01
    }
}

enum TrackerResult {
    case notCalibrated
    case lost
    case observed(SquareObservation, BoardCorners)
}

/// Keeps a reference view of the board and compares new frames with it square by square.
/// Each new frame is first aligned to the reference (the wearer's head moves), then the board is
/// warped to a top-down 256×256 image where every square is a 32×32 cell.
actor BoardTracker {
    private static let side = 256
    private static let cell = 32
    private static let inset = 6
    /// Median per-square difference above which the board is considered lost (0–255 scale).
    private static let lostThreshold: Float = 30

    private struct View {
        let frame: CGImage
        let corners: BoardCorners
        let pixels: [UInt8]
    }

    private struct Reference {
        let view: View
        let model: AppearanceModel
    }

    private let context = CIContext(options: [.cacheIntermediates: false])
    private var reference: Reference?
    private var pending: View?

    var isCalibrated: Bool { reference != nil }

    func reset() {
        reference = nil
        pending = nil
    }

    /// A first guess for the board's corners, from rectangle detection or a centred default.
    func suggestCorners(in frame: CGImage, playerColor: PieceColor) -> BoardCorners {
        let quad = detectQuadrilateral(in: frame) ?? [
            CGPoint(x: 0.15, y: 0.75), CGPoint(x: 0.85, y: 0.75),
            CGPoint(x: 0.85, y: 0.4), CGPoint(x: 0.15, y: 0.4),
        ]
        return BoardCorners.seated(quad, playerColor: playerColor)
    }

    /// Sets the reference view; `board` must be what is physically on the board right now.
    func calibrate(frame: CGImage, corners: BoardCorners, board: ChessBoard) -> Bool {
        guard let pixels = warp(frame, corners) else { return false }
        set(View(frame: frame, corners: corners, pixels: pixels), board: board)
        return true
    }

    /// Aligns `frame` with the reference and reports how each square changed.
    func observe(_ frame: CGImage) -> TrackerResult {
        guard let reference else { return .notCalibrated }
        guard let view = align(frame, to: reference.view) else { return .lost }
        pending = view
        let differences = Self.differences(reference.view.pixels, view.pixels)
        let features = Self.features(view.pixels)
        let contents = (0..<64).map { reference.model.probabilities(features[$0], light: Square(index: $0).isLight) }
        return .observed(SquareObservation(differences: differences, contents: contents), view.corners)
    }

    /// Makes the last observed frame the new reference, showing `board`.
    func commitPending(board: ChessBoard) {
        guard let pending else { return }
        set(pending, board: board)
    }

    /// Re-anchors on the current frame after a correction made on the phone.
    func resync(frame: CGImage, board: ChessBoard) -> BoardCorners? {
        guard let reference, let view = align(frame, to: reference.view) else { return nil }
        set(view, board: board)
        return view.corners
    }

    private func set(_ view: View, board: ChessBoard) {
        reference = Reference(view: view, model: AppearanceModel(features: Self.features(view.pixels), board: board))
        pending = nil
    }

    // MARK: Alignment

    private func align(_ frame: CGImage, to reference: View) -> View? {
        var candidates = [reference.corners]
        candidates += registrationCandidates(from: reference.frame, to: frame, corners: reference.corners)
        if let quad = detectQuadrilateral(in: frame) {
            candidates.append(closestLabeling(of: quad, to: reference.corners))
        }

        var best: (view: View, error: Float)?
        func evaluate(_ corners: BoardCorners) {
            guard corners.isPlausible, let pixels = warp(frame, corners) else { return }
            let error = Self.differences(reference.pixels, pixels).median
            if best == nil || error < best!.error {
                best = (View(frame: frame, corners: corners, pixels: pixels), error)
            }
        }
        candidates.forEach(evaluate)
        guard best != nil else { return nil }

        // Fine-tune with small shifts; registration and rectangle detection are only approximate.
        let width = CGFloat(frame.width), height = CGFloat(frame.height)
        let c = best!.view.corners
        let boardPixels = hypot((c.h1.x - c.a1.x) * width, (c.h1.y - c.a1.y) * height)
        var step = boardPixels * 0.03
        for _ in 0..<3 {
            let center = best!.view.corners
            for dx in [-step, 0, step] {
                for dy in [-step, 0, step] where dx != 0 || dy != 0 {
                    evaluate(center.translated(dx: dx / width, dy: dy / height))
                }
            }
            step /= 2
        }

        guard let result = best, result.error <= Self.lostThreshold else { return nil }
        return result.view
    }

    /// Corner positions predicted by Vision's homographic registration. The transform's coordinate
    /// convention isn't pinned down here, so every plausible reading is returned and `align`
    /// keeps whichever lines the board up best.
    private func registrationCandidates(from reference: CGImage, to frame: CGImage,
                                        corners: BoardCorners) -> [BoardCorners] {
        let request = VNHomographicImageRegistrationRequest(targetedCGImage: frame, options: [:])
        do {
            try VNImageRequestHandler(cgImage: reference, options: [:]).perform([request])
        } catch {
            return []
        }
        guard let observation = request.results?.first else { return [] }
        let warp = observation.warpTransform
        let referenceSize = SIMD2<Float>(Float(reference.width), Float(reference.height))
        let frameSize = SIMD2<Float>(Float(frame.width), Float(frame.height))

        var results: [BoardCorners] = []
        for matrix in [warp, warp.inverse] {
            for flipY in [false, true] {
                for pixelSpace in [true, false] {
                    results.append(corners.map { p in
                        var q = SIMD2<Float>(Float(p.x), Float(flipY ? 1 - p.y : p.y))
                        if pixelSpace { q *= referenceSize }
                        let v = matrix * SIMD3<Float>(q.x, q.y, 1)
                        guard abs(v.z) > 1e-6 else { return p }
                        var r = SIMD2<Float>(v.x / v.z, v.y / v.z)
                        if pixelSpace { r /= frameSize }
                        return CGPoint(x: CGFloat(r.x), y: CGFloat(flipY ? 1 - r.y : r.y))
                    })
                }
            }
        }
        return results
    }

    /// The largest rectangle-like shape, as bottom-left, bottom-right, top-right, top-left.
    private func detectQuadrilateral(in frame: CGImage) -> [CGPoint]? {
        let request = VNDetectRectanglesRequest()
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1.0
        request.minimumSize = 0.2
        request.quadratureTolerance = 45
        request.minimumConfidence = 0.3
        request.maximumObservations = 6
        do {
            try VNImageRequestHandler(cgImage: frame, options: [:]).perform([request])
        } catch {
            return nil
        }
        guard let largest = request.results?.max(by: {
            $0.boundingBox.width * $0.boundingBox.height < $1.boundingBox.width * $1.boundingBox.height
        }) else { return nil }
        // Vision uses a bottom-left origin.
        func flip(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x, y: 1 - p.y) }
        return [flip(largest.bottomLeft), flip(largest.bottomRight), flip(largest.topRight), flip(largest.topLeft)]
    }

    private func closestLabeling(of quad: [CGPoint], to corners: BoardCorners) -> BoardCorners {
        let rotations = (0..<4).map { k in BoardCorners((0..<4).map { quad[($0 + k) % 4] }) }
        func distance(_ c: BoardCorners) -> CGFloat {
            zip(c.points, corners.points).reduce(0) { $0 + hypot($1.0.x - $1.1.x, $1.0.y - $1.1.y) }
        }
        return rotations.min { distance($0) < distance($1) }!
    }

    // MARK: Warping

    /// The board as a top-down RGBA image: rank 8 along the top, file a on the left.
    private func warp(_ frame: CGImage, _ corners: BoardCorners) -> [UInt8]? {
        let width = CGFloat(frame.width), height = CGFloat(frame.height)
        // Core Image uses a bottom-left origin.
        func ciPoint(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * width, y: (1 - p.y) * height) }

        let filter = CIFilter.perspectiveCorrection()
        filter.inputImage = CIImage(cgImage: frame)
        filter.topLeft = ciPoint(corners.a8)
        filter.topRight = ciPoint(corners.h8)
        filter.bottomLeft = ciPoint(corners.a1)
        filter.bottomRight = ciPoint(corners.h1)
        guard let output = filter.outputImage else { return nil }

        let extent = output.extent
        guard extent.width >= 16, extent.height >= 16, extent.width < 10_000, extent.height < 10_000 else { return nil }
        let side = CGFloat(Self.side)
        let normalized = output
            .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
            .transformed(by: CGAffineTransform(scaleX: side / extent.width, y: side / extent.height))
        guard let image = context.createCGImage(normalized, from: CGRect(x: 0, y: 0, width: side, height: side)) else {
            return nil
        }

        var pixels = [UInt8](repeating: 0, count: Self.side * Self.side * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let bitmap = CGContext(
                data: buffer.baseAddress, width: Self.side, height: Self.side, bitsPerComponent: 8,
                bytesPerRow: Self.side * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            bitmap.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        return drawn ? pixels : nil
    }

    // MARK: Per-square measurements

    /// The sampled area of a square in the warped image (memory rows run top to bottom, so rank 8 first).
    private static func region(_ index: Int) -> (x: Int, y: Int, size: Int) {
        let square = Square(index: index)
        return (square.file * cell + inset, (7 - square.rank) * cell + inset, cell - 2 * inset)
    }

    private static func meanLuminance(_ pixels: [UInt8]) -> Float {
        var total: Float = 0
        var i = 0
        while i < pixels.count {
            total += 0.299 * Float(pixels[i]) + 0.587 * Float(pixels[i + 1]) + 0.114 * Float(pixels[i + 2])
            i += 16 // every 4th pixel is plenty
        }
        return total / Float(pixels.count / 16)
    }

    /// Mean absolute colour difference per square after evening out overall brightness.
    static func differences(_ a: [UInt8], _ b: [UInt8]) -> [Float] {
        let gain = min(max(meanLuminance(a) / max(meanLuminance(b), 1), 0.6), 1.6)
        return (0..<64).map { index in
            let r = region(index)
            var total: Float = 0
            for y in r.y..<(r.y + r.size) {
                for x in r.x..<(r.x + r.size) {
                    let o = (y * side + x) * 4
                    total += abs(Float(a[o]) - gain * Float(b[o]))
                        + abs(Float(a[o + 1]) - gain * Float(b[o + 1]))
                        + abs(Float(a[o + 2]) - gain * Float(b[o + 2]))
                }
            }
            return total / Float(r.size * r.size * 3)
        }
    }

    /// Mean colour and texture of each square, relative to the board's overall brightness.
    static func features(_ pixels: [UInt8]) -> [SIMD4<Float>] {
        var raw: [SIMD4<Float>] = []
        var brightness: Float = 0
        for index in 0..<64 {
            let r = region(index)
            var sum = SIMD3<Float>(repeating: 0)
            var luminance: Float = 0, luminanceSquared: Float = 0
            for y in r.y..<(r.y + r.size) {
                for x in r.x..<(r.x + r.size) {
                    let o = (y * side + x) * 4
                    let rgb = SIMD3<Float>(Float(pixels[o]), Float(pixels[o + 1]), Float(pixels[o + 2]))
                    sum += rgb
                    let l = 0.299 * rgb.x + 0.587 * rgb.y + 0.114 * rgb.z
                    luminance += l
                    luminanceSquared += l * l
                }
            }
            let count = Float(r.size * r.size)
            let mean = luminance / count
            let deviation = max(0, luminanceSquared / count - mean * mean).squareRoot()
            let color = sum / count
            raw.append(SIMD4<Float>(color.x, color.y, color.z, deviation))
            brightness += mean
        }
        let scale = max(brightness / 64, 1)
        return raw.map { $0 / scale }
    }
}

/// Learns what empty squares, White pieces and Black pieces look like (separately on light and
/// dark squares) from a view whose position is known, then classifies squares in later views.
struct AppearanceModel {
    private var centroids: [[SIMD4<Float>?]] // [dark = 0 / light = 1][empty, white, black]
    private var spread: SIMD4<Float>

    init(features: [SIMD4<Float>], board: ChessBoard) {
        var sums = Array(repeating: Array(repeating: SIMD4<Float>(repeating: 0), count: 3), count: 2)
        var counts = Array(repeating: Array(repeating: 0, count: 3), count: 2)
        for index in 0..<64 {
            let shade = Square(index: index).isLight ? 1 : 0
            let kind = Self.contentClass(board.squares[index])
            sums[shade][kind] += features[index]
            counts[shade][kind] += 1
        }
        centroids = (0..<2).map { shade in
            (0..<3).map { kind in counts[shade][kind] > 0 ? sums[shade][kind] / Float(counts[shade][kind]) : nil }
        }

        let mean = features.reduce(SIMD4<Float>(repeating: 0), +) / Float(features.count)
        let variance = features.reduce(SIMD4<Float>(repeating: 0)) { $0 + ($1 - mean) * ($1 - mean) } / Float(features.count)
        let deviation = SIMD4<Float>(variance.x.squareRoot(), variance.y.squareRoot(),
                                     variance.z.squareRoot(), variance.w.squareRoot())
        spread = pointwiseMax(deviation, SIMD4<Float>(repeating: 0.02))
    }

    static func contentClass(_ piece: Piece?) -> Int {
        switch piece?.color {
        case nil: return 0
        case .white?: return 1
        case .black?: return 2
        }
    }

    /// Probabilities of [empty, white, black], kept within 0.05…0.95 so one square can't dominate.
    func probabilities(_ feature: SIMD4<Float>, light: Bool) -> [Float] {
        let shade = light ? 1 : 0
        let logits: [Float] = (0..<3).map { kind in
            guard let centroid = centroids[shade][kind] ?? centroids[1 - shade][kind] else { return -1e30 }
            let d = (feature - centroid) / spread
            return -0.5 * simd_length_squared(d)
        }
        let top = logits.max() ?? 0
        let weights = logits.map { exp(max($0 - top, -80)) }
        let total = weights.reduce(0, +)
        return weights.map { 0.05 + 0.9 * $0 / total }
    }
}
