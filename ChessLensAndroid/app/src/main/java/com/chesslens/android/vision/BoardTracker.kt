package com.chesslens.android.vision

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.Square
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the camera saw on each square compared with the last confirmed view. */
class SquareObservation(
    /** Per-square brightness change since the reference view (0–255). Index = Square.index. */
    val differences: FloatArray,
    /** Per-square probabilities of [empty, white piece, black piece]. */
    val contents: Array<FloatArray>,
)

sealed class TrackerResult {
    object NotCalibrated : TrackerResult()
    object Lost : TrackerResult()
    class Observed(
        val observation: SquareObservation,
        val corners: BoardCorners,
        /** Squares that clearly disagree with the last confirmed position. */
        val mismatches: Set<Int>,
        /** Per-square [brightness, texture], for checking other candidate positions. */
        val features: Array<FloatArray>,
    ) : TrackerResult()
}

/**
 * Keeps a reference view of the board and compares new frames with it square by square. Each
 * frame is aligned with the reference (the wearer's head moves), then the board is warped to a
 * top-down 256×256 image where every square is a 32×32 cell. Not thread-safe: call from one thread.
 */
class BoardTracker {
    companion object {
        const val SIDE = 256
        private const val CELL = 32
        private const val INSET = 6
        /** Typical per-square difference above which the board is considered lost (0–255). */
        private const val LOST_THRESHOLD = 30f

        private fun region(index: Int): Triple<Int, Int, Int> {
            val s = Square.fromIndex(index)
            return Triple(s.file * CELL + INSET, (7 - s.rank) * CELL + INSET, CELL - 2 * INSET)
        }

        private fun meanLuminance(p: FloatArray): Float {
            var t = 0f
            var i = 0
            while (i < p.size) { t += p[i]; i += 4 }
            return t / (p.size / 4)
        }

        /** How far (pixels) each square may slide when compared, to absorb small misalignment. */
        private const val SLACK = 2

        /**
         * Mean absolute brightness difference per square after evening out overall exposure. Each
         * square is compared at the best of small shifts, so a pixel or two of misalignment on
         * high-contrast edges doesn't look like a change, while a piece arriving or leaving does.
         */
        fun differences(a: FloatArray, b: FloatArray): FloatArray {
            val gain = min(max(meanLuminance(a) / max(meanLuminance(b), 1f), 0.6f), 1.6f)
            return FloatArray(64) { index ->
                val (x0, y0, n) = region(index)
                var best = Float.MAX_VALUE
                for (dy in -SLACK..SLACK) for (dx in -SLACK..SLACK) {
                    var total = 0f
                    for (y in y0 until y0 + n) {
                        val row = y * SIDE
                        val shifted = (y + dy) * SIDE + dx
                        for (x in x0 until x0 + n) total += abs(a[row + x] - gain * b[shifted + x])
                    }
                    if (total < best) best = total
                }
                best / (n * n)
            }
        }

        /** Per-square [brightness, texture] relative to the board's overall brightness. */
        fun features(p: FloatArray): Array<FloatArray> {
            val raw = Array(64) { FloatArray(2) }
            var brightness = 0f
            for (index in 0 until 64) {
                val (x0, y0, n) = region(index)
                var s = 0f; var s2 = 0f
                for (y in y0 until y0 + n) for (x in x0 until x0 + n) {
                    val v = p[y * SIDE + x]; s += v; s2 += v * v
                }
                val count = (n * n).toFloat()
                val mean = s / count
                raw[index][0] = mean
                raw[index][1] = sqrt(max(0f, s2 / count - mean * mean))
                brightness += mean
            }
            val scale = max(brightness / 64, 1f)
            return Array(64) { floatArrayOf(raw[it][0] / scale, raw[it][1] / scale) }
        }

        private fun separation(a: List<Float>, b: List<Float>): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val pooled = sqrt((a.variance() + b.variance()) / 2)
            return min(max((a.meanOrZero() - b.meanOrZero()) / max(pooled, 0.02f), -3f), 6f)
        }

        /**
         * How well a warped view matches a chessboard holding [board]: light squares brighter than
         * dark, occupied squares more textured than empty, White pieces brighter than Black.
         */
        fun boardMatch(pixels: FloatArray, board: ChessBoard): BoardMatch {
            val f = features(pixels)
            val emptyLight = mutableListOf<Float>(); val emptyDark = mutableListOf<Float>()
            val occTex = mutableListOf<Float>(); val emptyTex = mutableListOf<Float>()
            for (i in 0 until 64) {
                if (board.at(i) == null) {
                    if (Square.fromIndex(i).isLight) emptyLight += f[i][0] else emptyDark += f[i][0]
                    emptyTex += f[i][1]
                } else occTex += f[i][1]
            }
            val lightBase = emptyLight.meanOrZero(); val darkBase = emptyDark.meanOrZero()
            val white = mutableListOf<Float>(); val black = mutableListOf<Float>()
            for (i in 0 until 64) {
                val piece = board.at(i) ?: continue
                val residual = f[i][0] - if (Square.fromIndex(i).isLight) lightBase else darkBase
                if (piece.color == PieceColor.WHITE) white += residual else black += residual
            }
            return BoardMatch(separation(emptyLight, emptyDark), separation(occTex, emptyTex), separation(white, black))
        }

        /**
         * Squares that clearly disagree with [board] within one view: an "empty" square as textured
         * as the occupied ones, an "occupied" one as plain as the empty ones, or a piece as bright
         * as the other colour. Empty squares just beyond a piece (from the wearer's seat) are
         * skipped, since a tall piece's silhouette covers them.
         */
        fun mismatchedSquares(f: Array<FloatArray>, board: ChessBoard, player: PieceColor): Set<Int> {
            val away = if (player == PieceColor.WHITE) 1 else -1
            val shadowed = HashSet<Int>()
            for (i in 0 until 64) if (board.at(i) != null) {
                val s = Square.fromIndex(i)
                for (step in 1..2) s.offset(0, away * step)?.let { shadowed += it.index }
            }
            // A White piece on a light square can look almost like an empty square, so it is never
            // flagged as "looks empty" and doesn't count towards what occupied squares look like.
            fun lowContrast(i: Int) = board.at(i)?.color == PieceColor.WHITE && Square.fromIndex(i).isLight
            val emptyTex = mutableListOf<Float>(); val occTex = mutableListOf<Float>()
            val emptyLight = mutableListOf<Float>(); val emptyDark = mutableListOf<Float>()
            for (i in 0 until 64) {
                if (board.at(i) == null) {
                    if (i !in shadowed) emptyTex += f[i][1]
                    if (Square.fromIndex(i).isLight) emptyLight += f[i][0] else emptyDark += f[i][0]
                } else if (!lowContrast(i)) occTex += f[i][1]
            }
            if (emptyTex.size < 4 || occTex.size < 2) return emptySet()

            val flagged = HashSet<Int>()
            val plain = emptyTex.median(); val busy = occTex.median()
            val gap = busy - plain
            if (gap > 0.02f) {
                for (i in 0 until 64) {
                    val t = f[i][1]
                    if (board.at(i) == null) {
                        if (i !in shadowed && t > busy - 0.15f * gap) flagged += i
                    } else if (!lowContrast(i) && t < plain + 0.15f * gap) flagged += i
                }
            }
            val lightBase = emptyLight.median(); val darkBase = emptyDark.median()
            val white = mutableListOf<Pair<Int, Float>>(); val black = mutableListOf<Pair<Int, Float>>()
            for (i in 0 until 64) {
                val piece = board.at(i) ?: continue
                if (i in flagged) continue
                val r = f[i][0] - if (Square.fromIndex(i).isLight) lightBase else darkBase
                if (piece.color == PieceColor.WHITE) white += i to r else black += i to r
            }
            if (white.size >= 2 && black.size >= 2) {
                val wl = white.map { it.second }.median(); val bl = black.map { it.second }.median()
                val cg = wl - bl
                if (cg > 0.05f) {
                    white.filter { it.second < bl + 0.15f * cg }.forEach { flagged += it.first }
                    black.filter { it.second > wl - 0.15f * cg }.forEach { flagged += it.first }
                }
            }
            return flagged
        }

        /** The board as a top-down grayscale image: rank 8 along the top, file a on the left. */
        fun warp(frame: Frame, corners: BoardCorners): FloatArray? {
            if (!corners.isPlausible) return null
            val out = FloatArray(SIDE * SIDE)
            for (y in 0 until SIDE) {
                val v = 1.0 - (y + 0.5) / SIDE
                for (x in 0 until SIDE) {
                    val p = corners.point((x + 0.5) / SIDE, v)
                    if (!p.x.isFinite() || !p.y.isFinite()) return null
                    out[y * SIDE + x] = frame.sample(p.x * frame.width - 0.5, p.y * frame.height - 0.5).toFloat()
                }
            }
            return out
        }
    }

    data class BoardMatch(val contrast: Float, val occupancy: Float, val colour: Float) {
        val total: Float get() = contrast + occupancy + colour
        /** Chosen on synthetic boards (correct fit ≈ 15, rotated or shifted fits < 8). */
        val isConvincing: Boolean get() = total >= 7 && contrast >= 1.5f && occupancy >= 0.5f && colour >= 0.5f
    }

    private class View(val frame: Frame, val corners: BoardCorners, val pixels: FloatArray)
    private class Reference(val view: View, val model: AppearanceModel, val board: ChessBoard)

    private var reference: Reference? = null
    private var pending: View? = null

    fun reset() { reference = null; pending = null }

    /** Finds the board from its grid of square corners, oriented to look most like [board]. */
    fun locateBoard(frame: Frame, board: ChessBoard): Pair<BoardCorners, BoardMatch>? {
        var best: Pair<BoardCorners, BoardMatch>? = null
        for (outline in ChessGridDetector.boardOutlines(frame).take(12)) {
            val corners = BoardCorners.of(BoardCorners.counterClockwise(outline))
            for (turn in 0 until 4) {
                val c = corners.rotated(turn)
                val pixels = warp(frame, c) ?: continue
                val match = boardMatch(pixels, board)
                if (best == null || match.total > best.second.total) best = c to match
            }
        }
        return best?.takeIf { it.second.isConvincing }
    }

    /** A starting point for setting corners by hand: the automatic fit, else a centred default. */
    fun suggestCorners(frame: Frame, board: ChessBoard): BoardCorners =
        locateBoard(frame, board)?.first
            ?: BoardCorners(Pt(0.15, 0.75), Pt(0.85, 0.75), Pt(0.85, 0.4), Pt(0.15, 0.4))

    fun calibrate(frame: Frame, corners: BoardCorners, board: ChessBoard): Boolean {
        val pixels = warp(frame, corners) ?: return false
        set(View(frame, corners, pixels), board)
        return true
    }

    fun observe(frame: Frame, player: PieceColor): TrackerResult {
        val ref = reference ?: return TrackerResult.NotCalibrated
        val view = align(frame, ref) ?: return TrackerResult.Lost
        pending = view
        val diffs = differences(ref.view.pixels, view.pixels)
        val f = features(view.pixels)
        val contents = Array(64) { ref.model.probabilities(f[it], Square.fromIndex(it).isLight) }
        return TrackerResult.Observed(SquareObservation(diffs, contents), view.corners, mismatchedSquares(f, ref.board, player), f)
    }

    fun commitPending(board: ChessBoard) {
        pending?.let { set(it, board) }
    }

    fun resync(frame: Frame, board: ChessBoard): BoardCorners? {
        val ref = reference ?: return null
        val view = align(frame, ref) ?: return null
        set(view, board)
        return view.corners
    }

    private fun set(view: View, board: ChessBoard) {
        reference = Reference(view, AppearanceModel(features(view.pixels), board), board)
        pending = null
    }

    private fun align(frame: Frame, ref: Reference): View? {
        var best: Pair<View, Float>? = null
        fun evaluate(c: BoardCorners) {
            val pixels = warp(frame, c) ?: return
            // Average of all but the 8 most changed squares: sensitive to misalignment anywhere on
            // the board, yet unaffected by the few squares a move changes.
            val error = differences(ref.view.pixels, pixels).sorted().take(56).average().toFloat()
            if (best == null || error < best!!.second) best = View(frame, c, pixels) to error
        }
        evaluate(ref.view.corners)
        // Poor fit (the wearer moved or looked away and back): find the board from scratch, but
        // still compare with the last confirmed view so a move made meanwhile isn't missed.
        if ((best?.second ?: Float.MAX_VALUE) > LOST_THRESHOLD / 2) {
            locateBoard(frame, ref.board)?.let { evaluate(it.first) }
        }
        val start = best ?: return null
        val c = start.first.corners
        val boardPixels = hypot((c.h1.x - c.a1.x) * frame.width, (c.h1.y - c.a1.y) * frame.height)
        var step = boardPixels * 0.03
        repeat(3) {
            val center = best!!.first.corners
            for (dx in listOf(-step, 0.0, step)) for (dy in listOf(-step, 0.0, step)) {
                if (dx == 0.0 && dy == 0.0) continue
                evaluate(center.translated(dx / frame.width, dy / frame.height))
            }
            step /= 2
        }
        return best?.takeIf { it.second <= LOST_THRESHOLD }?.first
    }
}

/**
 * Learns what empty squares, White pieces and Black pieces look like (separately on light and
 * dark squares) from a view whose position is known, then classifies squares in later views.
 */
class AppearanceModel(features: Array<FloatArray>, board: ChessBoard) {
    private val centroids = Array(2) { arrayOfNulls<FloatArray>(3) }
    private val spread = FloatArray(2)

    init {
        val sums = Array(2) { Array(3) { FloatArray(2) } }
        val counts = Array(2) { IntArray(3) }
        for (i in 0 until 64) {
            val shade = if (Square.fromIndex(i).isLight) 1 else 0
            val kind = when (board.at(i)?.color) { null -> 0; PieceColor.WHITE -> 1; PieceColor.BLACK -> 2 }
            for (d in 0..1) sums[shade][kind][d] += features[i][d]
            counts[shade][kind]++
        }
        for (s in 0..1) for (k in 0..2) {
            if (counts[s][k] > 0) centroids[s][k] = FloatArray(2) { sums[s][k][it] / counts[s][k] }
        }
        for (d in 0..1) {
            val values = features.map { it[d] }
            spread[d] = max(sqrt(values.variance()), 0.02f)
        }
    }

    /** Probabilities of [empty, white, black], kept within 0.05..0.95. */
    fun probabilities(f: FloatArray, light: Boolean): FloatArray {
        val shade = if (light) 1 else 0
        val logits = FloatArray(3) { k ->
            val c = centroids[shade][k] ?: centroids[1 - shade][k] ?: return@FloatArray -1e30f
            var d2 = 0f
            for (d in 0..1) { val z = (f[d] - c[d]) / spread[d]; d2 += z * z }
            -0.5f * d2
        }
        val top = logits.max()
        val w = FloatArray(3) { exp(max(logits[it] - top, -80f)) }
        val total = w.sum()
        return FloatArray(3) { 0.05f + 0.9f * w[it] / total }
    }
}
