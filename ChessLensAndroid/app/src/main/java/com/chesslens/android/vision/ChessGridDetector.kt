package com.chesslens.android.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds a chessboard by its grid: the 7×7 inner corners where four squares meet ("X-corners")
 * look the same on wooden boards and on chess.com boards on a screen, and don't need the board's
 * edge to be visible. Same steps as the iOS app (whose Python prototype found the grid on most of
 * 30 real board photos and 199 of 200 synthetic boards):
 * 1. saddle points of the blurred image, kept only if brightness around them alternates
 *    light/dark twice;
 * 2. a lattice grown from neighbouring X-corners with a homography fit;
 * 3. every 8×8 window of the lattice becomes a candidate outline (the caller picks one).
 */
object ChessGridDetector {
    const val MAX_SIDE = 640

    private data class Lattice(val homography: Homography, val matches: Map<Pair<Int, Int>, Int>)

    /** Candidate outlines in unit coordinates, lattice order (−1,−1), (7,−1), (7,7), (−1,7). */
    fun boardOutlines(frame: Frame): List<List<Pt>> {
        val image = GrayImage.from(frame, MAX_SIDE)
        val blurred = image.blurred(1.2f)
        val (points, weights) = xCorners(blurred, 250)
        if (points.size < 9) return emptyList()
        var lattice = findLattice(points) ?: return emptyList()
        if (lattice.matches.size < 10) return emptyList()
        lattice = refine(lattice, points)

        val keys = lattice.matches.keys
        val minI = keys.minOf { it.first }; val maxI = keys.maxOf { it.first }
        val minJ = keys.minOf { it.second }; val maxJ = keys.maxOf { it.second }
        data class Window(val weight: Float, val ox: Int, val oy: Int)
        val windows = mutableListOf<Window>()
        for (ox in min(minI, maxI - 6)..max(minI, maxI - 6)) {
            for (oy in min(minJ, maxJ - 6)..max(minJ, maxJ - 6)) {
                var weight = 0f
                for ((c, idx) in lattice.matches) {
                    if (c.first in ox..ox + 6 && c.second in oy..oy + 6) weight += weights[idx]
                }
                windows += Window(weight, ox, oy)
            }
        }
        val top = windows.maxOf { it.weight }
        return windows.filter { it.weight >= 0.6f * top }.sortedByDescending { it.weight }.map { w ->
            listOf(w.ox - 1 to w.oy - 1, w.ox + 7 to w.oy - 1, w.ox + 7 to w.oy + 7, w.ox - 1 to w.oy + 7).map { (i, j) ->
                val p = lattice.homography.apply(Pt(i.toDouble(), j.toDouble()))
                Pt(p.x / image.width, p.y / image.height)
            }
        }
    }

    // X-corners

    private const val RING = 16
    private val RADII = floatArrayOf(3f, 5f, 8f, 12f)
    private val RING_COS = FloatArray(RING) { cos(2 * PI * it / RING).toFloat() }
    private val RING_SIN = FloatArray(RING) { sin(2 * PI * it / RING).toFloat() }
    private val H_COS = Array(4) { k -> FloatArray(RING) { cos(2 * PI * (k + 1) * it / RING).toFloat() } }
    private val H_SIN = Array(4) { k -> FloatArray(RING) { sin(2 * PI * (k + 1) * it / RING).toFloat() } }

    private fun xCorners(image: GrayImage, keep: Int): Pair<List<Pt>, List<Float>> {
        val scored = saddleCandidates(image, 1500)
            .map { it to xness(image, it.x.toFloat(), it.y.toFloat()) }
            .filter { it.second > 6f }
            .sortedByDescending { it.second }
            .take(keep)
        return scored.map { it.first } to scored.map { it.second }
    }

    private fun saddleCandidates(image: GrayImage, limit: Int): List<Pt> {
        val w = image.width; val h = image.height; val g = image.pixels
        val response = FloatArray(w * h)
        var peak = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gxx = g[i + 1] - 2 * g[i] + g[i - 1]
            val gyy = g[i + w] - 2 * g[i] + g[i - w]
            val gxy = (g[i + w + 1] - g[i + w - 1] - g[i - w + 1] + g[i - w - 1]) / 4
            val s = gxy * gxy - gxx * gyy
            if (s > 0) { response[i] = s; if (s > peak) peak = s }
        }
        if (peak <= 0) return emptyList()
        val threshold = 0.002f * peak
        val r = 3
        val maxima = mutableListOf<Triple<Int, Int, Float>>()
        for (y in r until h - r) for (x in r until w - r) {
            val s = response[y * w + x]
            if (s <= threshold) continue
            var isMax = true
            loop@ for (dy in -r..r) for (dx in -r..r) {
                if (response[(y + dy) * w + x + dx] > s) { isMax = false; break@loop }
            }
            if (isMax) maxima += Triple(x, y, s)
        }
        return maxima.sortedByDescending { it.third }.take(limit).map { Pt(it.first.toDouble(), it.second.toDouble()) }
    }

    private fun xness(image: GrayImage, x: Float, y: Float): Float {
        var best = -Float.MAX_VALUE
        val ring = FloatArray(RING)
        for (r in RADII) {
            for (n in 0 until RING) ring[n] = image.sample(x + r * RING_COS[n], y + r * RING_SIN[n])
            val mag = FloatArray(4)
            for (k in 0 until 4) {
                var re = 0f; var im = 0f
                for (n in 0 until RING) { re += ring[n] * H_COS[k][n]; im -= ring[n] * H_SIN[k][n] }
                mag[k] = sqrt(re * re + im * im) / RING
            }
            best = max(best, mag[1] - mag[0] - mag[2] - 0.5f * mag[3])
        }
        return best
    }

    // Lattice

    private class PointIndex(val points: List<Pt>, val cell: Double) {
        private val buckets = HashMap<Long, MutableList<Int>>()
        init {
            points.forEachIndexed { i, p -> buckets.getOrPut(key(cellOf(p.x), cellOf(p.y))) { mutableListOf() }.add(i) }
        }
        private fun cellOf(v: Double) = kotlin.math.floor(v / cell).toInt()
        private fun key(x: Int, y: Int) = x.toLong() * 100_003L + y

        fun nearest(p: Pt, tolerance: Double): Int? {
            if (!p.x.isFinite() || !p.y.isFinite() || abs(p.x) > 1e6 || abs(p.y) > 1e6) return null
            val cx = cellOf(p.x); val cy = cellOf(p.y)
            val reach = kotlin.math.ceil(tolerance / cell).toInt()
            var best: Int? = null
            var bestD = tolerance * tolerance
            for (x in cx - reach..cx + reach) for (y in cy - reach..cy + reach) {
                for (i in buckets[key(x, y)] ?: continue) {
                    val dx = points[i].x - p.x; val dy = points[i].y - p.y
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = i }
                }
            }
            return best
        }
    }

    private fun findLattice(points: List<Pt>): Lattice? {
        val nn = points.take(80).map { p -> points.map { it.distance(p) }.sorted().getOrElse(1) { 0.0 } }.sorted()
        val index = PointIndex(points, max(4.0, nn[nn.size / 2]))
        var best: Lattice? = null
        var hypotheses = 0
        val unit = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(0.0, 1.0), Pt(1.0, 1.0))
        for (seed in 0 until min(60, points.size)) {
            val p = points[seed]
            val near = points.indices.filter { it != seed }.sortedBy { points[it].distance(p) }.take(6)
            for (a in near.indices) for (b in a + 1 until near.size) {
                val u = points[near[a]] - p; val v = points[near[b]] - p
                val lu = hypot(u.x, u.y); val lv = hypot(v.x, v.y)
                if (lu < 3 || lv < 3 || lu / lv !in 0.5..2.0) continue
                if (abs(u.x * v.x + u.y * v.y) / (lu * lv) > 0.6) continue
                val s = index.nearest(p + u + v, 0.25 * min(lu, lv)) ?: continue
                hypotheses++
                val seedH = Homography.fit(unit, listOf(p, points[near[a]], points[near[b]], points[s])) ?: continue
                val grown = grow(seedH, points, index) ?: continue
                if (grown.matches.size > (best?.matches?.size ?: 0)) best = grown
                if ((best?.matches?.size ?: 0) >= 45 || hypotheses >= 600) return best
            }
        }
        return best
    }

    private fun grow(start: Homography, points: List<Pt>, index: PointIndex): Lattice? {
        var h = start
        var matches = HashMap<Pair<Int, Int>, Int>()
        repeat(4) {
            val owner = HashMap<Int, Pair<Double, Pair<Int, Int>>>()
            matches = HashMap()
            for (i in -9..9) for (j in -9..9) {
                val pred = h.apply(Pt(i.toDouble(), j.toDouble()))
                val right = h.apply(Pt(i + 1.0, j.toDouble()))
                val up = h.apply(Pt(i.toDouble(), j + 1.0))
                val scale = min(right.distance(pred), up.distance(pred))
                if (!pred.x.isFinite() || !pred.y.isFinite() || !scale.isFinite() || scale < 3) continue
                val k = index.nearest(pred, 0.22 * scale) ?: continue
                val d = points[k].distance(pred)
                val coordinate = i to j
                val previous = owner[k]
                if (previous != null) {
                    if (previous.first <= d) continue
                    matches.remove(previous.second)
                }
                owner[k] = d to coordinate
                matches[coordinate] = k
            }
            if (matches.size < 4) return null
            val entries = matches.entries.toList()
            h = Homography.fit(entries.map { Pt(it.key.first.toDouble(), it.key.second.toDouble()) }, entries.map { points[it.value] })
                ?: return null
        }
        return Lattice(h, matches)
    }

    private fun refine(lattice: Lattice, points: List<Pt>): Lattice {
        var current = lattice
        repeat(2) {
            val entries = current.matches.entries.toList()
            val residuals = entries.map { (c, idx) ->
                val pred = current.homography.apply(Pt(c.first.toDouble(), c.second.toDouble()))
                val right = current.homography.apply(Pt(c.first + 1.0, c.second.toDouble()))
                pred.distance(points[idx]) / max(right.distance(pred), 1.0)
            }
            val limit = max(0.08, 2.5 * residuals.sorted()[residuals.size / 2])
            val kept = entries.zip(residuals).filter { it.second < limit }.associate { it.first.key to it.first.value }
            if (kept.size < 8) return current
            val ks = kept.entries.toList()
            val refit = Homography.fit(ks.map { Pt(it.key.first.toDouble(), it.key.second.toDouble()) }, ks.map { points[it.value] })
                ?: return current
            current = Lattice(refit, kept)
        }
        return current
    }
}
