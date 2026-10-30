import Foundation

enum PieceColor: String, CaseIterable, Codable {
    case white
    case black

    var opposite: PieceColor { self == .white ? .black : .white }
    var fenLetter: String { self == .white ? "w" : "b" }
    var displayName: String { self == .white ? "White" : "Black" }
}

enum PieceKind: Character, CaseIterable {
    case pawn = "p"
    case knight = "n"
    case bishop = "b"
    case rook = "r"
    case queen = "q"
    case king = "k"

    var name: String {
        switch self {
        case .pawn: return "Pawn"
        case .knight: return "Knight"
        case .bishop: return "Bishop"
        case .rook: return "Rook"
        case .queen: return "Queen"
        case .king: return "King"
        }
    }
}

struct Piece: Equatable, Hashable {
    let color: PieceColor
    let kind: PieceKind

    init(color: PieceColor, kind: PieceKind) {
        self.color = color
        self.kind = kind
    }

    /// Parses a FEN piece letter: uppercase is White, lowercase is Black.
    init?(fenChar: Character) {
        guard let kind = PieceKind(rawValue: Character(fenChar.lowercased())) else { return nil }
        self.kind = kind
        self.color = fenChar.isUppercase ? .white : .black
    }

    var fenChar: Character {
        color == .white ? Character(String(kind.rawValue).uppercased()) : kind.rawValue
    }

    var symbol: String {
        switch (color, kind) {
        case (.white, .king): return "♔"
        case (.white, .queen): return "♕"
        case (.white, .rook): return "♖"
        case (.white, .bishop): return "♗"
        case (.white, .knight): return "♘"
        case (.white, .pawn): return "♙"
        case (.black, .king): return "♚"
        case (.black, .queen): return "♛"
        case (.black, .rook): return "♜"
        case (.black, .bishop): return "♝"
        case (.black, .knight): return "♞"
        case (.black, .pawn): return "♟"
        }
    }

    static let all: [Piece] = PieceColor.allCases.flatMap { color in
        [PieceKind.king, .queen, .rook, .bishop, .knight, .pawn].map { Piece(color: color, kind: $0) }
    }
}

/// A board square. `file` 0...7 is a...h, `rank` 0...7 is 1...8.
struct Square: Hashable {
    let file: Int
    let rank: Int

    init?(file: Int, rank: Int) {
        guard (0..<8).contains(file), (0..<8).contains(rank) else { return nil }
        self.file = file
        self.rank = rank
    }

    init?(algebraic: Substring) {
        let chars = Array(algebraic)
        guard chars.count == 2,
              let f = chars[0].asciiValue, let r = chars[1].asciiValue,
              (97...104).contains(f), (49...56).contains(r)
        else { return nil }
        self.file = Int(f) - 97
        self.rank = Int(r) - 49
    }

    var index: Int { rank * 8 + file }
    var algebraic: String { "\(Character(UnicodeScalar(UInt8(97 + file))))\(rank + 1)" }

    func offset(_ df: Int, _ dr: Int) -> Square? { Square(file: file + df, rank: rank + dr) }
}

struct UCIMove: Equatable {
    let from: Square
    let to: Square
    let promotion: PieceKind?

    init?(_ text: String) {
        let s = Substring(text)
        guard s.count == 4 || s.count == 5,
              let from = Square(algebraic: s.prefix(2)),
              let to = Square(algebraic: s.dropFirst(2).prefix(2))
        else { return nil }
        self.from = from
        self.to = to
        if s.count == 5 {
            guard let kind = PieceKind(rawValue: Character(s.last!.lowercased())) else { return nil }
            promotion = kind
        } else {
            promotion = nil
        }
    }
}

struct ChessBoard: Equatable {
    /// 64 squares indexed `rank * 8 + file` (a1 = 0, h8 = 63).
    private(set) var squares: [Piece?] = Array(repeating: nil, count: 64)

    init() {}

    /// `ranks[0]` is rank 8 and `ranks[7]` is rank 1; each string runs from file a to h.
    /// Accepts "." / "-" / "_" / " " for empty squares and FEN digits for runs of empty squares.
    init?(ranks: [String]) {
        guard ranks.count == 8 else { return nil }
        for (row, text) in ranks.enumerated() {
            let rank = 7 - row
            var file = 0
            for ch in text {
                if let n = ch.wholeNumberValue, (1...8).contains(n) {
                    file += n
                } else if ".-_ ".contains(ch) {
                    file += 1
                } else if let piece = Piece(fenChar: ch) {
                    guard file < 8 else { return nil }
                    squares[rank * 8 + file] = piece
                    file += 1
                } else {
                    return nil
                }
                if file > 8 { return nil }
            }
            guard file == 8 else { return nil }
        }
    }

    /// Parses the piece-placement field (or a full FEN; only the first field is read).
    init?(fen: String) {
        guard let placement = fen.split(separator: " ").first else { return nil }
        let rows = placement.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        self.init(ranks: rows)
    }

    static let startingPosition = ChessBoard(fen: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")!

    subscript(square: Square) -> Piece? {
        get { squares[square.index] }
        set { squares[square.index] = newValue }
    }

    var placementFEN: String {
        var rows: [String] = []
        for rank in stride(from: 7, through: 0, by: -1) {
            var row = ""
            var empty = 0
            for file in 0..<8 {
                if let piece = squares[rank * 8 + file] {
                    if empty > 0 { row += String(empty); empty = 0 }
                    row.append(piece.fenChar)
                } else {
                    empty += 1
                }
            }
            if empty > 0 { row += String(empty) }
            rows.append(row)
        }
        return rows.joined(separator: "/")
    }

    /// Castling rights inferred from king/rook placement (a recognized photo has no move history).
    var inferredCastlingRights: String {
        var rights = ""
        func at(_ name: Substring) -> Piece? { Square(algebraic: name).flatMap { self[$0] } }
        if at("e1") == Piece(color: .white, kind: .king) {
            if at("h1") == Piece(color: .white, kind: .rook) { rights += "K" }
            if at("a1") == Piece(color: .white, kind: .rook) { rights += "Q" }
        }
        if at("e8") == Piece(color: .black, kind: .king) {
            if at("h8") == Piece(color: .black, kind: .rook) { rights += "k" }
            if at("a8") == Piece(color: .black, kind: .rook) { rights += "q" }
        }
        return rights.isEmpty ? "-" : rights
    }

    func fen(sideToMove: PieceColor) -> String {
        "\(placementFEN) \(sideToMove.fenLetter) \(inferredCastlingRights) - 0 1"
    }

    /// The board rotated 180°, for when the photo was read from the wrong side.
    var rotated: ChessBoard {
        var copy = ChessBoard()
        for i in 0..<64 { copy.squares[63 - i] = squares[i] }
        return copy
    }

    func pieces(of color: PieceColor) -> [(Square, Piece)] {
        (0..<64).compactMap { i -> (Square, Piece)? in
            guard let piece = squares[i], piece.color == color else { return nil }
            return (Square(file: i % 8, rank: i / 8)!, piece)
        }
    }

    func kingSquare(_ color: PieceColor) -> Square? {
        pieces(of: color).first { $0.1.kind == .king }?.0
    }

    func isAttacked(_ target: Square, by attacker: PieceColor) -> Bool {
        func has(_ sq: Square?, _ kinds: Set<PieceKind>) -> Bool {
            guard let sq, let p = self[sq] else { return false }
            return p.color == attacker && kinds.contains(p.kind)
        }
        // Pawns attack diagonally forward, so look one rank "behind" the target from the attacker's view.
        let pawnDir = attacker == .white ? -1 : 1
        if has(target.offset(-1, pawnDir), [.pawn]) || has(target.offset(1, pawnDir), [.pawn]) { return true }

        let knightJumps = [(1, 2), (2, 1), (2, -1), (1, -2), (-1, -2), (-2, -1), (-2, 1), (-1, 2)]
        if knightJumps.contains(where: { has(target.offset($0.0, $0.1), [.knight]) }) { return true }

        let kingSteps = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
        if kingSteps.contains(where: { has(target.offset($0.0, $0.1), [.king]) }) { return true }

        func slides(_ dirs: [(Int, Int)], _ kinds: Set<PieceKind>) -> Bool {
            for (df, dr) in dirs {
                var sq = target.offset(df, dr)
                while let current = sq {
                    if let p = self[current] {
                        if p.color == attacker && kinds.contains(p.kind) { return true }
                        break
                    }
                    sq = current.offset(df, dr)
                }
            }
            return false
        }
        if slides([(1, 0), (-1, 0), (0, 1), (0, -1)], [.rook, .queen]) { return true }
        if slides([(1, 1), (1, -1), (-1, 1), (-1, -1)], [.bishop, .queen]) { return true }
        return false
    }

    func isInCheck(_ color: PieceColor) -> Bool {
        guard let king = kingSquare(color) else { return false }
        return isAttacked(king, by: color.opposite)
    }

    /// Problems that would make the position illegal. Stockfish assumes legal input, so
    /// anything listed here must be fixed before the engine sees the position.
    func validationIssues(sideToMove: PieceColor) -> [String] {
        var issues: [String] = []
        for color in PieceColor.allCases {
            let mine = pieces(of: color)
            let kings = mine.filter { $0.1.kind == .king }.count
            if kings != 1 { issues.append("\(color.displayName) has \(kings) kings (needs exactly 1).") }
            let pawns = mine.filter { $0.1.kind == .pawn }.count
            if pawns > 8 { issues.append("\(color.displayName) has \(pawns) pawns.") }
            if mine.count > 16 { issues.append("\(color.displayName) has \(mine.count) pieces.") }
        }
        for file in 0..<8 {
            for rank in [0, 7] where squares[rank * 8 + file]?.kind == .pawn {
                issues.append("Pawn on \(Square(file: file, rank: rank)!.algebraic) (first/last rank).")
            }
        }
        if issues.isEmpty && isInCheck(sideToMove.opposite) {
            issues.append("\(sideToMove.opposite.displayName) is in check but it's \(sideToMove.displayName)'s move.")
        }
        return issues
    }

    /// Applies a UCI move without legality checks (castling and en passant handled).
    func applying(_ move: UCIMove) -> ChessBoard {
        var next = self
        guard let piece = self[move.from] else { return next }
        next[move.from] = nil
        if piece.kind == .pawn, move.from.file != move.to.file, self[move.to] == nil {
            // En passant: the captured pawn sits beside the moving pawn.
            next[Square(file: move.to.file, rank: move.from.rank)!] = nil
        }
        if piece.kind == .king, abs(move.to.file - move.from.file) == 2 {
            let rank = move.from.rank
            let kingside = move.to.file > move.from.file
            let rookFrom = Square(file: kingside ? 7 : 0, rank: rank)!
            let rookTo = Square(file: kingside ? 5 : 3, rank: rank)!
            next[rookTo] = next[rookFrom]
            next[rookFrom] = nil
        }
        next[move.to] = move.promotion.map { Piece(color: piece.color, kind: $0) } ?? piece
        return next
    }

    /// Short notation such as "Nf3", "exd5", "O-O", "e8=Q+". File disambiguation is
    /// omitted, so two identical pieces able to reach one square read the same.
    func shortNotation(for move: UCIMove) -> String {
        guard let piece = self[move.from] else { return "\(move.from.algebraic)\(move.to.algebraic)" }
        let check = applying(move).isInCheck(piece.color.opposite) ? "+" : ""
        if piece.kind == .king, abs(move.to.file - move.from.file) == 2 {
            return (move.to.file > move.from.file ? "O-O" : "O-O-O") + check
        }
        let isCapture = self[move.to] != nil || (piece.kind == .pawn && move.from.file != move.to.file)
        var text = ""
        if piece.kind == .pawn {
            if isCapture { text += String(move.from.algebraic.prefix(1)) + "x" }
        } else {
            text += String(piece.fenChar).uppercased()
            if isCapture { text += "x" }
        }
        text += move.to.algebraic
        if let promo = move.promotion { text += "=" + String(promo.rawValue).uppercased() }
        return text + check
    }

    /// A sentence suited to text-to-speech, e.g. "Knight g1 to f3, check".
    func spokenDescription(for move: UCIMove) -> String {
        guard let piece = self[move.from] else { return "\(move.from.algebraic) to \(move.to.algebraic)" }
        let check = applying(move).isInCheck(piece.color.opposite) ? ", check" : ""
        if piece.kind == .king, abs(move.to.file - move.from.file) == 2 {
            return (move.to.file > move.from.file ? "Castle kingside" : "Castle queenside") + check
        }
        let captured = self[move.to]
            ?? ((piece.kind == .pawn && move.from.file != move.to.file)
                ? Piece(color: piece.color.opposite, kind: .pawn) : nil)
        var text = "\(piece.kind.name) \(move.from.algebraic)"
        if let captured {
            text += " takes \(captured.kind.name.lowercased()) on \(move.to.algebraic)"
        } else {
            text += " to \(move.to.algebraic)"
        }
        if let promo = move.promotion { text += ", promote to \(promo.name.lowercased())" }
        return text + check
    }

    /// Guesses who just moved by comparing with an earlier position: the colour whose
    /// pieces arrived on new squares. Returns nil when the change is unclear.
    func colorThatMoved(since previous: ChessBoard) -> PieceColor? {
        var arrived: [PieceColor: Int] = [:]
        var changed = 0
        for i in 0..<64 where squares[i] != previous.squares[i] {
            changed += 1
            if let piece = squares[i] { arrived[piece.color, default: 0] += 1 }
        }
        // A normal move changes 2 squares, castling 4, en passant 3. More means a misread.
        guard (2...4).contains(changed) else { return nil }
        let white = arrived[.white, default: 0]
        let black = arrived[.black, default: 0]
        if white > 0 && black == 0 { return .white }
        if black > 0 && white == 0 { return .black }
        return nil
    }
}
