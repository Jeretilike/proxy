import SwiftUI

/// Shows a frame from the glasses with four draggable corner handles. The wearer places them on
/// the corners of the 64 squares so the app knows where each square is.
struct CalibrationView: View {
    @Bindable var game: GameViewModel
    @Environment(\.dismiss) private var dismiss

    private let labels = ["a1", "h1", "h8", "a8"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("Drag each dot onto that corner of the 64 squares (the playing area, not the outer border). The grid should line up with the squares.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                if let draft = game.calibration {
                    editor(draft)
                }

                HStack {
                    Button {
                        game.refreshCalibrationFrame()
                    } label: {
                        Label("New frame", systemImage: "arrow.clockwise")
                    }
                    Button {
                        Task { await game.detectCornersAgain() }
                    } label: {
                        Label("Auto-detect", systemImage: "viewfinder")
                    }
                }
                .buttonStyle(.bordered)

                Text("The position on the board must match the one shown in the app (normally the starting position).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }
            .padding(.vertical)
            .navigationTitle("Calibrate board")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        game.calibration = nil
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") {
                        guard let draft = game.calibration else { return }
                        Task { await game.finishCalibration(draft) }
                    }
                }
            }
        }
    }

    private func editor(_ draft: GameViewModel.CalibrationDraft) -> some View {
        GeometryReader { geo in
            let imageSize = CGSize(width: draft.frame.width, height: draft.frame.height)
            let scale = min(geo.size.width / imageSize.width, geo.size.height / imageSize.height)
            let shown = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)

            ZStack(alignment: .topLeading) {
                Image(decorative: draft.frame, scale: 1)
                    .resizable()
                    .frame(width: shown.width, height: shown.height)

                grid(draft.corners, in: shown)
                    .stroke(Color.yellow.opacity(0.8), lineWidth: 1)

                ForEach(0..<4, id: \.self) { index in
                    handle(index, corners: draft.corners, in: shown)
                }
            }
            .frame(width: shown.width, height: shown.height)
            .coordinateSpace(name: "calibration")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func grid(_ corners: BoardCorners, in size: CGSize) -> Path {
        func point(_ u: CGFloat, _ v: CGFloat) -> CGPoint {
            let p = corners.point(u: u, v: v)
            return CGPoint(x: p.x * size.width, y: p.y * size.height)
        }
        var path = Path()
        for i in 0...8 {
            let t = CGFloat(i) / 8
            path.move(to: point(t, 0))
            path.addLine(to: point(t, 1))
            path.move(to: point(0, t))
            path.addLine(to: point(1, t))
        }
        return path
    }

    private func handle(_ index: Int, corners: BoardCorners, in size: CGSize) -> some View {
        let p = corners.points[index]
        return ZStack {
            Circle().fill(Color.blue.opacity(0.35)).frame(width: 44, height: 44)
            Circle().fill(Color.blue).frame(width: 10, height: 10)
            Text(labels[index])
                .font(.caption.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 4)
                .background(Color.blue, in: Capsule())
                .offset(y: -30)
        }
        .position(x: p.x * size.width, y: p.y * size.height)
        .gesture(
            DragGesture(coordinateSpace: .named("calibration"))
                .onChanged { value in
                    guard var draft = game.calibration else { return }
                    var points = draft.corners.points
                    points[index] = CGPoint(
                        x: min(max(value.location.x / size.width, 0), 1),
                        y: min(max(value.location.y / size.height, 0), 1)
                    )
                    draft.corners = BoardCorners(points)
                    game.calibration = draft
                }
        )
    }
}
