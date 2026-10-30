import Foundation

/// A full game state (board, side to move, castling, en passant) with legal move generation.
/// The move generator mirrors a Python prototype that was checked against standard perft
/// counts (start position, Kiwipete and other castling / en passant / promotion test positions).
struct ChessPosition: Equatable {
    var board: ChessBoard
    var sideToMove: PieceColor
    var castlingRights: Set<Character>
    var enPassant: Square?
    var halfmoveClock = 0
    var fullmoveNumber = 1

    static let start = ChessPosition(board: .startingPosition, sideToMove: .white)

    /// Castling rights are inferred from where the kings and rooks stand.
    init(board: ChessBoard, sideToMove: PieceColor) {
        self.board = board
        self.sideToMove = sideToMove
        let rights = board.inferredCastlingRights
        castlingRights = rights == "-" ? [] : Set(rights)
        enPassant = nil
    }

    var fen: String {
        let castling = String("KQkq".filter { castlingRights.contains($0) })
        return [
            board.placementFEN,
            sideToMove.fenLetter,
            castling.isEmpty ? "-" : castling,
            enPassant?.algebraic ?? "-",
            String(halfmoveClock),
            String(fullmoveNumber),
        ].joined(separator: " ")
    }

    func legalMoves() -> [UCIMove] {
        pseudoLegalMoves().filter { move in
            let next = board.applying(move)
            guard let king = next.kingSquare(sideToMove) else { return false }
            return !next.isAttacked(king, by: sideToMove.opposite)
        }
    }

    func applying(_ move: UCIMove) -> ChessPosition {
        guard let piece = board[move.from] else { return self }
        var next = self
        let isCapture = board[move.to] != nil || (piece.kind == .pawn && move.from.file != move.to.file)
        next.board = board.applying(move)

        next.enPassant = nil
        if piece.kind == .pawn, abs(move.to.rank - move.from.rank) == 2 {
            next.enPassant = Square(file: move.from.file, rank: (move.from.rank + move.to.rank) / 2)
        }

        if piece.kind == .king {
            let lost: [Character] = piece.color == .white ? ["K", "Q"] : ["k", "q"]
            next.castlingRights.subtract(lost)
        }
        for (corner, right) in Self.rookCorners where move.from == corner || move.to == corner {
            next.castlingRights.remove(right)
        }

        next.halfmoveClock = (piece.kind == .pawn || isCapture) ? 0 : halfmoveClock + 1
        if sideToMove == .black { next.fullmoveNumber += 1 }
        next.sideToMove = sideToMove.opposite
        return next
    }

    // MARK: Move generation

    private static let knightSteps = [(1, 2), (2, 1), (2, -1), (1, -2), (-1, -2), (-2, -1), (-2, 1), (-1, 2)]
    private static let kingSteps = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
    private static let rookDirections = [(1, 0), (-1, 0), (0, 1), (0, -1)]
    private static let bishopDirections = [(1, 1), (1, -1), (-1, 1), (-1, -1)]
    private static let rookCorners: [(Square, Character)] = [
        (Square(file: 0, rank: 0)!, "Q"),
        (Square(file: 7, rank: 0)!, "K"),
        (Square(file: 0, rank: 7)!, "q"),
        (Square(file: 7, rank: 7)!, "k"),
    ]

    private func pseudoLegalMoves() -> [UCIMove] {
        var moves: [UCIMove] = []
        let us = sideToMove
        let them = us.opposite

        for (from, piece) in board.pieces(of: us) {
            switch piece.kind {
            case .pawn:
                let direction = us == .white ? 1 : -1
                let startRank = us == .white ? 1 : 6
                let lastRank = us == .white ? 7 : 0
                func add(_ to: Square) {
                    if to.rank == lastRank {
                        for promotion in [PieceKind.queen, .rook, .bishop, .knight] {
                            moves.append(UCIMove(from: from, to: to, promotion: promotion))
                        }
                    } else {
                        moves.append(UCIMove(from: from, to: to))
                    }
                }
                if let one = from.offset(0, direction), board[one] == nil {
                    add(one)
                    if from.rank == startRank, let two = from.offset(0, 2 * direction), board[two] == nil {
                        moves.append(UCIMove(from: from, to: two))
                    }
                }
                for df in [-1, 1] {
                    guard let to = from.offset(df, direction) else { continue }
                    if board[to]?.color == them || to == enPassant { add(to) }
                }

            case .knight, .king:
                let steps = piece.kind == .knight ? Self.knightSteps : Self.kingSteps
                for (df, dr) in steps {
                    if let to = from.offset(df, dr), board[to]?.color != us {
                        moves.append(UCIMove(from: from, to: to))
                    }
                }

            case .bishop, .rook, .queen:
                let directions: [(Int, Int)]
                switch piece.kind {
                case .rook: directions = Self.rookDirections
                case .bishop: directions = Self.bishopDirections
                default: directions = Self.rookDirections + Self.bishopDirections
                }
                for (df, dr) in directions {
                    var target = from.offset(df, dr)
                    while let to = target {
                        if let occupant = board[to] {
                            if occupant.color == them { moves.append(UCIMove(from: from, to: to)) }
                            break
                        }
                        moves.append(UCIMove(from: from, to: to))
                        target = to.offset(df, dr)
                    }
                }
            }
        }

        // Castling: king and rook unmoved (per rights), path empty, king not passing through check.
        let rank = us == .white ? 0 : 7
        func square(_ file: Int) -> Square { Square(file: file, rank: rank)! }
        let king = Piece(color: us, kind: .king)
        let rook = Piece(color: us, kind: .rook)
        if board[square(4)] == king, !board.isAttacked(square(4), by: them) {
            let kingside: Character = us == .white ? "K" : "k"
            let queenside: Character = us == .white ? "Q" : "q"
            if castlingRights.contains(kingside), board[square(7)] == rook,
               board[square(5)] == nil, board[square(6)] == nil,
               !board.isAttacked(square(5), by: them), !board.isAttacked(square(6), by: them) {
                moves.append(UCIMove(from: square(4), to: square(6)))
            }
            if castlingRights.contains(queenside), board[square(0)] == rook,
               board[square(1)] == nil, board[square(2)] == nil, board[square(3)] == nil,
               !board.isAttacked(square(3), by: them), !board.isAttacked(square(2), by: them) {
                moves.append(UCIMove(from: square(4), to: square(2)))
            }
        }
        return moves
    }
}
