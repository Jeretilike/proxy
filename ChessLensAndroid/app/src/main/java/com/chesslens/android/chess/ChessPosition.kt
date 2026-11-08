package com.chesslens.android.chess

import kotlin.math.abs

/**
 * A full game state with legal move generation. Same algorithm as the iOS app, whose prototype
 * was checked against standard perft counts (see the unit test for this class).
 */
data class ChessPosition(
    val board: ChessBoard,
    val sideToMove: PieceColor,
    val castlingRights: Set<Char>,
    val enPassant: Square? = null,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1,
) {
    val fen: String
        get() {
            val castling = "KQkq".filter { it in castlingRights }
            return listOf(
                board.placementFen,
                sideToMove.fenLetter,
                castling.ifEmpty { "-" },
                enPassant?.algebraic ?: "-",
                halfmoveClock.toString(),
                fullmoveNumber.toString(),
            ).joinToString(" ")
        }

    fun legalMoves(): List<UciMove> = pseudoLegalMoves().filter { move ->
        val next = board.applying(move)
        val king = next.kingSquare(sideToMove) ?: return@filter false
        !next.isAttacked(king, sideToMove.opposite)
    }

    fun applying(move: UciMove): ChessPosition {
        val piece = board[move.from] ?: return this
        val capture = board[move.to] != null || (piece.kind == PieceKind.PAWN && move.from.file != move.to.file)
        val rights = castlingRights.toMutableSet()
        if (piece.kind == PieceKind.KING) {
            rights -= if (piece.color == PieceColor.WHITE) setOf('K', 'Q') else setOf('k', 'q')
        }
        for ((corner, right) in ROOK_CORNERS) {
            if (move.from == corner || move.to == corner) rights -= right
        }
        val enPassant = if (piece.kind == PieceKind.PAWN && abs(move.to.rank - move.from.rank) == 2)
            Square(move.from.file, (move.from.rank + move.to.rank) / 2) else null
        return ChessPosition(
            board = board.applying(move),
            sideToMove = sideToMove.opposite,
            castlingRights = rights,
            enPassant = enPassant,
            halfmoveClock = if (piece.kind == PieceKind.PAWN || capture) 0 else halfmoveClock + 1,
            fullmoveNumber = if (sideToMove == PieceColor.BLACK) fullmoveNumber + 1 else fullmoveNumber,
        )
    }

    private fun pseudoLegalMoves(): List<UciMove> {
        val moves = mutableListOf<UciMove>()
        val us = sideToMove
        val them = us.opposite

        for ((from, piece) in board.pieces(us)) {
            when (piece.kind) {
                PieceKind.PAWN -> {
                    val dir = if (us == PieceColor.WHITE) 1 else -1
                    val startRank = if (us == PieceColor.WHITE) 1 else 6
                    val lastRank = if (us == PieceColor.WHITE) 7 else 0
                    fun add(to: Square) {
                        if (to.rank == lastRank) {
                            for (p in listOf(PieceKind.QUEEN, PieceKind.ROOK, PieceKind.BISHOP, PieceKind.KNIGHT)) {
                                moves += UciMove(from, to, p)
                            }
                        } else {
                            moves += UciMove(from, to)
                        }
                    }
                    val one = from.offset(0, dir)
                    if (one != null && board[one] == null) {
                        add(one)
                        val two = from.offset(0, 2 * dir)
                        if (from.rank == startRank && two != null && board[two] == null) moves += UciMove(from, two)
                    }
                    for (df in listOf(-1, 1)) {
                        val to = from.offset(df, dir) ?: continue
                        if (board[to]?.color == them || to == enPassant) add(to)
                    }
                }
                PieceKind.KNIGHT, PieceKind.KING -> {
                    val steps = if (piece.kind == PieceKind.KNIGHT) ChessBoard.KNIGHT_STEPS else ChessBoard.KING_STEPS
                    for ((df, dr) in steps) {
                        val to = from.offset(df, dr) ?: continue
                        if (board[to]?.color != us) moves += UciMove(from, to)
                    }
                }
                else -> {
                    val dirs = when (piece.kind) {
                        PieceKind.ROOK -> ChessBoard.ROOK_DIRS
                        PieceKind.BISHOP -> ChessBoard.BISHOP_DIRS
                        else -> ChessBoard.ROOK_DIRS + ChessBoard.BISHOP_DIRS
                    }
                    for ((df, dr) in dirs) {
                        var target = from.offset(df, dr)
                        while (target != null) {
                            val occupant = board[target]
                            if (occupant != null) {
                                if (occupant.color == them) moves += UciMove(from, target)
                                break
                            }
                            moves += UciMove(from, target)
                            target = target.offset(df, dr)
                        }
                    }
                }
            }
        }

        // Castling: king and rook unmoved (per rights), path empty, king not passing through check.
        val rank = if (us == PieceColor.WHITE) 0 else 7
        fun sq(file: Int) = Square(file, rank)
        val king = Piece(us, PieceKind.KING)
        val rook = Piece(us, PieceKind.ROOK)
        if (board[sq(4)] == king && !board.isAttacked(sq(4), them)) {
            val kingside = if (us == PieceColor.WHITE) 'K' else 'k'
            val queenside = if (us == PieceColor.WHITE) 'Q' else 'q'
            if (kingside in castlingRights && board[sq(7)] == rook && board[sq(5)] == null && board[sq(6)] == null &&
                !board.isAttacked(sq(5), them) && !board.isAttacked(sq(6), them)
            ) moves += UciMove(sq(4), sq(6))
            if (queenside in castlingRights && board[sq(0)] == rook && board[sq(1)] == null && board[sq(2)] == null &&
                board[sq(3)] == null && !board.isAttacked(sq(3), them) && !board.isAttacked(sq(2), them)
            ) moves += UciMove(sq(4), sq(2))
        }
        return moves
    }

    companion object {
        private val ROOK_CORNERS = listOf(Square(0, 0) to 'Q', Square(7, 0) to 'K', Square(0, 7) to 'q', Square(7, 7) to 'k')

        /** Castling rights inferred from where the kings and rooks stand. */
        fun of(board: ChessBoard, sideToMove: PieceColor): ChessPosition {
            val rights = board.inferredCastlingRights
            return ChessPosition(board, sideToMove, if (rights == "-") emptySet() else rights.toSet())
        }

        /** Parses a full FEN (castling and en passant fields included when present). */
        fun fromFen(fen: String): ChessPosition? {
            val fields = fen.trim().split(Regex("\\s+"))
            val board = ChessBoard.fromFen(fields[0]) ?: return null
            val side = if (fields.getOrNull(1) == "b") PieceColor.BLACK else PieceColor.WHITE
            val castling = fields.getOrNull(2)?.takeIf { it != "-" }?.toSet()?.filter { it in "KQkq" }?.toSet()
                ?: if (fields.size > 2) emptySet() else of(board, side).castlingRights
            val ep = fields.getOrNull(3)?.let { Square.parse(it) }
            return ChessPosition(board, side, castling, ep, fields.getOrNull(4)?.toIntOrNull() ?: 0, fields.getOrNull(5)?.toIntOrNull() ?: 1)
        }

        val START: ChessPosition = of(ChessBoard.STARTING, PieceColor.WHITE)
    }
}
