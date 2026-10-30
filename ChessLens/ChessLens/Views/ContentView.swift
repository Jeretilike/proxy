import PhotosUI
import SwiftUI

struct ContentView: View {
    @Bindable var glasses: GlassesManager
    @Bindable var analyzer: AnalyzerViewModel

    @AppStorage(SettingsKey.handsFree) private var handsFree = true
    @AppStorage(SettingsKey.playerColor) private var playerColorRaw = PieceColor.white.rawValue

    @State private var showSettings = false
    @State private var showCamera = false
    @State private var photoItem: PhotosPickerItem?
    @State private var editingSquare: Square?
    @State private var showFENEditor = false
    @State private var fenDraft = ""

    private var playerColor: PieceColor { PieceColor(rawValue: playerColorRaw) ?? .white }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    glassesCard
                    analyzeControls
                    resultCard
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
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in Task { await analyzer.analyze(image: image) } }
                    .ignoresSafeArea()
            }
            .sheet(isPresented: $showFENEditor) { fenEditor }
            .confirmationDialog(
                editingSquare.map { "Piece on \($0.algebraic)" } ?? "",
                isPresented: Binding(get: { editingSquare != nil }, set: { if !$0 { editingSquare = nil } }),
                titleVisibility: .visible
            ) {
                ForEach(Piece.all, id: \.self) { piece in
                    Button("\(piece.symbol) \(piece.color.displayName) \(piece.kind.name.lowercased())") {
                        if let square = editingSquare { analyzer.setPiece(piece, at: square) }
                    }
                }
                Button("Empty square", role: .destructive) {
                    if let square = editingSquare { analyzer.setPiece(nil, at: square) }
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
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        await analyzer.analyze(image: image)
                    }
                    photoItem = nil
                }
            }
            .onChange(of: glasses.isStreaming) { _, streaming in
                // Keep the phone awake while the glasses feed hands-free analysis.
                UIApplication.shared.isIdleTimerDisabled = streaming
                if streaming { analyzer.resetHandsFree() }
            }
            .onChange(of: playerColorRaw) { _, _ in analyzer.resetHandsFree() }
        }
    }

    // MARK: Sections

    private var glassesCard: some View {
        card {
            HStack {
                Image(systemName: "eyeglasses")
                    .font(.title2)
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
                    .frame(maxHeight: 220)
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

            Toggle("Hands-free: speak the best move automatically", isOn: $handsFree)
                .font(.subheadline)
        }
    }

    private var analyzeControls: some View {
        VStack(spacing: 10) {
            Button {
                Task { await analyzer.analyzeFromGlasses() }
            } label: {
                Label("Analyze from glasses", systemImage: "sparkle.magnifyingglass")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!glasses.isStreaming || analyzer.isWorking)

            HStack {
                Button { showCamera = true } label: {
                    Label("iPhone camera", systemImage: "camera").frame(maxWidth: .infinity)
                }
                .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label("Photo", systemImage: "photo").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .disabled(analyzer.isWorking)

            HStack(spacing: 8) {
                if analyzer.isWorking { ProgressView() }
                Text(analyzer.statusText)
                    .font(.footnote)
                    .foregroundStyle(isFailure ? .red : .secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var isFailure: Bool {
        if case .failed = analyzer.phase { return true }
        return false
    }

    @ViewBuilder
    private var resultCard: some View {
        if let best = analyzer.bestMoveText {
            card {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Best move for \(analyzer.sideToMove.displayName)")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(best).font(.system(size: 40, weight: .bold, design: .rounded))
                        if let spoken = analyzer.spokenMoveText {
                            Text(spoken).font(.subheadline)
                        }
                    }
                    Spacer()
                    if let eval = analyzer.evaluationText {
                        Text(eval)
                            .font(.title3.monospacedDigit().weight(.semibold))
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(.thinMaterial, in: Capsule())
                    }
                }
                if let line = analyzer.lineText {
                    Text("Line: \(line)").font(.footnote).foregroundStyle(.secondary)
                }
                if let depth = analyzer.analysis?.depth {
                    Text("Stockfish depth \(depth)").font(.caption2).foregroundStyle(.tertiary)
                }
                Button { analyzer.speakAgain() } label: {
                    Label("Say it again", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    @ViewBuilder
    private var boardCard: some View {
        if let board = analyzer.board {
            card {
                Text("Recognized position — tap a square to fix it")
                    .font(.caption).foregroundStyle(.secondary)
                BoardView(
                    board: board,
                    bottomColor: playerColor,
                    highlight: analyzer.analysis.flatMap { UCIMove($0.bestMove) },
                    onTap: { editingSquare = $0 }
                )
                HStack {
                    Button { analyzer.toggleSideToMove() } label: {
                        Label("\(analyzer.sideToMove.displayName) to move", systemImage: "arrow.left.arrow.right")
                    }
                    Spacer()
                    Button { analyzer.rotateBoard() } label: {
                        Label("Rotate", systemImage: "arrow.triangle.2.circlepath")
                    }
                    Button {
                        fenDraft = analyzer.fen ?? ""
                        showFENEditor = true
                    } label: {
                        Label("FEN", systemImage: "square.and.pencil")
                    }
                }
                .buttonStyle(.bordered)
                .font(.footnote)
                .disabled(analyzer.isWorking)

                if !analyzer.recognitionNotes.isEmpty {
                    Text(analyzer.recognitionNotes).font(.caption).foregroundStyle(.secondary)
                }
                if let photo = analyzer.photo {
                    DisclosureGroup("Photo used") {
                        Image(uiImage: photo).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .font(.footnote)
                }
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
                    Text("Edit the position directly, then tap Apply to re-run Stockfish.")
                }
            }
            .navigationTitle("Position (FEN)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showFENEditor = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        analyzer.applyFEN(fenDraft)
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
