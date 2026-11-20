import CoreGraphics
import Foundation

/// Finds a chessboard by its grid instead of its outline. The 7×7 inner corners where four
/// squares meet ("X-corners") look the same on wooden boards, vinyl boards and chess.com boards
/// on a screen, and they don't depend on the board's edge being visible.
///
/// 1. Saddle points of the blurred image are candidates; each is kept only if brightness on a
///    small circle around it alternates light/dark/light/dark (an X-corner, not a piece outline,
///    a T-junction at the border or wood grain).
/// 2. A lattice is grown from pairs of neighbouring X-corners and fitted with a homography
///    (lattice coordinates → pixels); the hypothesis matching the most X-corners wins.
/// 3. Every 8×8 window of that lattice consistent with the matches becomes a candidate board
///    outline; the caller picks the right window and orientation by comparing with the position.
///
/// Mirrors a Python prototype that found the grid on most of 30 real board photos (failures were
/// folding boards bent at the hinge) and on 199 of 200 synthetic chess.com/wooden boards.
enum ChessGridDetector {
    /// Frames are analysed at this size (long side, pixels).
    static let maxSide = 640

    /// Candidate board outlines in unit coordinates (0…1, origin top-left), strongest grid first.
    /// Each is the corners at lattice (−1,−1), (7,−1), (7,7), (−1,7) of an 8×8 window, i.e. a
    /// quadrilateral in consistent winding but with unknown labelling.
    static func boardOutlines(in frame: CGImage) -> [[CGPoint]] {
        guard let image = GrayImage(frame, maxSide: maxSide) else { return [] }
        let blurred = image.blurred(sigma: 1.2)
        let (points, weights) = xCorners(in: blurred, keep: 250)
        guard points.count >= 9, var lattice = findLattice(points: points) else { return [] }
        guard lattice.matches.count >= 10 else { return [] }
        lattice = refine(lattice, points: points)

        let coordinates = Array(lattice.matches.keys)
        let minI = coordinates.map(\.i).min()!, maxI = coordinates.map(\.i).max()!
        let minJ = coordinates.map(\.j).min()!, maxJ = coordinates.map(\.j).max()!

        var windows: [(weight: Float, ox: Int, oy: Int)] = []
        for ox in min(minI, maxI - 6)...max(minI, maxI - 6) {
            for oy in min(minJ, maxJ - 6)...max(minJ, maxJ - 6) {
                var weight: Float = 0
                for (coordinate, index) in lattice.matches
                where (ox...ox + 6).contains(coordinate.i) && (oy...oy + 6).contains(coordinate.j) {
                    weight += weights[index]
                }
                windows.append((weight, ox, oy))
            }
        }
        let top = windows.map(\.weight).max() ?? 0
        let size = SIMD2<Double>(Double(image.width), Double(image.height))
        return windows
            .filter { $0.weight >= 0.6 * top }
            .sorted { $0.weight > $1.weight }
            .map { window in
                [(window.ox - 1, window.oy - 1), (window.ox + 7, window.oy - 1),
                 (window.ox + 7, window.oy + 7), (window.ox - 1, window.oy + 7)].map { i, j in
                    let p = lattice.homography.apply(SIMD2(Double(i), Double(j))) / size
                    return CGPoint(x: p.x, y: p.y)
                }
            }
    }

    // MARK: X-corners

    private static let ringSamples = 16
    private static let ringRadii: [Float] = [3, 5, 8, 12]
    private static let ringCos = (0..<ringSamples).map { cos(2 * Float.pi * Float($0) / Float(ringSamples)) }
    private static let ringSin = (0..<ringSamples).map { sin(2 * Float.pi * Float($0) / Float(ringSamples)) }
    /// cos/sin of 2πkn/16 for the harmonics k = 1…4.
    private static let harmonicCos = (1...4).map { k in (0..<ringSamples).map { cos(2 * Float.pi * Float(k * $0) / Float(ringSamples)) } }
    private static let harmonicSin = (1...4).map { k in (0..<ringSamples).map { sin(2 * Float.pi * Float(k * $0) / Float(ringSamples)) } }

    /// The strongest X-corners and how X-like each is.
    static func xCorners(in image: GrayImage, keep: Int) -> ([SIMD2<Double>], [Float]) {
        let candidates = saddleCandidates(in: image, limit: 1500)
        var scored: [(SIMD2<Double>, Float)] = []
        for p in candidates {
            let score = xness(image, x: Float(p.x), y: Float(p.y))
            if score > 6 { scored.append((p, score)) }
        }
        scored.sort { $0.1 > $1.1 }
        let kept = scored.prefix(keep)
        return (kept.map(\.0), kept.map(\.1))
    }

    /// Local maxima of the saddle response det(−Hessian) of the blurred image.
    private static func saddleCandidates(in image: GrayImage, limit: Int) -> [SIMD2<Double>] {
        let w = image.width, h = image.height
        let g = image.pixels
        var response = [Float](repeating: 0, count: w * h)
        var peak: Float = 0
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                let gxx = g[i + 1] - 2 * g[i] + g[i - 1]
                let gyy = g[i + w] - 2 * g[i] + g[i - w]
                let gxy = (g[i + w + 1] - g[i + w - 1] - g[i - w + 1] + g[i - w - 1]) / 4
                let s = gxy * gxy - gxx * gyy
                if s > 0 {
                    response[i] = s
                    peak = max(peak, s)
                }
            }
        }
        guard peak > 0 else { return [] }

        let threshold = 0.002 * peak
        let radius = 3
        var maxima: [(Int, Int, Float)] = []
        for y in radius..<(h - radius) {
            for x in radius..<(w - radius) {
                let s = response[y * w + x]
                guard s > threshold else { continue }
                var isMax = true
                neighbourhood: for dy in -radius...radius {
                    for dx in -radius...radius where response[(y + dy) * w + x + dx] > s {
                        isMax = false
                        break neighbourhood
                    }
                }
                if isMax { maxima.append((x, y, s)) }
            }
        }
        maxima.sort { $0.2 > $1.2 }
        return maxima.prefix(limit).map { SIMD2(Double($0.0), Double($0.1)) }
    }

    /// 2nd-harmonic strength of the brightness on a circle around (x, y), minus the other
    /// harmonics, best over a few radii. High only where four squares meet.
    private static func xness(_ image: GrayImage, x: Float, y: Float) -> Float {
        var best = -Float.greatestFiniteMagnitude
        var ring = [Float](repeating: 0, count: ringSamples)
        for r in ringRadii {
            for n in 0..<ringSamples {
                ring[n] = image.sample(x: x + r * ringCos[n], y: y + r * ringSin[n])
            }
            var magnitude = [Float](repeating: 0, count: 4)
            for k in 0..<4 {
                var re: Float = 0, im: Float = 0
                for n in 0..<ringSamples {
                    re += ring[n] * harmonicCos[k][n]
                    im -= ring[n] * harmonicSin[k][n]
                }
                magnitude[k] = (re * re + im * im).squareRoot() / Float(ringSamples)
            }
            best = max(best, magnitude[1] - magnitude[0] - magnitude[2] - 0.5 * magnitude[3])
        }
        return best
    }

    // MARK: Lattice

    struct LatticeCoordinate: Hashable {
        let i: Int
        let j: Int
    }

    struct Lattice {
        var homography: Homography
        /// Lattice coordinate → index of the matched X-corner.
        var matches: [LatticeCoordinate: Int]
    }

    private static func findLattice(points: [SIMD2<Double>]) -> Lattice? {
        let sample = points.prefix(80)
        let nearestDistances = sample.map { p in
            points.map { simd_distance_d($0, p) }.sorted().dropFirst().first ?? 0
        }.sorted()
        let cellSize = max(4, nearestDistances[nearestDistances.count / 2])
        let index = PointIndex(points: points, cellSize: cellSize)

        var best: Lattice?
        var hypotheses = 0
        let unitSquare = [SIMD2<Double>(0, 0), SIMD2(1, 0), SIMD2(0, 1), SIMD2(1, 1)]

        for seed in 0..<min(60, points.count) {
            let p = points[seed]
            let neighbours = points.indices
                .filter { $0 != seed }
                .sorted { simd_distance_d(points[$0], p) < simd_distance_d(points[$1], p) }
                .prefix(6)
            let near = Array(neighbours)
            for a in 0..<near.count {
                for b in (a + 1)..<max(a + 1, near.count) {
                    let u = points[near[a]] - p, v = points[near[b]] - p
                    let lu = simd_length_d(u), lv = simd_length_d(v)
                    guard lu >= 3, lv >= 3, (0.5...2).contains(lu / lv) else { continue }
                    guard abs(simd_dot_d(u, v)) / (lu * lv) <= 0.6 else { continue }
                    guard let s = index.nearest(to: p + u + v, within: 0.25 * min(lu, lv)) else { continue }
                    hypotheses += 1
                    guard let seedHomography = Homography(from: unitSquare,
                                                          to: [p, points[near[a]], points[near[b]], points[s]]),
                          let grown = grow(seedHomography, points: points, index: index)
                    else { continue }
                    if grown.matches.count > (best?.matches.count ?? 0) { best = grown }
                    if (best?.matches.count ?? 0) >= 45 || hypotheses >= 600 { return best }
                }
            }
        }
        return best
    }

    /// Repeatedly predicts lattice points, matches them to X-corners and refits the homography.
    private static func grow(_ start: Homography, points: [SIMD2<Double>], index: PointIndex) -> Lattice? {
        var homography = start
        var matches: [LatticeCoordinate: Int] = [:]
        for _ in 0..<4 {
            var owner: [Int: (distance: Double, coordinate: LatticeCoordinate)] = [:]
            matches = [:]
            for i in -9...9 {
                for j in -9...9 {
                    let predicted = homography.apply(SIMD2(Double(i), Double(j)))
                    let right = homography.apply(SIMD2(Double(i + 1), Double(j)))
                    let up = homography.apply(SIMD2(Double(i), Double(j + 1)))
                    let scale = min(simd_distance_d(right, predicted), simd_distance_d(up, predicted))
                    guard predicted.x.isFinite, predicted.y.isFinite, scale.isFinite, scale >= 3,
                          let k = index.nearest(to: predicted, within: 0.22 * scale)
                    else { continue }
                    let distance = simd_distance_d(points[k], predicted)
                    let coordinate = LatticeCoordinate(i: i, j: j)
                    if let previous = owner[k] {
                        if previous.distance <= distance { continue }
                        matches[previous.coordinate] = nil
                    }
                    owner[k] = (distance, coordinate)
                    matches[coordinate] = k
                }
            }
            guard matches.count >= 4 else { return nil }
            let (source, target) = correspondences(matches, points: points)
            guard let refit = Homography(from: source, to: target) else { return nil }
            homography = refit
        }
        return Lattice(homography: homography, matches: matches)
    }

    /// Drops matches that sit far from the fitted grid and refits.
    private static func refine(_ lattice: Lattice, points: [SIMD2<Double>]) -> Lattice {
        var current = lattice
        for _ in 0..<2 {
            let entries = Array(current.matches)
            let residuals = entries.map { coordinate, index -> Double in
                let predicted = current.homography.apply(SIMD2(Double(coordinate.i), Double(coordinate.j)))
                let right = current.homography.apply(SIMD2(Double(coordinate.i + 1), Double(coordinate.j)))
                return simd_distance_d(predicted, points[index]) / max(simd_distance_d(right, predicted), 1)
            }
            let sorted = residuals.sorted()
            let limit = max(0.08, 2.5 * sorted[sorted.count / 2])
            let kept = Dictionary(uniqueKeysWithValues: zip(entries, residuals).filter { $0.1 < limit }.map { ($0.0.key, $0.0.value) })
            guard kept.count >= 8 else { break }
            let (source, target) = correspondences(kept, points: points)
            guard let refit = Homography(from: source, to: target) else { break }
            current = Lattice(homography: refit, matches: kept)
        }
        return current
    }

    private static func correspondences(_ matches: [LatticeCoordinate: Int],
                                        points: [SIMD2<Double>]) -> ([SIMD2<Double>], [SIMD2<Double>]) {
        let entries = Array(matches)
        return (entries.map { SIMD2(Double($0.key.i), Double($0.key.j)) }, entries.map { points[$0.value] })
    }
}

// MARK: - Supporting types

/// An 8-bit grayscale copy of a frame, scaled down, as Floats.
struct GrayImage {
    let width: Int
    let height: Int
    var pixels: [Float]

    init?(_ image: CGImage, maxSide: Int) {
        let scale = min(1, Double(maxSide) / Double(max(image.width, image.height)))
        let w = max(8, Int((Double(image.width) * scale).rounded()))
        let h = max(8, Int((Double(image.height) * scale).rounded()))
        var bytes = [UInt8](repeating: 0, count: w * h)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        width = w
        height = h
        pixels = bytes.map(Float.init)
    }

    private init(width: Int, height: Int, pixels: [Float]) {
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    /// Separable Gaussian blur.
    func blurred(sigma: Float) -> GrayImage {
        let radius = Int((3 * sigma).rounded(.up))
        var kernel = (-radius...radius).map { exp(-Float($0 * $0) / (2 * sigma * sigma)) }
        let total = kernel.reduce(0, +)
        kernel = kernel.map { $0 / total }

        var horizontal = [Float](repeating: 0, count: pixels.count)
        for y in 0..<height {
            let row = y * width
            for x in 0..<width {
                var sum: Float = 0
                for k in -radius...radius {
                    sum += kernel[k + radius] * pixels[row + min(max(x + k, 0), width - 1)]
                }
                horizontal[row + x] = sum
            }
        }
        var vertical = [Float](repeating: 0, count: pixels.count)
        for y in 0..<height {
            for x in 0..<width {
                var sum: Float = 0
                for k in -radius...radius {
                    sum += kernel[k + radius] * horizontal[min(max(y + k, 0), height - 1) * width + x]
                }
                vertical[y * width + x] = sum
            }
        }
        return GrayImage(width: width, height: height, pixels: vertical)
    }

    /// Bilinear sample with edge clamping.
    func sample(x: Float, y: Float) -> Float {
        let cx = min(max(x, 0), Float(width) - 1.001)
        let cy = min(max(y, 0), Float(height) - 1.001)
        let x0 = Int(cx), y0 = Int(cy)
        let fx = cx - Float(x0), fy = cy - Float(y0)
        let i = y0 * width + x0
        return pixels[i] * (1 - fx) * (1 - fy) + pixels[i + 1] * fx * (1 - fy)
            + pixels[i + width] * (1 - fx) * fy + pixels[i + width + 1] * fx * fy
    }
}

/// A 2D projective transform, fitted by least squares with Hartley normalisation.
struct Homography {
    /// Row-major 3×3.
    var m: [Double]

    init(matrix: [Double]) {
        m = matrix
    }

    /// Fits a transform mapping `source[i]` to `target[i]` (at least 4 pairs).
    init?(from source: [SIMD2<Double>], to target: [SIMD2<Double>]) {
        guard source.count >= 4, source.count == target.count else { return nil }
        let sourceNorm = Homography.normalising(source)
        let targetNorm = Homography.normalising(target)
        let ps = source.map { sourceNorm.apply($0) }
        let pt = target.map { targetNorm.apply($0) }

        // Solve A h = b for the 8 unknowns (h33 = 1) via normal equations.
        var ata = [Double](repeating: 0, count: 64)
        var atb = [Double](repeating: 0, count: 8)
        for (p, q) in zip(ps, pt) {
            let rows: [([Double], Double)] = [
                ([p.x, p.y, 1, 0, 0, 0, -q.x * p.x, -q.x * p.y], q.x),
                ([0, 0, 0, p.x, p.y, 1, -q.y * p.x, -q.y * p.y], q.y),
            ]
            for (row, rhs) in rows {
                for r in 0..<8 {
                    atb[r] += row[r] * rhs
                    for c in 0..<8 { ata[r * 8 + c] += row[r] * row[c] }
                }
            }
        }
        guard let h = Homography.solve(ata, atb, size: 8) else { return nil }
        let normalized = Homography(matrix: h + [1])
        // Undo the normalisations: H = T_target⁻¹ · H_n · T_source
        guard let targetInverse = targetNorm.inverse else { return nil }
        self = targetInverse.multiplied(by: normalized).multiplied(by: sourceNorm)
        let last = m[8]
        guard m.allSatisfy(\.isFinite), abs(last) > 1e-12 else { return nil }
        m = m.map { $0 / last }
    }

    func apply(_ p: SIMD2<Double>) -> SIMD2<Double> {
        let w = m[6] * p.x + m[7] * p.y + m[8]
        return SIMD2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    func multiplied(by other: Homography) -> Homography {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 {
            for j in 0..<3 {
                r[i * 3 + j] = (0..<3).reduce(0) { $0 + m[i * 3 + $1] * other.m[$1 * 3 + j] }
            }
        }
        return Homography(matrix: r)
    }

    var inverse: Homography? {
        let a = m
        let det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        guard abs(det) > 1e-15 else { return nil }
        let inv = [
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        ]
        return Homography(matrix: inv.map { $0 / det })
    }

    /// Similarity transform moving the points' centroid to the origin with mean distance √2.
    private static func normalising(_ points: [SIMD2<Double>]) -> Homography {
        let centroid = points.reduce(SIMD2<Double>(0, 0), +) / Double(points.count)
        let meanDistance = points.map { simd_distance_d($0, centroid) }.reduce(0, +) / Double(points.count)
        let s = 2.0.squareRoot() / max(meanDistance, 1e-9)
        return Homography(matrix: [s, 0, -s * centroid.x, 0, s, -s * centroid.y, 0, 0, 1])
    }

    /// Gaussian elimination with partial pivoting.
    private static func solve(_ matrix: [Double], _ rhs: [Double], size n: Int) -> [Double]? {
        var a = matrix, b = rhs
        for column in 0..<n {
            var pivot = column
            for row in (column + 1)..<n where abs(a[row * n + column]) > abs(a[pivot * n + column]) { pivot = row }
            guard abs(a[pivot * n + column]) > 1e-12 else { return nil }
            if pivot != column {
                for k in 0..<n { a.swapAt(column * n + k, pivot * n + k) }
                b.swapAt(column, pivot)
            }
            for row in (column + 1)..<n {
                let factor = a[row * n + column] / a[column * n + column]
                if factor == 0 { continue }
                for k in column..<n { a[row * n + k] -= factor * a[column * n + k] }
                b[row] -= factor * b[column]
            }
        }
        var x = [Double](repeating: 0, count: n)
        for row in stride(from: n - 1, through: 0, by: -1) {
            var sum = b[row]
            for k in (row + 1)..<n { sum -= a[row * n + k] * x[k] }
            x[row] = sum / a[row * n + row]
        }
        return x
    }
}

/// Buckets points on a grid for fast nearest-neighbour queries.
private struct PointIndex {
    let points: [SIMD2<Double>]
    let cellSize: Double
    private var buckets: [Int: [Int]] = [:]

    init(points: [SIMD2<Double>], cellSize: Double) {
        self.points = points
        self.cellSize = cellSize
        for (index, p) in points.enumerated() {
            buckets[key(Int((p.x / cellSize).rounded(.down)), Int((p.y / cellSize).rounded(.down))), default: []].append(index)
        }
    }

    private func key(_ x: Int, _ y: Int) -> Int { x &* 100_003 &+ y }

    func nearest(to p: SIMD2<Double>, within tolerance: Double) -> Int? {
        guard p.x.isFinite, p.y.isFinite, abs(p.x) < 1e6, abs(p.y) < 1e6 else { return nil }
        let cx = Int((p.x / cellSize).rounded(.down)), cy = Int((p.y / cellSize).rounded(.down))
        let reach = Int((tolerance / cellSize).rounded(.up))
        var best: Int?
        var bestDistance = tolerance * tolerance
        for x in (cx - reach)...(cx + reach) {
            for y in (cy - reach)...(cy + reach) {
                for index in buckets[key(x, y)] ?? [] {
                    let d = points[index] - p
                    let distance = d.x * d.x + d.y * d.y
                    if distance < bestDistance {
                        bestDistance = distance
                        best = index
                    }
                }
            }
        }
        return best
    }
}

private func simd_distance_d(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double {
    let d = a - b
    return (d.x * d.x + d.y * d.y).squareRoot()
}

private func simd_length_d(_ a: SIMD2<Double>) -> Double {
    (a.x * a.x + a.y * a.y).squareRoot()
}

private func simd_dot_d(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double {
    a.x * b.x + a.y * b.y
}
