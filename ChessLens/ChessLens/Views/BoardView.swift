import SwiftUI

/// An 8×8 board drawn from the player's side, with the suggested move highlighted.
/// Tapping a square reports it so the caller can correct misread pieces.
struct BoardView: View {
    let board: ChessBoard
    let bottomColor: PieceColor
    var highlight: UCIMove?
    var onTap: ((Square) -> Void)?

    private let light = Color(red: 0.93, green: 0.85, blue: 0.72)
    private let dark = Color(red: 0.69, green: 0.53, blue: 0.39)
    private let accent = Color(red: 0.98, green: 0.80, blue: 0.20)

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height) / 8
            VStack(spacing: 0) {
                ForEach(0..<8, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<8, id: \.self) { column in
                            cell(square(row: row, column: column), size: size)
                        }
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func square(row: Int, column: Int) -> Square {
        bottomColor == .white
            ? Square(file: column, rank: 7 - row)!
            : Square(file: 7 - column, rank: row)!
    }

    private func cell(_ square: Square, size: CGFloat) -> some View {
        let isLight = (square.file + square.rank) % 2 == 1
        let isHighlighted = highlight.map { $0.from == square || $0.to == square } ?? false
        return ZStack {
            Rectangle().fill(isLight ? light : dark)
            if isHighlighted {
                Rectangle().fill(accent.opacity(0.55))
            }
            if let piece = board[square] {
                Text(piece.symbol)
                    .font(.system(size: size * 0.78))
                    .foregroundStyle(.black)
            }
            coordinateLabels(square, size: size, isLight: isLight)
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .onTapGesture { onTap?(square) }
        .accessibilityLabel("\(square.algebraic), \(board[square].map { "\($0.color.displayName) \($0.kind.name)" } ?? "empty")")
    }

    @ViewBuilder
    private func coordinateLabels(_ square: Square, size: CGFloat, isLight: Bool) -> some View {
        let bottomRank = bottomColor == .white ? 0 : 7
        let leftFile = bottomColor == .white ? 0 : 7
        let color = isLight ? dark : light
        if square.rank == bottomRank {
            Text(String(square.algebraic.prefix(1)))
                .font(.system(size: size * 0.2, weight: .semibold))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(2)
        }
        if square.file == leftFile {
            Text(String(square.rank + 1))
                .font(.system(size: size * 0.2, weight: .semibold))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(2)
        }
    }
}
