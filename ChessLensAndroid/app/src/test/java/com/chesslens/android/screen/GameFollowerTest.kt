package com.chesslens.android.screen

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.Piece
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.PieceKind
import com.chesslens.android.chess.Square
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class GameFollowerTest {
    /** Misreads like the ones seen on real screens: a piece read as another, a ghost piece, a missing piece. */
    private fun misread(board: ChessBoard, rnd: Random, count: Int): ChessBoard {
        var b = board
        repeat(count) {
            val i = rnd.nextInt(64)
            val sq = Square.fromIndex(i)
            val p = b.at(i)
            b = when {
                p?.kind == PieceKind.KING -> b.with(sq, Piece(p.color, PieceKind.QUEEN)) // "king replaced by something"
                p != null && rnd.nextBoolean() -> b.with(sq, Piece(p.color, PieceKind.KING)) // "two kings"
                p != null -> b.with(sq, Piece(p.color, PieceKind.entries[rnd.nextInt(5)]))
                else -> b.with(sq, Piece(if (rnd.nextBoolean()) PieceColor.WHITE else PieceColor.BLACK, PieceKind.BISHOP))
            }
        }
        return b
    }

    @Test fun followsGamesDespiteMisreads() {
        var followed = 0
        var total = 0
        for (game in 0 until 40) {
            val rnd = Random(game)
            var truth = ChessPosition.START
            val follower = GameFollower()
            for (ply in 0 until 60) {
                val moves = truth.legalMoves()
                if (moves.isEmpty()) break
                // Prefer knight moves now and then, since those caused trouble.
                val knight = moves.filter { truth.board[it.from]?.kind == PieceKind.KNIGHT }
                truth = truth.applying(if (knight.isNotEmpty() && rnd.nextInt(3) == 0) knight.random(rnd) else moves.random(rnd))
                // Sometimes the reply comes before ChessLens looks again (two moves in one look).
                if (rnd.nextInt(4) == 0) {
                    val replies = truth.legalMoves()
                    if (replies.isEmpty()) break
                    truth = truth.applying(replies.random(rnd))
                }
                // Most readings are perfect; some have one or two wrong squares.
                val wrong = when (rnd.nextInt(10)) { 0, 1 -> 1; 2 -> 2; else -> 0 }
                val seen = misread(truth.board, rnd, wrong)
                val justMoved = truth.sideToMove.opposite
                follower.follow(seen, justMoved, wrong)
                total++
                if (follower.position.board == truth.board && follower.position.sideToMove == truth.sideToMove) followed++
                else {
                    // It must catch up once the screen shows the position clearly for a moment
                    // (the screen is re-read about every 0.6 s while it's still).
                    repeat(GameFollower.TRUST_SCREEN_AFTER) { follower.follow(truth.board, justMoved, 0) }
                }
                assertEquals("game $game ply $ply: lost track after a clean reading", truth.board, follower.position.board)
            }
        }
        println("positions followed correctly on the first look: $followed/$total")
        assert(followed >= total * 0.97) { "too many misreads followed: $followed/$total" }
    }

    @Test fun ignoresImpossibleBoards() {
        val follower = GameFollower()
        val twoKings = ChessPosition.START.board.with(Square(3, 0), Piece(PieceColor.WHITE, PieceKind.KING))
            .with(Square(3, 1), null).with(Square(3, 3), Piece(PieceColor.WHITE, PieceKind.PAWN))
            .with(Square(4, 1), null).with(Square(4, 3), Piece(PieceColor.WHITE, PieceKind.PAWN))
        follower.follow(twoKings, null, 0)
        assertEquals(ChessPosition.START.board, follower.position.board)
    }

    @Test fun newGameAfterAnOldOne() {
        // Follow a game as far as move 20 with Black to move, then a new game appears.
        val rnd = Random(5)
        var truth = ChessPosition.START
        val follower = GameFollower()
        repeat(21) {
            truth = truth.applying(truth.legalMoves().random(rnd))
            follower.follow(truth.board, truth.sideToMove.opposite, 0)
        }
        assertEquals(PieceColor.BLACK, follower.position.sideToMove)
        // New game: the start position, even with a doubtful square, means White to move.
        follower.follow(ChessPosition.START.board, null, 1)
        assertEquals(ChessPosition.START.board, follower.position.board)
        assertEquals(PieceColor.WHITE, follower.position.sideToMove)
        // And the new game is followed: 1.e4 e5.
        var game = ChessPosition.START
        for (uci in listOf("e2e4", "e7e5", "g1f3")) {
            game = game.applying(com.chesslens.android.chess.UciMove.parse(uci)!!)
            follower.follow(game.board, game.sideToMove.opposite, 0)
            assertEquals(game.board, follower.position.board)
            assertEquals(game.sideToMove, follower.position.sideToMove)
        }
    }

    @Test fun switchesToAnotherGameShownOnScreen() {
        // A different game in progress (e.g. opened from the game list) with a doubtful square.
        val other = ChessPosition.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4")!!
        val follower = GameFollower()
        repeat(GameFollower.TRUST_SCREEN_AFTER) { follower.follow(other.board, PieceColor.BLACK, 1) }
        assertEquals(other.board, follower.position.board)
        assertEquals(PieceColor.WHITE, follower.position.sideToMove)
    }
}
