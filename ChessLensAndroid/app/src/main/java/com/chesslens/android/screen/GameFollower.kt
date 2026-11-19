package com.chesslens.android.screen

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.PieceColor

/**
 * Keeps track of the game from what is read on screen. Each reading is matched to the current
 * position, or to one move or a move and reply from it, choosing whichever board agrees with the
 * screen on the most squares, so an occasional misread square doesn't derail the game.
 */
class GameFollower(var position: ChessPosition = ChessPosition.START) {
    companion object {
        /** A reading may disagree with the matched position on at most this many squares. */
        const val MAX_MISREAD = 2
        /** A clear reading that keeps disagreeing this many times in a row wins over the game so far. */
        const val TRUST_SCREEN_AFTER = 3

        fun differences(a: ChessBoard, b: ChessBoard): Int = (0 until 64).count { a.at(it) != b.at(it) }
    }

    /**
     * Updates [position] from a reading. [lastMover] comes from the last-move highlight (or null),
     * [uncertain] is how many squares only roughly matched a known piece.
     */
    private var lastSeen: ChessBoard? = null
    private var repeats = 0

    fun follow(seen: ChessBoard, lastMover: PieceColor?, uncertain: Int): ChessPosition {
        repeats = if (seen == lastSeen) repeats + 1 else 1
        lastSeen = seen
        // The starting position on screen means a new game, whatever happened before.
        if (seen == ChessPosition.START.board) {
            if (position.board != seen) position = ChessPosition.START
            return position
        }
        var match = closest(position, seen)
        // The same clear, possible board seen again and again that still doesn't fit: the game here
        // went wrong somewhere, so take the screen's word for it.
        if (match != null && differences(match.board, seen) > 0 && uncertain == 0 && repeats >= TRUST_SCREEN_AFTER && isPlausible(seen)) {
            match = null
        }
        position = when {
            match != null && match.board == position.board ->
                // No move made. The highlight may still say whose turn it is (e.g. at the start).
                if (lastMover != null && position.sideToMove == lastMover && differences(position.board, seen) == 0)
                    position.copy(sideToMove = lastMover.opposite, enPassant = null) else position
            match != null -> match
            (uncertain == 0 || repeats >= TRUST_SCREEN_AFTER) && isPlausible(seen) -> {
                // Nothing close: a new game or a jump (e.g. moves made while ChessLens wasn't looking).
                val turn = lastMover?.opposite ?: position.sideToMove
                if (seen.validationIssues(turn).isEmpty()) ChessPosition.of(seen, turn) else ChessPosition.of(seen, turn.opposite)
            }
            else -> position // a rough reading of an unfamiliar board: wait for a clearer one
        }
        return position
    }

    /** A board that could be a real game: one king each, no pawns on the first or last rank. */
    private fun isPlausible(board: ChessBoard): Boolean {
        val kings = (0 until 64).mapNotNull { board.at(it) }.filter { it.kind == com.chesslens.android.chess.PieceKind.KING }
        if (kings.count { it.color == PieceColor.WHITE } != 1 || kings.count { it.color == PieceColor.BLACK } != 1) return false
        val pawnOnEdge = (0 until 8).any { f -> listOf(f, 56 + f).any { board.at(it)?.kind == com.chesslens.android.chess.PieceKind.PAWN } }
        return !pawnOnEdge && (board.validationIssues(PieceColor.WHITE).isEmpty() || board.validationIssues(PieceColor.BLACK).isEmpty())
    }

    private fun closest(from: ChessPosition, seen: ChessBoard): ChessPosition? {
        var best: ChessPosition? = null
        var bestDiff = Int.MAX_VALUE
        var tie = false
        fun consider(p: ChessPosition, plies: Int) {
            // Prefer fewer moves on equal agreement (staying put beats an invented move and reply).
            val d = differences(p.board, seen) * 3 + plies
            if (d < bestDiff) { best = p; bestDiff = d; tie = false }
            else if (d == bestDiff && p.board != best?.board) tie = true
        }
        consider(from, 0)
        val firsts = from.legalMoves()
        val middles = firsts.map { from.applying(it) }
        for (m in middles) consider(m, 1)
        for (m in middles) for (second in m.legalMoves()) consider(m.applying(second), 2)
        val result = best ?: return null
        if (tie) return null
        return if (differences(result.board, seen) <= MAX_MISREAD) result else null
    }
}
