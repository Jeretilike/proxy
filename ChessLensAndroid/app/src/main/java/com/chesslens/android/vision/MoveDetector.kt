package com.chesslens.android.vision

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.PieceKind
import com.chesslens.android.chess.Square
import com.chesslens.android.chess.UciMove
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed class MoveDetection {
    object NoChange : MoveDetection()
    /** One move, or two (yours and the reply) if both happened between looks. */
    class Moves(val moves: List<UciMove>, val result: ChessPosition) : MoveDetection()
    object Unclear : MoveDetection()
    object Ambiguous : MoveDetection()
}

/**
 * Works out which legal move(s) explain the squares that changed. Same scoring as the iOS app
 * (tuned on simulated noise with a Python prototype).
 */
object MoveDetector {
    private const val CHANGE_THRESHOLD = 4f
    /** Floor for the noise scale (0–255 units); square comparisons tolerate small shifts, so 3 is enough. */
    private const val NOISE_FLOOR = 3f

    private class Score(val total: Float, val evidence: Float, val unexplained: Int)
    private class Candidate(val moves: List<UciMove>, val result: ChessPosition, val score: Score)

    private fun UciMove.isQueenOrPlain() = promotion == null || promotion == PieceKind.QUEEN

    fun detect(position: ChessPosition, observation: SquareObservation, player: PieceColor): MoveDetection {
        val z = robustZScores(observation.differences)
        if ((z.maxOrNull() ?: 0f) < CHANGE_THRESHOLD) return MoveDetection.NoChange

        val best = HashMap<String, Candidate>()
        fun consider(moves: List<UciMove>, result: ChessPosition) {
            val fit = score(position.board, result.board, z, observation.contents, player) ?: return
            val key = result.board.placementFen
            val existing = best[key]
            if (existing != null && existing.score.total >= fit.total) return
            best[key] = Candidate(moves, result, fit)
        }
        val firstMoves = position.legalMoves().filter { it.isQueenOrPlain() }
        for (m in firstMoves) consider(listOf(m), position.applying(m))

        val topSingle = best.values.maxByOrNull { it.score.total }
        if (topSingle == null || topSingle.score.unexplained != 0 || topSingle.score.evidence < 3) {
            for (first in firstMoves) {
                val middle = position.applying(first)
                for (second in middle.legalMoves()) if (second.isQueenOrPlain()) consider(listOf(first, second), middle.applying(second))
            }
        }
        val ranked = best.values.sortedByDescending { it.score.total }
        val top = ranked.firstOrNull() ?: return MoveDetection.Unclear
        if (top.score.unexplained != 0 || top.score.evidence < 3) return MoveDetection.Unclear
        val margin = if (top.moves.size == 1) 0.8f else 1.5f
        if (ranked.size > 1 && top.score.total - ranked[1].score.total < margin) return MoveDetection.Ambiguous
        return MoveDetection.Moves(top.moves, top.result)
    }

    /**
     * The move (or move and reply) after which [mismatches] is zero, when exactly one resulting
     * board fits. Used when comparison with the last confirmed view missed a move.
     */
    fun recover(position: ChessPosition, mismatches: (ChessBoard) -> Int): Pair<List<UciMove>, ChessPosition>? {
        if (mismatches(position.board) == 0) return null
        val fits = HashMap<String, Pair<List<UciMove>, ChessPosition>>()
        val firstMoves = position.legalMoves().filter { it.isQueenOrPlain() }
        var bestSingle = Int.MAX_VALUE
        for (m in firstMoves) {
            val r = position.applying(m)
            val count = mismatches(r.board)
            bestSingle = minOf(bestSingle, count)
            if (count == 0) fits[r.board.placementFen] = listOf(m) to r
        }
        // Only consider a move and a reply when no single move comes close to explaining the
        // board; otherwise a near-miss single move plus an arbitrary reply could "fit".
        if (fits.isEmpty() && bestSingle >= 2) {
            for (first in firstMoves) {
                val middle = position.applying(first)
                for (second in middle.legalMoves()) {
                    if (!second.isQueenOrPlain()) continue
                    val r = middle.applying(second)
                    if (mismatches(r.board) == 0) fits.putIfAbsent(r.board.placementFen, listOf(first, second) to r)
                }
            }
        }
        return if (fits.size == 1) fits.values.first() else null
    }

    private fun score(before: ChessBoard, after: ChessBoard, z: FloatArray, contents: Array<FloatArray>, player: PieceColor): Score? {
        val changed = (0 until 64).filter { before.at(it) != after.at(it) }
        if (changed.isEmpty()) return null
        val evidence = changed.sumOf { min(max(z[it], -2f), 8f).toDouble() }.toFloat() / changed.size
        val weak = changed.sumOf { max(0f, 2.5f - z[it]).toDouble() }.toFloat() * 0.5f
        var mismatch = 0f
        for (i in 0 until 64) {
            val expected = when (after.at(i)?.color) { null -> 0; PieceColor.WHITE -> 1; PieceColor.BLACK -> 2 }
            mismatch += 1 - contents[i][expected]
        }
        val excused = changed.toHashSet()
        val away = if (player == PieceColor.WHITE) 1 else -1
        for (i in changed) {
            val s = Square.fromIndex(i)
            for (step in 1..2) s.offset(0, away * step)?.let { excused += it.index }
        }
        val unexplained = (0 until 64).count { z[it] > 5 && it !in excused }
        return Score(evidence - weak - 1.5f * mismatch - 2 * unexplained, evidence, unexplained)
    }

    fun robustZScores(values: FloatArray): FloatArray {
        val list = values.toList()
        val median = list.median()
        val mad = list.map { abs(it - median) }.median() * 1.4826f
        val scale = max(mad, NOISE_FLOOR)
        return FloatArray(values.size) { (values[it] - median) / scale }
    }
}

/**
 * Decides when the camera view has settled after movement, so a check runs once the wearer's
 * head and hands are still. (Periodic checks are scheduled by the caller.)
 */
class SceneChangeDetector {
    private val stillThreshold = 6.0
    private val changeThreshold = 3.0
    private val settleMillis = 1000L
    private val side = 24

    private var previous: IntArray? = null
    private var stillSince: Long? = null
    private var lastAnalyzed: IntArray? = null
    private var movedSinceAnalysis = true

    fun shouldAnalyze(frame: Frame, now: Long): Boolean {
        val thumb = thumbnail(frame)
        val prev = previous
        previous = thumb
        if (prev == null || difference(prev, thumb) >= stillThreshold) {
            stillSince = null
            movedSinceAnalysis = true
            return false
        }
        val since = stillSince ?: now.also { stillSince = it }
        if (now - since < settleMillis) return false
        if (movedSinceAnalysis || lastAnalyzed == null) return true
        return difference(lastAnalyzed!!, thumb) >= changeThreshold
    }

    fun markAnalyzed(frame: Frame) {
        lastAnalyzed = thumbnail(frame)
        movedSinceAnalysis = false
    }

    fun reset() {
        previous = null; stillSince = null; lastAnalyzed = null; movedSinceAnalysis = true
    }

    private fun thumbnail(frame: Frame): IntArray = IntArray(side * side) { i ->
        val x = (i % side + 0.5) * frame.width / side
        val y = (i / side + 0.5) * frame.height / side
        frame.at(x.toInt().coerceIn(0, frame.width - 1), y.toInt().coerceIn(0, frame.height - 1))
    }

    private fun difference(a: IntArray, b: IntArray): Double {
        var t = 0L
        for (i in a.indices) t += abs(a[i] - b[i])
        return t.toDouble() / a.size
    }
}
