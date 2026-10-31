import Foundation

/// What the camera saw on each square, compared with the last confirmed view of the board.
struct SquareObservation {
    /// Per-square appearance change since the reference view, 0–255 scale. Index = `Square.index`.
    var differences: [Float]
    /// Per-square probabilities of [empty, white piece, black piece] in the new view.
    var contents: [[Float]]
}

enum MoveDetection {
    case noChange
    /// One move, or two (yours and the reply) if both happened between looks.
    case moves([UCIMove], ChessPosition)
    /// Something changed but no legal move explains it (hand over the board, misalignment…).
    case unclear
    /// Several moves fit about equally well.
    case ambiguous
}

/// Works out which legal move(s) explain the squares that changed. Pieces are never identified
/// directly: the game is followed from a known position, so only *which* squares changed matters,
/// with an empty/white/black guess per square to break ties. Tuned on simulated noise with a
/// Python prototype of this exact scoring.
enum MoveDetector {
    /// Square changes smaller than this many robust standard deviations are treated as noise.
    static let changeThreshold: Float = 4
    /// Floor for the noise scale (0–255 units) so a perfectly still view can't inflate tiny changes.
    static let noiseFloor: Float = 4

    static func detect(from position: ChessPosition, observation: SquareObservation,
                       playerColor: PieceColor) -> MoveDetection {
        let z = robustZScores(observation.differences)
        guard (z.max() ?? 0) >= changeThreshold else { return .noChange }

        struct Candidate {
            var moves: [UCIMove]
            var position: ChessPosition
            var score: Score
        }
        // Keyed by resulting placement, so different move orders reaching the same board don't
        // count as ambiguity.
        var best: [String: Candidate] = [:]
        func consider(_ moves: [UCIMove], _ result: ChessPosition) {
            guard let fit = Self.score(before: position.board, after: result.board, z: z,
                                       contents: observation.contents, playerColor: playerColor)
            else { return }
            let key = result.board.placementFEN
            if let existing = best[key], existing.score.total >= fit.total { return }
            best[key] = Candidate(moves: moves, position: result, score: fit)
        }

        // Under-promotions are rare and look identical to a queen promotion on camera.
        let firstMoves = position.legalMoves().filter { $0.promotion == nil || $0.promotion == .queen }
        for move in firstMoves {
            consider([move], position.applying(move))
        }

        // If one move doesn't explain everything, try a move and the reply (the wearer may have
        // looked away while both were played).
        let topSingle = best.values.max { $0.score.total < $1.score.total }
        if !(topSingle.map { $0.score.unexplained == 0 && $0.score.evidence >= 3 } ?? false) {
            for first in firstMoves {
                let middle = position.applying(first)
                for second in middle.legalMoves() where second.promotion == nil || second.promotion == .queen {
                    consider([first, second], middle.applying(second))
                }
            }
        }

        let ranked = best.values.sorted { $0.score.total > $1.score.total }
        guard let top = ranked.first, top.score.unexplained == 0, top.score.evidence >= 3 else {
            return .unclear
        }
        let margin: Float = top.moves.count == 1 ? 0.8 : 1.5
        if ranked.count > 1, top.score.total - ranked[1].score.total < margin {
            return .ambiguous
        }
        return .moves(top.moves, top.position)
    }

    /// Finds the move (or move and reply) after which `mismatches(board)` is zero, when exactly one
    /// resulting board fits. Used when comparison with the last confirmed view missed a move but
    /// the current view clearly disagrees with the game.
    static func recover(from position: ChessPosition, playerColor: PieceColor,
                        mismatches: (ChessBoard) -> Int) -> ([UCIMove], ChessPosition)? {
        guard mismatches(position.board) > 0 else { return nil }
        var fits: [String: ([UCIMove], ChessPosition)] = [:]
        let firstMoves = position.legalMoves().filter { $0.promotion == nil || $0.promotion == .queen }
        for move in firstMoves {
            let result = position.applying(move)
            if mismatches(result.board) == 0 { fits[result.board.placementFEN] = ([move], result) }
        }
        if fits.isEmpty {
            for first in firstMoves {
                let middle = position.applying(first)
                for second in middle.legalMoves() where second.promotion == nil || second.promotion == .queen {
                    let result = middle.applying(second)
                    if mismatches(result.board) == 0, fits[result.board.placementFEN] == nil {
                        fits[result.board.placementFEN] = ([first, second], result)
                    }
                }
            }
        }
        return fits.count == 1 ? fits.values.first : nil
    }

    private struct Score {
        var total: Float
        var evidence: Float
        var unexplained: Int
    }

    private static func score(before: ChessBoard, after: ChessBoard, z: [Float], contents: [[Float]],
                              playerColor: PieceColor) -> Score? {
        let changed = (0..<64).filter { before.squares[$0] != after.squares[$0] }
        guard !changed.isEmpty else { return nil }

        // Every square the hypothesis changes should show a change on camera.
        let evidence = changed.reduce(Float(0)) { $0 + min(max(z[$1], -2), 8) } / Float(changed.count)
        let weak = changed.reduce(Float(0)) { $0 + max(0, 2.5 - z[$1]) } * 0.5

        // Every square should look like what the hypothesis says is on it.
        var mismatch: Float = 0
        for i in 0..<64 {
            let expected: Int
            switch after.squares[i]?.color {
            case nil: expected = 0
            case .white?: expected = 1
            case .black?: expected = 2
            }
            mismatch += 1 - contents[i][expected]
        }

        // Strong changes the hypothesis doesn't explain. Squares just beyond a changed square
        // (from the wearer's seat) are excused: a tall piece's silhouette spills onto them.
        var excused = Set(changed)
        let away = playerColor == .white ? 1 : -1
        for i in changed {
            let square = Square(index: i)
            for step in 1...2 {
                if let spill = square.offset(0, away * step) { excused.insert(spill.index) }
            }
        }
        let unexplained = (0..<64).filter { z[$0] > 5 && !excused.contains($0) }.count

        let total = evidence - weak - 1.5 * mismatch - 2 * Float(unexplained)
        return Score(total: total, evidence: evidence, unexplained: unexplained)
    }

    static func robustZScores(_ values: [Float]) -> [Float] {
        let median = values.median
        let mad = values.map { abs($0 - median) }.median * 1.4826
        let scale = max(mad, noiseFloor)
        return values.map { ($0 - median) / scale }
    }
}

extension Array where Element == Float {
    var median: Float {
        guard !isEmpty else { return 0 }
        let sorted = self.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
}
