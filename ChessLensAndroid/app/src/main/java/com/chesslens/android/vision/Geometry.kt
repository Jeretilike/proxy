package com.chesslens.android.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point in unit coordinates (0..1, origin top-left) or pixels, depending on context. */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    fun distance(o: Pt) = hypot(x - o.x, y - o.y)
}

/** A grayscale camera frame (the Y plane of the glasses' YUV frames). */
class Frame(val width: Int, val height: Int, val luma: ByteArray) {
    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

    /** Bilinear sample at pixel coordinates, clamped to the frame. */
    fun sample(x: Double, y: Double): Double {
        val cx = min(max(x, 0.0), width - 1.001)
        val cy = min(max(y, 0.0), height - 1.001)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val fx = cx - x0
        val fy = cy - y0
        return at(x0, y0) * (1 - fx) * (1 - fy) + at(x0 + 1, y0) * fx * (1 - fy) +
            at(x0, y0 + 1) * (1 - fx) * fy + at(x0 + 1, y0 + 1) * fx * fy
    }
}

/**
 * The four corners of the 64-square playing area in unit coordinates of the frame, so they
 * survive the glasses switching stream resolution.
 */
data class BoardCorners(val a1: Pt, val h1: Pt, val h8: Pt, val a8: Pt) {
    val points: List<Pt> get() = listOf(a1, h1, h8, a8)

    fun map(transform: (Pt) -> Pt) = of(points.map(transform))
    fun translated(dx: Double, dy: Double) = map { Pt(it.x + dx, it.y + dy) }
    fun rotated(steps: Int): BoardCorners = of((0 until 4).map { points[(it + steps) % 4] })

    /** Shrinks by [t] (fraction of the board) on every side in board space; negative grows. */
    fun inset(t: Double) = BoardCorners(point(t, t), point(1 - t, t), point(1 - t, 1 - t), point(t, 1 - t))

    fun distance(other: BoardCorners): Double = points.zip(other.points).maxOf { (a, b) -> a.distance(b) }

    /** Where board point (u: a→h, v: rank 1→8, both 0..1) lands in the frame (projective map). */
    fun point(u: Double, v: Double): Pt {
        val (x0, y0) = a1; val (x1, y1) = h1; val (x2, y2) = h8; val (x3, y3) = a8
        val dx3 = x0 - x1 + x2 - x3
        val dy3 = y0 - y1 + y2 - y3
        if (abs(dx3) < 1e-12 && abs(dy3) < 1e-12) {
            return Pt(x0 + (x1 - x0) * u + (x3 - x0) * v, y0 + (y1 - y0) * u + (y3 - y0) * v)
        }
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
        val det = dx1 * dy2 - dx2 * dy1
        if (abs(det) < 1e-15) return a1
        val g = (dx3 * dy2 - dx2 * dy3) / det
        val h = (dx1 * dy3 - dx3 * dy1) / det
        val a = x1 - x0 + g * x1; val b = x3 - x0 + h * x3
        val d = y1 - y0 + g * y1; val e = y3 - y0 + h * y3
        val w = g * u + h * v + 1
        return Pt((a * u + b * v + x0) / w, (d * u + e * v + y0) / w)
    }

    val isPlausible: Boolean
        get() {
            if (!points.all { it.x in -0.25..1.25 && it.y in -0.25..1.25 }) return false
            var area = 0.0
            for (i in 0 until 4) {
                val p = points[i]; val q = points[(i + 1) % 4]
                area += p.x * q.y - q.x * p.y
            }
            return abs(area) / 2 > 0.01
        }

    companion object {
        fun of(p: List<Pt>) = BoardCorners(p[0], p[1], p[2], p[3])

        /** Orders a quadrilateral like a1, h1, h8, a8 seen from a player's seat, never mirrored. */
        fun counterClockwise(quad: List<Pt>): List<Pt> {
            var area = 0.0
            for (i in 0 until 4) {
                val p = quad[i]; val q = quad[(i + 1) % 4]
                area += p.x * q.y - q.x * p.y
            }
            return if (area > 0) listOf(quad[0], quad[3], quad[2], quad[1]) else quad
        }
    }
}

/** A 2D projective transform fitted by least squares with Hartley normalisation. */
class Homography(val m: DoubleArray) {
    fun apply(p: Pt): Pt {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Pt((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    fun times(o: Homography): Homography {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = (0 until 3).sumOf { m[i * 3 + it] * o.m[it * 3 + j] }
        }
        return Homography(r)
    }

    fun inverse(): Homography? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-15) return null
        val inv = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        /** Fits a transform mapping source[i] to target[i] (at least 4 pairs). */
        fun fit(source: List<Pt>, target: List<Pt>): Homography? {
            if (source.size < 4 || source.size != target.size) return null
            val sn = normalising(source)
            val tn = normalising(target)
            val ps = source.map { sn.apply(it) }
            val pt = target.map { tn.apply(it) }
            val ata = DoubleArray(64)
            val atb = DoubleArray(8)
            for (i in ps.indices) {
                val p = ps[i]; val q = pt[i]
                val rows = arrayOf(
                    doubleArrayOf(p.x, p.y, 1.0, 0.0, 0.0, 0.0, -q.x * p.x, -q.x * p.y) to q.x,
                    doubleArrayOf(0.0, 0.0, 0.0, p.x, p.y, 1.0, -q.y * p.x, -q.y * p.y) to q.y,
                )
                for ((row, rhs) in rows) for (r in 0 until 8) {
                    atb[r] += row[r] * rhs
                    for (c in 0 until 8) ata[r * 8 + c] += row[r] * row[c]
                }
            }
            val h = solve(ata, atb, 8) ?: return null
            val normalized = Homography(DoubleArray(9) { if (it < 8) h[it] else 1.0 })
            val tInv = tn.inverse() ?: return null
            val full = tInv.times(normalized).times(sn).m
            val last = full[8]
            if (!full.all { it.isFinite() } || abs(last) < 1e-12) return null
            return Homography(DoubleArray(9) { full[it] / last })
        }

        private fun normalising(points: List<Pt>): Homography {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            val mean = points.sumOf { hypot(it.x - cx, it.y - cy) } / points.size
            val s = sqrt(2.0) / max(mean, 1e-9)
            return Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
        }

        private fun solve(matrix: DoubleArray, rhs: DoubleArray, n: Int): DoubleArray? {
            val a = matrix.copyOf()
            val b = rhs.copyOf()
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) if (abs(a[row * n + col]) > abs(a[pivot * n + col])) pivot = row
                if (abs(a[pivot * n + col]) < 1e-12) return null
                if (pivot != col) {
                    for (k in 0 until n) { val t = a[col * n + k]; a[col * n + k] = a[pivot * n + k]; a[pivot * n + k] = t }
                    val t = b[col]; b[col] = b[pivot]; b[pivot] = t
                }
                for (row in col + 1 until n) {
                    val f = a[row * n + col] / a[col * n + col]
                    if (f == 0.0) continue
                    for (k in col until n) a[row * n + k] -= f * a[col * n + k]
                    b[row] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = b[row]
                for (k in row + 1 until n) sum -= a[row * n + k] * x[k]
                x[row] = sum / a[row * n + row]
            }
            return x
        }
    }
}

/** A downscaled float grayscale image for corner detection. */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    fun sample(x: Float, y: Float): Float {
        val cx = min(max(x, 0f), width - 1.001f)
        val cy = min(max(y, 0f), height - 1.001f)
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val fx = cx - x0; val fy = cy - y0
        val i = y0 * width + x0
        return pixels[i] * (1 - fx) * (1 - fy) + pixels[i + 1] * fx * (1 - fy) +
            pixels[i + width] * (1 - fx) * fy + pixels[i + width + 1] * fx * fy
    }

    fun blurred(sigma: Float): GrayImage {
        val radius = kotlin.math.ceil(3 * sigma).toInt()
        val kernel = FloatArray(2 * radius + 1) { kotlin.math.exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val h = FloatArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var s = 0f
                for (k in -radius..radius) s += kernel[k + radius] * pixels[row + min(max(x + k, 0), width - 1)]
                h[row + x] = s
            }
        }
        val v = FloatArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var s = 0f
            for (k in -radius..radius) s += kernel[k + radius] * h[min(max(y + k, 0), height - 1) * width + x]
            v[y * width + x] = s
        }
        return GrayImage(width, height, v)
    }

    companion object {
        /** Scales a frame so its long side is at most [maxSide] pixels. */
        fun from(frame: Frame, maxSide: Int): GrayImage {
            val scale = min(1.0, maxSide.toDouble() / max(frame.width, frame.height))
            val w = max(8, (frame.width * scale).roundToInt())
            val h = max(8, (frame.height * scale).roundToInt())
            val sx = frame.width.toDouble() / w
            val sy = frame.height.toDouble() / h
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                out[y * w + x] = frame.sample((x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5).toFloat()
            }
            return GrayImage(w, h, out)
        }
    }
}

fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val s = sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
}

fun List<Float>.meanOrZero(): Float = if (isEmpty()) 0f else sum() / size

fun List<Float>.variance(): Float {
    if (size < 2) return 0f
    val m = meanOrZero()
    return sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / size
}
