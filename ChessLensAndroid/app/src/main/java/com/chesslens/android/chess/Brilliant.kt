package com.chesslens.android.chess

import kotlin.math.exp
import kotlin.math.max

/** One engine line: first move, score from the side to move's point of view, and the variation. */
data class EngineLine(val move: String, val centipawns: Int?, val mate: Int?, val pv: List<String>)

/**
 * "Brilliant" moves as chess.com describes them: a good piece sacrifice that is the best move or
 * almost as good, without being worse after it or already completely winning. Same rules and
 * numbers as the PC app's brilliant.js.
 */
object Brilliant {
    private val VALUE = mapOf(
        PieceKind.PAWN to 1, PieceKind.KNIGHT to 3, PieceKind.BISHOP to 3,
        PieceKind.ROOK to 5, PieceKind.QUEEN to 9, PieceKind.KING to 100,
    )
    const val NEAR_BEST = 0.03
    const val MIN_WIN_CHANCE_AFTER = 0.45
    const val ALREADY_WINNING_CP = 600
    const val MIN_SACRIFICE = 2

    fun winChance(line: EngineLine): Double {
        line.mate?.let { return if (it > 0) 1.0 else 0.0 }
        val cp = line.centipawns ?: 0
        return 1 / (1 + exp(-0.00368208 * cp))
    }

    private fun value(p: Piece) = VALUE.getValue(p.kind)

    /** Squares (indices) of [color] pieces attacking [target], pins ignored. */
    private fun attackers(board: Array<Piece?>, target: Int, color: PieceColor): List<Int> {
        val result = ArrayList<Int>()
        val tf = target % 8
        val tr = target / 8
        fun at(f: Int, r: Int) = if (f in 0..7 && r in 0..7) r * 8 + f else -1
        fun has(i: Int, kinds: Set<PieceKind>) = i >= 0 && board[i]?.let { it.color == color && it.kind in kinds } == true
        val pr = if (color == PieceColor.WHITE) tr - 1 else tr + 1
        for (df in intArrayOf(-1, 1)) at(tf + df, pr).let { if (has(it, setOf(PieceKind.PAWN))) result += it }
        for ((df, dr) in ChessBoard.KNIGHT_STEPS) at(tf + df, tr + dr).let { if (has(it, setOf(PieceKind.KNIGHT))) result += it }
        for ((df, dr) in ChessBoard.KING_STEPS) at(tf + df, tr + dr).let { if (has(it, setOf(PieceKind.KING))) result += it }
        fun slide(dirs: List<Pair<Int, Int>>, kinds: Set<PieceKind>) {
            for ((df, dr) in dirs) {
                var f = tf + df
                var r = tr + dr
                while (f in 0..7 && r in 0..7) {
                    val p = board[r * 8 + f]
                    if (p != null) {
                        if (p.color == color && p.kind in kinds) result += r * 8 + f
                        break
                    }
                    f += df; r += dr
                }
            }
        }
        slide(ChessBoard.ROOK_DIRS, setOf(PieceKind.ROOK, PieceKind.QUEEN))
        slide(ChessBoard.BISHOP_DIRS, setOf(PieceKind.BISHOP, PieceKind.QUEEN))
        return result
    }

    /** Material [side] can win by capturing on [target] and trading off optimally (either side may stop). */
    private fun see(board: Array<Piece?>, target: Int, side: PieceColor): Int {
        val victim = board[target] ?: return 0
        val from = attackers(board, target, side).minByOrNull { value(board[it]!!) } ?: return 0
        return max(0, value(victim) - seeAfterCapture(board, from, target, side))
    }

    private fun seeAfterCapture(board: Array<Piece?>, from: Int, target: Int, side: PieceColor): Int {
        val next = board.copyOf()
        val mover = next[from]!!
        next[target] = mover
        next[from] = null
        if (mover.kind == PieceKind.KING && attackers(next, target, side.opposite).isNotEmpty()) return 1000
        return see(next, target, side.opposite)
    }

    private fun cells(board: ChessBoard) = Array(64) { board.at(it) }

    /** Net material (pawn units) the opponent can win right after [move], and the piece they'd win. */
    fun sacrificed(position: ChessPosition, move: UciMove): Pair<Int, PieceKind?> {
        val mover = position.board[move.from] ?: return 0 to null
        var taken = position.board[move.to]?.let { value(it) }
            ?: if (mover.kind == PieceKind.PAWN && move.from.file != move.to.file) 1 else 0
        move.promotion?.let { taken += VALUE.getValue(it) - 1 }
        val after = position.applying(move)
        val board = cells(after.board)
        var best = 0
        var piece: PieceKind? = null
        for (reply in after.legalMoves()) {
            val victim = after.board[reply.to]
            val attacker = after.board[reply.from] ?: continue
            val enPassant = victim == null && attacker.kind == PieceKind.PAWN && reply.from.file != reply.to.file
            if (victim == null && !enPassant) continue
            if (victim?.kind == PieceKind.KING) continue
            val gain = if (enPassant) 1 else value(victim!!) - seeAfterCapture(board, reply.from.index, reply.to.index, mover.color.opposite)
            if (gain > best) {
                best = gain
                piece = if (enPassant) PieceKind.PAWN else victim!!.kind
            }
        }
        return (best - taken) to piece
    }

    fun isSacrifice(position: ChessPosition, move: UciMove): Boolean {
        val (net, piece) = sacrificed(position, move)
        return net >= MIN_SACRIFICE && piece != null && piece != PieceKind.PAWN
    }

    /** The move to suggest from MultiPV lines (best first); second value: is it brilliant. */
    fun choose(position: ChessPosition, lines: List<EngineLine>, brilliantMode: Boolean): Pair<EngineLine, Boolean>? {
        val best = lines.firstOrNull() ?: return null
        if (!brilliantMode) return best to false
        val bestChance = winChance(best)
        val alreadyWinning = best.mate == null && (best.centipawns ?: 0) > ALREADY_WINNING_CP
        var pick: EngineLine? = null
        for (line in lines) {
            val chance = winChance(line)
            if (bestChance - chance > NEAR_BEST) continue
            if (chance < MIN_WIN_CHANCE_AFTER) continue
            if (alreadyWinning && !(line.mate != null && line.mate > 0)) continue
            val move = UciMove.parse(line.move) ?: continue
            if (!isSacrifice(position, move)) continue
            if (pick == null || chance > winChance(pick)) pick = line
        }
        return if (pick != null) pick to true else best to false
    }
}
