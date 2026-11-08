package com.chesslens.android.chess

import org.junit.Assert.assertEquals
import org.junit.Test

/** Perft: counts leaf nodes of the legal-move tree; standard values catch move-generation bugs. */
class ChessPositionTest {
    private fun perft(position: ChessPosition, depth: Int): Long {
        if (depth == 0) return 1
        val moves = position.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(position.applying(it), depth - 1) }
    }

    private fun check(fen: String, vararg expected: Long) {
        val position = ChessPosition.fromFen(fen)!!
        expected.forEachIndexed { i, count -> assertEquals("$fen depth ${i + 1}", count, perft(position, i + 1)) }
    }

    @Test fun startPosition() = check("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 20, 400, 8902, 197281)
    @Test fun kiwipete() = check("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 48, 2039, 97862)
    @Test fun endgame() = check("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 14, 191, 2812, 43238)
    @Test fun promotions() = check("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 6, 264, 9467)
    @Test fun position5() = check("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 44, 1486, 62379)

    @Test fun notationAndSpeech() {
        val start = ChessPosition.START
        val move = UciMove.parse("g1f3")!!
        assertEquals("Nf3", start.board.shortNotation(move))
        assertEquals("Knight g1 to f3", start.board.spokenDescription(move))
        assertEquals("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1", start.applying(move).fen)
    }
}
