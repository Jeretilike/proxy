package com.chesslens.android.vision

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.Piece
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.PieceKind
import com.chesslens.android.chess.Square
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A screenshot as packed 0xRRGGBB pixels. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray)

/** Board position on a screenshot: top-left corner and cell size, in image pixels. */
data class ScreenGrid(val x: Double, val y: Double, val cw: Double, val ch: Double, val score: Double = 0.0)

/** One square as seen on screen: background colour, piece shape (32×32) and how much it covers. */
class ScreenSquare(val bg: Int, val mask: BooleanArray, val lum: FloatArray, val coverage: Double, val meanLum: Double)

class PieceTemplate(var n: Int, val pm: FloatArray, val lum: FloatArray)

class ScreenReading(val board: ChessBoard, val whiteAtBottom: Boolean, val lastMover: PieceColor?, val uncertain: Int = 0)

/**
 * Reads a chess board from a screenshot of a chess app or site: finds the 8×8 grid of flat,
 * alternating colours, then identifies pieces by comparing them with ones learned from a starting
 * position of the same piece set. A port of the PC app's screen-vision.js (same rules and numbers).
 */
object ScreenVision {
    private const val SIDE = 32
    private const val MASK_THRESHOLD = 60
    const val OCCUPIED = 0.035
    private const val MAX_DISTANCE = 0.16

    // -------------------------------------------------------------------------------------------
    // Pixels

    private fun colorAt(img: RgbImage, x: Double, y: Double): Int {
        val xi = x.roundToInt().coerceIn(0, img.width - 1)
        val yi = y.roundToInt().coerceIn(0, img.height - 1)
        return img.pixels[yi * img.width + xi]
    }

    private fun bilinear(img: RgbImage, x: Double, y: Double): Int {
        val x0 = floor(x).toInt().coerceIn(0, img.width - 2)
        val y0 = floor(y).toInt().coerceIn(0, img.height - 2)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        val w = img.width
        val a = img.pixels[y0 * w + x0]
        val b = img.pixels[y0 * w + x0 + 1]
        val e = img.pixels[(y0 + 1) * w + x0]
        val f = img.pixels[(y0 + 1) * w + x0 + 1]
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val v = (((a shr shift) and 255) * (1 - fx) + ((b shr shift) and 255) * fx) * (1 - fy) +
                (((e shr shift) and 255) * (1 - fx) + ((f shr shift) and 255) * fx) * fy
            out = out or (v.roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    fun colorDistance(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    private fun luma(c: Int): Float = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)

    private fun medianColor(colors: List<Int>): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val values = colors.map { (it shr shift) and 255 }.sorted()
            out = out or (values[values.size / 2] shl shift)
        }
        return out
    }

    // -------------------------------------------------------------------------------------------
    // Finding the board

    private class Chain(val start: Int, val cell: Double, val cells: Int)

    private fun chains(img: RgbImage, horizontal: Boolean): List<Chain> {
        val lines = if (horizontal) img.height else img.width
        val length = if (horizontal) img.width else img.height
        fun at(i: Int, line: Int): Int = if (horizontal) img.pixels[line * img.width + i.coerceIn(0, img.width - 1)]
        else img.pixels[i.coerceIn(0, img.height - 1) * img.width + line]
        val found = ArrayList<Chain>()
        val minCell = 12
        val maxCell = length / 7.5
        var line = 1
        while (line < lines - 1) {
            val bounds = ArrayList<Int>()
            var last = -10
            var prev = at(0, line)
            for (i in 1 until length) {
                val c = at(i, line)
                if (colorDistance(c, prev) > 40) {
                    if (i - last > 2) bounds += i
                    last = i
                }
                prev = c
            }
            if (bounds.size >= 6) {
                var s = 0
                while (s < bounds.size - 5) {
                    var t = s + 1
                    var matched = false
                    while (t < min(bounds.size, s + 8)) {
                        val g = bounds[t] - bounds[s]
                        if (g < minCell) { t++; continue }
                        if (g > maxCell) break
                        val tol = max(2.0, g * 0.04)
                        val off = max(2, (g * 0.06).roundToInt())
                        var cells = 0
                        var lastEdge = bounds[s]
                        var edge = bounds[s]
                        var next = bounds[t]
                        var k = t
                        var previousInside = -1
                        while (true) {
                            val inside = at(edge + off, line)
                            val insideEnd = at(next - off, line)
                            if (colorDistance(inside, insideEnd) > 30) break
                            if (previousInside >= 0 && colorDistance(inside, previousInside) < 25) break
                            cells++
                            lastEdge = next
                            previousInside = inside
                            edge = next
                            var nextIndex = -1
                            for (j in k + 1 until bounds.size) {
                                val gap = bounds[j] - (edge + g)
                                if (abs(gap) <= tol) { nextIndex = j; break }
                                if (gap > tol) break
                            }
                            if (nextIndex < 0) break
                            k = nextIndex
                            next = bounds[nextIndex]
                        }
                        if (cells >= 5) {
                            found += Chain(bounds[s], (lastEdge - bounds[s]).toDouble() / cells, cells)
                            matched = true
                            break
                        }
                        t++
                    }
                    s++
                    if (matched) continue
                }
            }
            line += 2
        }
        return found
    }

    private class Candidate(val origin: Double, val cell: Double, val n: Int)

    private fun edgeCandidates(list: List<Chain>): List<Candidate> {
        class Vote(var origin: Double = 0.0, var cell: Double = 0.0, var n: Int = 0)
        val votes = HashMap<String, Vote>()
        for (c in list) {
            for (k in 0..(8 - c.cells)) {
                val origin = c.start - k * c.cell
                val key = "${(origin / 2).roundToInt()}:${c.cell.roundToInt()}"
                val v = votes.getOrPut(key) { Vote() }
                v.origin += origin; v.cell += c.cell; v.n++
            }
        }
        return votes.values.map { Candidate(it.origin / it.n, it.cell / it.n, it.n) }.sortedByDescending { it.n }.take(12)
    }

    private fun cellBackground(img: RgbImage, g: ScreenGrid, row: Int, col: Int): Int {
        val x0 = g.x + col * g.cw
        val y0 = g.y + row * g.ch
        val samples = ArrayList<Int>(16)
        for (fy in doubleArrayOf(0.07, 0.13, 0.87, 0.93)) for (fx in doubleArrayOf(0.07, 0.13, 0.87, 0.93)) {
            samples += colorAt(img, x0 + fx * g.cw, y0 + fy * g.ch)
        }
        return medianColor(samples)
    }

    private fun isLightCell(i: Int) = ((i shr 3) + (i and 7)) % 2 == 0

    fun checkerScore(img: RgbImage, g: ScreenGrid): Double {
        if (g.x < -2 || g.y < -2 || g.x + 8 * g.cw > img.width + 2 || g.y + 8 * g.ch > img.height + 2) return 0.0
        val bgs = (0 until 64).map { cellBackground(img, g, it shr 3, it and 7) }
        val light = bgs.filterIndexed { i, _ -> isLightCell(i) }
        val dark = bgs.filterIndexed { i, _ -> !isLightCell(i) }
        val lightMean = medianColor(light)
        val darkMean = medianColor(dark)
        val separation = colorDistance(lightMean, darkMean)
        if (separation < 30) return 0.0
        val limit = min(40, separation / 2)
        val consistent = bgs.indices.count { colorDistance(bgs[it], if (isLightCell(it)) lightMean else darkMean) < limit }
        val fraction = consistent / 64.0
        if (fraction < 0.7) return 0.0
        var outsideMatches = 0
        var outside = 0
        for (i in 0..7) {
            for ((row, col) in listOf(-1 to i, 8 to i, i to -1, i to 8)) {
                outside++
                val cx = g.x + (col + 0.5) * g.cw
                val cy = g.y + (row + 0.5) * g.ch
                if (cx < 0 || cy < 0 || cx >= img.width || cy >= img.height) continue
                val c = cellBackground(img, g, row, col)
                val expected = if ((((row + 8) % 2) + ((col + 8) % 2)) % 2 == 0) lightMean else darkMean
                if (colorDistance(c, expected) < limit) outsideMatches++
            }
        }
        return fraction * min(separation, 150) * (1 - outsideMatches.toDouble() / outside)
    }

    private fun refineAxis(img: RgbImage, g: ScreenGrid, horizontal: Boolean): ScreenGrid {
        val origin = if (horizontal) g.x else g.y
        val cell = if (horizontal) g.cw else g.ch
        var bestOrigin = origin
        var bestCell = cell
        var bestEnergy = -1.0
        var dc = -1.0
        while (dc <= 1.001) {
            var d0 = -3.0
            while (d0 <= 3.0) {
                val o = origin + d0
                val c = cell + dc
                var energy = 0.0
                for (k in 1 until 8) {
                    val p = o + k * c
                    for (s in 0 until 32) {
                        val q = (if (horizontal) g.y else g.x) + (s + 0.5) / 32 * 8 * (if (horizontal) g.ch else g.cw)
                        val a = if (horizontal) bilinear(img, p - 1, q) else bilinear(img, q, p - 1)
                        val b = if (horizontal) bilinear(img, p, q) else bilinear(img, q, p)
                        energy += colorDistance(a, b)
                    }
                }
                if (energy > bestEnergy) { bestEnergy = energy; bestOrigin = o; bestCell = c }
                d0 += 0.5
            }
            dc += 0.25
        }
        return if (horizontal) g.copy(x = bestOrigin, cw = bestCell) else g.copy(y = bestOrigin, ch = bestCell)
    }

    /** Fine-tunes a board marked by hand onto the colour edges between its squares. */
    fun snap(img: RgbImage, g: ScreenGrid): ScreenGrid {
        val refined = refineAxis(img, refineAxis(img, g, true), false)
        // Only accept small corrections; otherwise keep what was marked.
        val ok = kotlin.math.abs(refined.x - g.x) <= g.cw * 0.15 && kotlin.math.abs(refined.y - g.y) <= g.ch * 0.15 &&
            kotlin.math.abs(refined.cw - g.cw) <= g.cw * 0.05
        return if (ok) refined else g
    }

    /** Finds the board, checking [previous] first (cheap) before searching the whole image. */
    fun findBoard(img: RgbImage, previous: ScreenGrid? = null): ScreenGrid? {
        if (previous != null) {
            val s = checkerScore(img, previous)
            if (s > 25) return previous.copy(score = s)
        }
        val xs = edgeCandidates(chains(img, true))
        val ys = edgeCandidates(chains(img, false))
        var best: ScreenGrid? = null
        for (cx in xs) for (cy in ys) {
            if (abs(cx.cell - cy.cell) > max(3.0, cx.cell * 0.06)) continue
            val grid = ScreenGrid(cx.origin, cy.origin, cx.cell, cy.cell)
            val score = checkerScore(img, grid)
            if (score > 25 && (best == null || score > best.score)) best = grid.copy(score = score)
        }
        val found = best ?: return null
        val refined = refineAxis(img, refineAxis(img, found, true), false)
        val score = checkerScore(img, refined)
        return if (score >= found.score * 0.9) refined.copy(score = score) else found
    }

    // -------------------------------------------------------------------------------------------
    // Squares and pieces

    fun readSquares(img: RgbImage, g: ScreenGrid): List<ScreenSquare> = (0 until 64).map { i ->
        val row = i shr 3
        val col = i and 7
        val bg = cellBackground(img, g, row, col)
        val mask = BooleanArray(SIDE * SIDE)
        val lum = FloatArray(SIDE * SIDE)
        var count = 0
        var lumSum = 0.0
        val x0 = g.x + col * g.cw
        val y0 = g.y + row * g.ch
        for (v in 0 until SIDE) for (u in 0 until SIDE) {
            val c = bilinear(img, x0 + (0.04 + 0.92 * (u + 0.5) / SIDE) * g.cw, y0 + (0.04 + 0.92 * (v + 0.5) / SIDE) * g.ch)
            val k = v * SIDE + u
            if (colorDistance(c, bg) > MASK_THRESHOLD) {
                mask[k] = true
                lum[k] = luma(c)
                count++
                lumSum += lum[k]
            }
        }
        ScreenSquare(bg, mask, lum, count.toDouble() / (SIDE * SIDE), if (count > 0) lumSum / count else 0.0)
    }

    private fun highlighted(squares: List<ScreenSquare>): List<Int> {
        val means = listOf(
            medianColor(squares.filterIndexed { i, _ -> isLightCell(i) }.map { it.bg }),
            medianColor(squares.filterIndexed { i, _ -> !isLightCell(i) }.map { it.bg }),
        )
        return squares.indices.filter { colorDistance(squares[it].bg, means[if (isLightCell(it)) 0 else 1]) > 45 }
    }

    /**
     * Who just moved, from the last-move highlight: only trusted when it looks like one: the empty
     * square a piece left plus the square it's on now (or a castling king and rook). A tapped,
     * selected piece adds a highlighted square of its own, so it's never mistaken for a move.
     */
    private fun lastMover(highlights: List<Int>, screen: Array<Piece?>): PieceColor? {
        val occupied = highlights.mapNotNull { screen[it] }
        val empty = highlights.size - occupied.size
        if (highlights.size == 2 && occupied.size == 1 && empty == 1) return occupied[0].color
        // Castling: sites highlight the king's squares, sometimes the rook's too.
        if (highlights.size in 3..4 && empty >= 1 && occupied.size == 2 &&
            occupied.map { it.kind }.toSet() == setOf(PieceKind.KING, PieceKind.ROOK) && occupied[0].color == occupied[1].color
        ) return occupied[0].color
        return null
    }

    private fun distance(square: ScreenSquare, t: PieceTemplate): Double {
        var total = 0.0
        for (i in 0 until SIDE * SIDE) {
            val p = t.pm[i]
            if (square.mask[i]) {
                total += 1 - p
                if (p > 0.3f) total += abs(square.lum[i] - t.lum[i]) / 255.0 * 0.8
            } else {
                total += p
            }
        }
        return total / (SIDE * SIDE)
    }

    /** Which board square (a1 = 0) a screen cell (top-left first) shows. */
    fun boardIndex(screenIndex: Int, whiteAtBottom: Boolean): Int {
        val row = screenIndex shr 3
        val col = screenIndex and 7
        return if (whiteAtBottom) (7 - row) * 8 + col else row * 8 + (7 - col)
    }

    private val BACK_RANK = "rnbqkbnr"

    /** If the screen shows the starting position, learns every piece from it. */
    fun learnFromStart(squares: List<ScreenSquare>): Pair<Map<String, PieceTemplate>, Boolean>? {
        for (i in 0 until 64) {
            val row = i shr 3
            val shouldBeOccupied = row <= 1 || row >= 6
            if ((squares[i].coverage > OCCUPIED) != shouldBeOccupied) return null
        }
        fun average(rows: Set<Int>, f: (ScreenSquare) -> Double) =
            squares.filterIndexed { i, _ -> (i shr 3) in rows }.map(f).average()
        val bottom = average(setOf(6, 7)) { it.meanLum }
        val top = average(setOf(0, 1)) { it.meanLum }
        val whiteAtBottom = if (abs(bottom - top) >= 25) bottom > top else {
            val bottomCover = average(setOf(6, 7)) { it.coverage }
            val topCover = average(setOf(0, 1)) { it.coverage }
            if (abs(bottomCover - topCover) < 0.03) return null
            bottomCover < topCover
        }
        val placement = arrayOfNulls<Piece>(64)
        for (file in 0..7) {
            val kind = PieceKind.fromLetter(BACK_RANK[file])!!
            placement[file] = Piece(PieceColor.WHITE, kind)
            placement[8 + file] = Piece(PieceColor.WHITE, PieceKind.PAWN)
            placement[48 + file] = Piece(PieceColor.BLACK, PieceKind.PAWN)
            placement[56 + file] = Piece(PieceColor.BLACK, kind)
        }
        return learn(squares, placement, whiteAtBottom, emptyMap()) to whiteAtBottom
    }

    private fun keyOf(p: Piece) = "${if (p.color == PieceColor.WHITE) 'w' else 'b'}${p.kind.letter}"

    /** Learns the pieces on screen given what's really on the board (a1 = 0), merged into [existing]. */
    fun learn(squares: List<ScreenSquare>, placement: Array<Piece?>, whiteAtBottom: Boolean, existing: Map<String, PieceTemplate>): Map<String, PieceTemplate> {
        val templates = HashMap(existing)
        val fresh = HashSet<String>()
        for (i in 0 until 64) {
            val piece = placement[boardIndex(i, whiteAtBottom)] ?: continue
            if (squares[i].coverage <= OCCUPIED) continue
            val key = keyOf(piece)
            if (key !in fresh) {
                templates[key] = PieceTemplate(0, FloatArray(SIDE * SIDE), FloatArray(SIDE * SIDE))
                fresh += key
            }
            val t = templates.getValue(key)
            val sq = squares[i]
            val n = t.n
            for (k in 0 until SIDE * SIDE) {
                val oldPm = t.pm[k]
                val m = if (sq.mask[k]) 1f else 0f
                t.pm[k] = (oldPm * n + m) / (n + 1)
                if (sq.mask[k]) {
                    val weight = oldPm * n
                    t.lum[k] = (t.lum[k] * weight + sq.lum[k]) / (weight + 1)
                }
            }
            t.n = n + 1
        }
        return templates
    }

    /**
     * Improves the learned pieces from squares that were misread, now that the game follower
     * knows what is really on them ([board], a1 = 0). Only pieces are learned (not empty squares).
     */
    fun refine(squares: List<ScreenSquare>, board: ChessBoard, whiteAtBottom: Boolean, templates: Map<String, PieceTemplate>, screenSquares: List<Int>) {
        for (i in screenSquares) {
            val piece = board.at(boardIndex(i, whiteAtBottom)) ?: continue
            val sq = squares[i]
            if (sq.coverage <= OCCUPIED) continue
            val t = templates[keyOf(piece)] ?: continue
            // A running average that keeps adapting: older samples count at most 20 times.
            val n = minOf(t.n, 20)
            for (k in 0 until SIDE * SIDE) {
                val oldPm = t.pm[k]
                t.pm[k] = (oldPm * n + (if (sq.mask[k]) 1f else 0f)) / (n + 1)
                if (sq.mask[k]) {
                    val weight = oldPm * n
                    t.lum[k] = (t.lum[k] * weight + sq.lum[k]) / (weight + 1)
                }
            }
            t.n = n + 1
        }
    }

    /**
     * Identifies every square with its best guess, or null when several squares don't look like
     * anything known (a hand, an arrow, a piece being dragged). A square or two that only roughly
     * match (a piece set with shading looks a little different on light and dark squares) are
     * still guessed; the game follower sorts out such misreads using the legal moves.
     */
    fun recognize(squares: List<ScreenSquare>, templates: Map<String, PieceTemplate>, previousWhiteAtBottom: Boolean): ScreenReading? {
        if (templates.size < 12) return null
        val screen = arrayOfNulls<Piece>(64)
        var uncertain = 0
        for (i in 0 until 64) {
            val sq = squares[i]
            var bestKey: String? = null
            var bestDistance = sq.coverage
            if (sq.coverage > OCCUPIED * 0.6) {
                for ((key, t) in templates) {
                    val d = distance(sq, t)
                    if (d < bestDistance) { bestDistance = d; bestKey = key }
                }
            }
            if (bestDistance > MAX_DISTANCE) uncertain++
            if (bestDistance > MAX_DISTANCE * 2.5) return null
            screen[i] = bestKey?.let { Piece(if (it[0] == 'w') PieceColor.WHITE else PieceColor.BLACK, PieceKind.fromLetter(it[1])!!) }
        }
        var whiteAtBottom = previousWhiteAtBottom
        fun kingRow(color: PieceColor): Int {
            val i = screen.indexOfFirst { it?.color == color && it.kind == PieceKind.KING }
            return if (i < 0) -1 else i shr 3
        }
        val wk = kingRow(PieceColor.WHITE)
        val bk = kingRow(PieceColor.BLACK)
        if (wk >= 4 && bk in 0..3) whiteAtBottom = true
        else if (wk in 0..3 && bk >= 4) whiteAtBottom = false

        var board = ChessBoard.EMPTY
        for (i in 0 until 64) {
            val p = screen[i] ?: continue
            board = board.with(Square.fromIndex(boardIndex(i, whiteAtBottom)), p)
        }
        if (uncertain > 3) return null
        return ScreenReading(board, whiteAtBottom, lastMover(highlighted(squares), screen), uncertain)
    }

    // -------------------------------------------------------------------------------------------
    // Saving learned pieces

    fun serialize(templates: Map<String, PieceTemplate>): String = templates.entries.joinToString(";") { (key, t) ->
        val bytes = ByteArray(SIDE * SIDE * 2)
        for (k in 0 until SIDE * SIDE) {
            bytes[k] = (t.pm[k] * 255).roundToInt().coerceIn(0, 255).toByte()
            bytes[SIDE * SIDE + k] = t.lum[k].roundToInt().coerceIn(0, 255).toByte()
        }
        "$key:${t.n}:" + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    fun deserialize(text: String?): Map<String, PieceTemplate> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            text.split(';').associate { entry ->
                val (key, n, data) = entry.split(':', limit = 3)
                val bytes = java.util.Base64.getDecoder().decode(data)
                val pm = FloatArray(SIDE * SIDE) { (bytes[it].toInt() and 255) / 255f }
                val lum = FloatArray(SIDE * SIDE) { (bytes[SIDE * SIDE + it].toInt() and 255).toFloat() }
                key to PieceTemplate(n.toInt(), pm, lum)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
