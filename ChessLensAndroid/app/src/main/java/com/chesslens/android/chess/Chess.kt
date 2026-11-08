package com.chesslens.android.chess

import kotlin.math.abs

enum class PieceColor(val fenLetter: String, val displayName: String) {
    WHITE("w", "White"),
    BLACK("b", "Black");

    val opposite: PieceColor get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceKind(val letter: Char, val displayName: String) {
    PAWN('p', "Pawn"),
    KNIGHT('n', "Knight"),
    BISHOP('b', "Bishop"),
    ROOK('r', "Rook"),
    QUEEN('q', "Queen"),
    KING('k', "King");

    companion object {
        fun fromLetter(c: Char): PieceKind? = entries.firstOrNull { it.letter == c.lowercaseChar() }
    }
}

data class Piece(val color: PieceColor, val kind: PieceKind) {
    val fenChar: Char get() = if (color == PieceColor.WHITE) kind.letter.uppercaseChar() else kind.letter

    val symbol: String
        get() = when (kind) {
            PieceKind.KING -> if (color == PieceColor.WHITE) "♔" else "♚"
            PieceKind.QUEEN -> if (color == PieceColor.WHITE) "♕" else "♛"
            PieceKind.ROOK -> if (color == PieceColor.WHITE) "♖" else "♜"
            PieceKind.BISHOP -> if (color == PieceColor.WHITE) "♗" else "♝"
            PieceKind.KNIGHT -> if (color == PieceColor.WHITE) "♘" else "♞"
            PieceKind.PAWN -> if (color == PieceColor.WHITE) "♙" else "♟"
        }

    companion object {
        fun fromFen(c: Char): Piece? {
            val kind = PieceKind.fromLetter(c) ?: return null
            return Piece(if (c.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK, kind)
        }

        val all: List<Piece> = PieceColor.entries.flatMap { color ->
            listOf(PieceKind.KING, PieceKind.QUEEN, PieceKind.ROOK, PieceKind.BISHOP, PieceKind.KNIGHT, PieceKind.PAWN)
                .map { Piece(color, it) }
        }
    }
}

/** A board square: [file] 0..7 is a..h, [rank] 0..7 is 1..8. */
data class Square(val file: Int, val rank: Int) {
    val index: Int get() = rank * 8 + file
    val algebraic: String get() = "${'a' + file}${rank + 1}"
    val isLight: Boolean get() = (file + rank) % 2 == 1

    fun offset(df: Int, dr: Int): Square? = of(file + df, rank + dr)

    companion object {
        fun of(file: Int, rank: Int): Square? =
            if (file in 0..7 && rank in 0..7) Square(file, rank) else null

        fun fromIndex(index: Int) = Square(index % 8, index / 8)

        fun parse(text: String): Square? {
            if (text.length != 2) return null
            val f = text[0] - 'a'
            val r = text[1] - '1'
            return of(f, r)
        }
    }
}

data class UciMove(val from: Square, val to: Square, val promotion: PieceKind? = null) {
    val uci: String get() = from.algebraic + to.algebraic + (promotion?.letter?.toString() ?: "")

    companion object {
        fun parse(text: String): UciMove? {
            if (text.length != 4 && text.length != 5) return null
            val from = Square.parse(text.substring(0, 2)) ?: return null
            val to = Square.parse(text.substring(2, 4)) ?: return null
            val promotion = if (text.length == 5) PieceKind.fromLetter(text[4]) ?: return null else null
            return UciMove(from, to, promotion)
        }
    }
}

/** 64 squares indexed `rank * 8 + file` (a1 = 0, h8 = 63). Immutable. */
class ChessBoard private constructor(private val cells: Array<Piece?>) {

    operator fun get(square: Square): Piece? = cells[square.index]
    fun at(index: Int): Piece? = cells[index]

    fun with(square: Square, piece: Piece?): ChessBoard {
        val copy = cells.copyOf()
        copy[square.index] = piece
        return ChessBoard(copy)
    }

    override fun equals(other: Any?): Boolean = other is ChessBoard && cells.contentEquals(other.cells)
    override fun hashCode(): Int = cells.contentHashCode()

    val placementFen: String
        get() = buildString {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val piece = cells[rank * 8 + file]
                    if (piece == null) {
                        empty++
                    } else {
                        if (empty > 0) append(empty).also { empty = 0 }
                        append(piece.fenChar)
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
        }

    /** Castling rights inferred from king/rook placement. */
    val inferredCastlingRights: String
        get() {
            var rights = ""
            fun at(name: String) = Square.parse(name)?.let { this[it] }
            if (at("e1") == Piece(PieceColor.WHITE, PieceKind.KING)) {
                if (at("h1") == Piece(PieceColor.WHITE, PieceKind.ROOK)) rights += "K"
                if (at("a1") == Piece(PieceColor.WHITE, PieceKind.ROOK)) rights += "Q"
            }
            if (at("e8") == Piece(PieceColor.BLACK, PieceKind.KING)) {
                if (at("h8") == Piece(PieceColor.BLACK, PieceKind.ROOK)) rights += "k"
                if (at("a8") == Piece(PieceColor.BLACK, PieceKind.ROOK)) rights += "q"
            }
            return rights.ifEmpty { "-" }
        }

    fun pieces(color: PieceColor): List<Pair<Square, Piece>> =
        (0 until 64).mapNotNull { i -> cells[i]?.takeIf { it.color == color }?.let { Square.fromIndex(i) to it } }

    fun kingSquare(color: PieceColor): Square? =
        pieces(color).firstOrNull { it.second.kind == PieceKind.KING }?.first

    fun isAttacked(target: Square, attacker: PieceColor): Boolean {
        fun has(sq: Square?, kinds: Set<PieceKind>): Boolean {
            val p = sq?.let { this[it] } ?: return false
            return p.color == attacker && p.kind in kinds
        }
        val pawnDir = if (attacker == PieceColor.WHITE) -1 else 1
        if (has(target.offset(-1, pawnDir), setOf(PieceKind.PAWN)) || has(target.offset(1, pawnDir), setOf(PieceKind.PAWN))) return true
        if (KNIGHT_STEPS.any { (df, dr) -> has(target.offset(df, dr), setOf(PieceKind.KNIGHT)) }) return true
        if (KING_STEPS.any { (df, dr) -> has(target.offset(df, dr), setOf(PieceKind.KING)) }) return true
        fun slides(dirs: List<Pair<Int, Int>>, kinds: Set<PieceKind>): Boolean {
            for ((df, dr) in dirs) {
                var sq = target.offset(df, dr)
                while (sq != null) {
                    val p = this[sq]
                    if (p != null) {
                        if (p.color == attacker && p.kind in kinds) return true
                        break
                    }
                    sq = sq.offset(df, dr)
                }
            }
            return false
        }
        if (slides(ROOK_DIRS, setOf(PieceKind.ROOK, PieceKind.QUEEN))) return true
        if (slides(BISHOP_DIRS, setOf(PieceKind.BISHOP, PieceKind.QUEEN))) return true
        return false
    }

    fun isInCheck(color: PieceColor): Boolean {
        val king = kingSquare(color) ?: return false
        return isAttacked(king, color.opposite)
    }

    /** Problems that would make the position illegal (Stockfish assumes legal input). */
    fun validationIssues(sideToMove: PieceColor): List<String> {
        val issues = mutableListOf<String>()
        for (color in PieceColor.entries) {
            val mine = pieces(color)
            val kings = mine.count { it.second.kind == PieceKind.KING }
            if (kings != 1) issues += "${color.displayName} has $kings kings (needs exactly 1)."
            val pawns = mine.count { it.second.kind == PieceKind.PAWN }
            if (pawns > 8) issues += "${color.displayName} has $pawns pawns."
            if (mine.size > 16) issues += "${color.displayName} has ${mine.size} pieces."
        }
        for (file in 0..7) for (rank in listOf(0, 7)) {
            if (cells[rank * 8 + file]?.kind == PieceKind.PAWN) issues += "Pawn on ${Square(file, rank).algebraic} (first/last rank)."
        }
        if (issues.isEmpty() && isInCheck(sideToMove.opposite)) {
            issues += "${sideToMove.opposite.displayName} is in check but it's ${sideToMove.displayName}'s move."
        }
        return issues
    }

    /** Applies a move without legality checks (castling and en passant handled). */
    fun applying(move: UciMove): ChessBoard {
        val piece = this[move.from] ?: return this
        val next = cells.copyOf()
        next[move.from.index] = null
        if (piece.kind == PieceKind.PAWN && move.from.file != move.to.file && this[move.to] == null) {
            next[Square(move.to.file, move.from.rank).index] = null // en passant
        }
        if (piece.kind == PieceKind.KING && abs(move.to.file - move.from.file) == 2) {
            val rank = move.from.rank
            val kingside = move.to.file > move.from.file
            val rookFrom = Square(if (kingside) 7 else 0, rank)
            val rookTo = Square(if (kingside) 5 else 3, rank)
            next[rookTo.index] = next[rookFrom.index]
            next[rookFrom.index] = null
        }
        next[move.to.index] = move.promotion?.let { Piece(piece.color, it) } ?: piece
        return ChessBoard(next)
    }

    /** Short notation such as "Nf3", "exd5", "O-O", "e8=Q+" (no file disambiguation). */
    fun shortNotation(move: UciMove): String {
        val piece = this[move.from] ?: return move.uci
        val check = if (applying(move).isInCheck(piece.color.opposite)) "+" else ""
        if (piece.kind == PieceKind.KING && abs(move.to.file - move.from.file) == 2) {
            return (if (move.to.file > move.from.file) "O-O" else "O-O-O") + check
        }
        val capture = this[move.to] != null || (piece.kind == PieceKind.PAWN && move.from.file != move.to.file)
        val text = StringBuilder()
        if (piece.kind == PieceKind.PAWN) {
            if (capture) text.append(move.from.algebraic[0]).append('x')
        } else {
            text.append(piece.kind.letter.uppercaseChar())
            if (capture) text.append('x')
        }
        text.append(move.to.algebraic)
        move.promotion?.let { text.append('=').append(it.letter.uppercaseChar()) }
        return text.append(check).toString()
    }

    /** A sentence for text-to-speech, e.g. "Knight g1 to f3, check". */
    fun spokenDescription(move: UciMove): String {
        val piece = this[move.from] ?: return "${move.from.algebraic} to ${move.to.algebraic}"
        val check = if (applying(move).isInCheck(piece.color.opposite)) ", check" else ""
        if (piece.kind == PieceKind.KING && abs(move.to.file - move.from.file) == 2) {
            return (if (move.to.file > move.from.file) "Castle kingside" else "Castle queenside") + check
        }
        val captured = this[move.to]
            ?: if (piece.kind == PieceKind.PAWN && move.from.file != move.to.file) Piece(piece.color.opposite, PieceKind.PAWN) else null
        var text = "${piece.kind.displayName} ${move.from.algebraic}"
        text += if (captured != null) " takes ${captured.kind.displayName.lowercase()} on ${move.to.algebraic}"
        else " to ${move.to.algebraic}"
        move.promotion?.let { text += ", promote to ${it.displayName.lowercase()}" }
        return text + check
    }

    companion object {
        val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        val KING_STEPS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        val EMPTY = ChessBoard(arrayOfNulls(64))
        val STARTING: ChessBoard = fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")!!

        /** Parses the piece-placement field (or a full FEN; only the first field is read). */
        fun fromFen(fen: String): ChessBoard? {
            val placement = fen.trim().split(" ").firstOrNull() ?: return null
            val rows = placement.split("/")
            if (rows.size != 8) return null
            val cells = arrayOfNulls<Piece>(64)
            for ((row, text) in rows.withIndex()) {
                val rank = 7 - row
                var file = 0
                for (c in text) {
                    when {
                        c.isDigit() -> file += c - '0'
                        else -> {
                            val piece = Piece.fromFen(c) ?: return null
                            if (file > 7) return null
                            cells[rank * 8 + file] = piece
                            file++
                        }
                    }
                    if (file > 8) return null
                }
                if (file != 8) return null
            }
            return ChessBoard(cells)
        }
    }
}
