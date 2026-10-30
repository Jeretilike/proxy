import SwiftUI

struct ContentView: View {
    @Bindable var glasses: GlassesManager
    @Bindable var game: GameViewModel

    @AppStorage(SettingsKey.handsFree) private var handsFree = true
    @AppStorage(SettingsKey.playerColor) private var playerColorRaw = PieceColor.white.rawValue

    @State private var showSettings = false
    @State private var editingSquare: Square?
    @State private var showFENEditor = false
    @State private var fenDraft = ""

    private var playerColor: PieceColor { PieceColor(rawValue: playerColorRaw) ?? .white }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    glassesCard
                    gameControls
                    suggestionCard
                    boardCard
                }
                .padding()
            }
            .navigationTitle("ChessLens")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView(glasses: glasses) }
            .sheet(isPresented: Binding(
                get: { game.calibration != nil },
                set: { if !$0 { game.calibration = nil } }
            )) {
                CalibrationView(game: game)
            }
            .sheet(isPresented: $showFENEditor) { fenEditor }
            .confirmationDialog(
                editingSquare.map { "Piece on \($0.algebraic)" } ?? "",
                isPresented: Binding(get: { editingSquare != nil }, set: { if !$0 { editingSquare = nil } }),
                titleVisibility: .visible
            ) {
                ForEach(Piece.all, id: \.self) { piece in
                    Button("\(piece.symbol) \(piece.color.displayName) \(piece.kind.name.lowercased())") {
                        if let square = editingSquare { game.setPiece(piece, at: square) }
                    }
                }
                Button("Empty square", role: .destructive) {
                    if let square = editingSquare { game.setPiece(nil, at: square) }
                }
            }
            .alert("Glasses", isPresented: Binding(
                get: { glasses.errorMessage != nil },
                set: { if !$0 { glasses.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(glasses.errorMessage ?? "")
            }
            .onChange(of: glasses.isStreaming) { _, streaming in
                // Keep the phone awake while the glasses watch the board.
                UIApplication.shared.isIdleTimerDisabled = streaming
            }
        }
    }

    // MARK: Sections

    private var glassesCard: some View {
        card {
            HStack {
                Image(systemName: "eyeglasses").font(.title2)
                VStack(alignment: .leading) {
                    Text("Ray-Ban Meta").font(.headline)
                    Text(glasses.statusText).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                if glasses.isBusy { ProgressView() }
            }

            if let frame = glasses.latestFrame {
                Image(uiImage: frame)
                    .resizable()
                    .scaledToFit()
                    .overlay { boardOutline }
                    .frame(maxHeight: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            if !glasses.isRegistered {
                Button { glasses.connect() } label: {
                    Label("Connect glasses via Meta AI", systemImage: "link").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(glasses.registrationState == .registering)
            } else if glasses.isCameraActive {
                Button(role: .destructive) { glasses.stopStreaming() } label: {
                    Label("Stop glasses camera", systemImage: "stop.circle").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else {
                Button { Task { await glasses.startStreaming() } } label: {
                    Label("Start glasses camera", systemImage: "video").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(glasses.isBusy)
            }

            Toggle("Hands-free: follow moves and speak", isOn: $handsFree)
                .font(.subheadline)
        }
    }

    /// The tracked board outline drawn over the live preview.
    private var boardOutline: some View {
        GeometryReader { geo in
            if let corners = game.corners {
                Path { path in
                    let points = corners.points.map { CGPoint(x: $0.x * geo.size.width, y: $0.y * geo.size.height) }
                    path.move(to: points[0])
                    points.dropFirst().forEach { path.addLine(to: $0) }
                    path.closeSubpath()
                }
                .stroke(Color.yellow, lineWidth: 2)
            }
        }
    }

    private var gameControls: some View {
        VStack(spacing: 10) {
            Button {
                Task { await game.beginCalibration() }
            } label: {
                Label(game.isCalibrated ? "Recalibrate board" : "Calibrate board", systemImage: "viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!glasses.isStreaming)

            HStack {
                Button { game.checkNow() } label: {
                    Label("Check now", systemImage: "eye").frame(maxWidth: .infinity)
                }
                .disabled(!game.isCalibrated || !glasses.isStreaming)
                Button { game.undo() } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward").frame(maxWidth: .infinity)
                }
                .disabled(game.history.isEmpty)
                Button(role: .destructive) { game.newGame() } label: {
                    Label("New game", systemImage: "plus.circle").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .font(.footnote)

            Text(game.statusText)
                .font(.footnote)
                .foregroundStyle(isProblem ? .red : .secondary)
                .multilineTextAlignment(.center)
            if let event = game.lastEvent {
                Text(event).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var isProblem: Bool {
        switch game.state {
        case .lost, .unsure: return true
        default: return false
        }
    }

    @ViewBuilder
    private var suggestionCard: some View {
        if let best = game.suggestionText {
            card {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Best move for \(game.position.sideToMove.displayName)")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(best).font(.system(size: 40, weight: .bold, design: .rounded))
                        if let spoken = game.spokenSuggestion {
                            Text(spoken).font(.subheadline)
                        }
                    }
                    Spacer()
                    if let eval = game.evaluationText {
                        Text(eval)
                            .font(.title3.monospacedDigit().weight(.semibold))
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(.thinMaterial, in: Capsule())
                    }
                }
                if let line = game.lineText {
                    Text("Line: \(line)").font(.footnote).foregroundStyle(.secondary)
                }
                if let depth = game.suggestion?.depth {
                    Text("Stockfish depth \(depth)").font(.caption2).foregroundStyle(.tertiary)
                }
                Button { game.speakAgain() } label: {
                    Label("Say it again", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var boardCard: some View {
        card {
            Text("Game position. Tap a piece then its square to enter a move; long-press to change a piece.")
                .font(.caption).foregroundStyle(.secondary)
            BoardView(
                board: game.position.board,
                bottomColor: playerColor,
                highlight: game.suggestion.flatMap { UCIMove($0.bestMove) },
                selected: game.selectedSquare,
                onTap: { game.tap($0) },
                onLongPress: { editingSquare = $0 }
            )
            HStack {
                Button { game.toggleSideToMove() } label: {
                    Label("\(game.position.sideToMove.displayName) to move", systemImage: "arrow.left.arrow.right")
                }
                Spacer()
                Button {
                    fenDraft = game.position.fen
                    showFENEditor = true
                } label: {
                    Label("FEN", systemImage: "square.and.pencil")
                }
            }
            .buttonStyle(.bordered)
            .font(.footnote)

            if !game.moveLog.isEmpty {
                Text(game.moveLog.joined(separator: " "))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var fenEditor: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("FEN", text: $fenDraft, axis: .vertical)
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Paste or edit a position. After changing the board, the app re-anchors on the current camera view.")
                }
            }
            .navigationTitle("Position (FEN)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showFENEditor = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        game.applyFEN(fenDraft)
                        showFENEditor = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}
