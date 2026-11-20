import MWDATCamera
import MWDATCore
import Observation
import UIKit

/// Owns the connection to Ray-Ban Meta glasses through the Meta Wearables Device Access
/// Toolkit: registration with the Meta AI app, the device session, and the camera stream.
@Observable
@MainActor
final class GlassesManager {
    var registrationState: RegistrationState
    var hasActiveDevice = false
    var sessionState: DeviceSessionState = .idle
    var streamState: StreamState = .stopped
    var latestFrame: UIImage?
    var errorMessage: String?

    var isRegistered: Bool { registrationState == .registered }
    var isStreaming: Bool { streamState == .streaming }
    /// A camera stream exists in any state other than stopped.
    var isCameraActive: Bool { streamState != .stopped }
    var isBusy: Bool {
        sessionState == .starting || sessionState == .stopping
            || streamState == .starting || streamState == .waitingForDevice || streamState == .stopping
    }

    var statusText: String {
        if !isRegistered { return "Not connected to Meta AI" }
        if !hasActiveDevice { return "Put on your glasses" }
        switch streamState {
        case .streaming: return "Streaming from glasses"
        case .paused: return "Stream paused (tap glasses to resume)"
        case .starting, .waitingForDevice: return "Starting camera…"
        case .stopping: return "Stopping…"
        case .stopped: return sessionState == .started ? "Glasses connected" : "Glasses ready"
        }
    }

    /// Called on the main actor with each decoded frame while streaming.
    @ObservationIgnored var onFrame: ((UIImage) -> Void)?

    @ObservationIgnored private let wearables: WearablesInterface
    @ObservationIgnored private let deviceSelector: AutoDeviceSelector
    @ObservationIgnored private var session: DeviceSession?
    @ObservationIgnored private var camera: MWDATCamera.Camera?
    @ObservationIgnored private let sessionTokens = ListenerTokenBag()
    @ObservationIgnored private let streamTokens = ListenerTokenBag()
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init(wearables: WearablesInterface = Wearables.shared) {
        self.wearables = wearables
        self.registrationState = wearables.registrationState
        self.deviceSelector = AutoDeviceSelector(wearables: wearables)

        tasks.append(Task { [weak self] in
            for await state in wearables.registrationStateStream() {
                self?.registrationState = state
            }
        })
        let selector = deviceSelector
        tasks.append(Task { [weak self] in
            for await deviceId in selector.activeDeviceStream() {
                self?.hasActiveDevice = deviceId != nil
            }
        })
    }

    // MARK: Registration

    func connect() {
        guard registrationState != .registering else { return }
        Task {
            do {
                try await wearables.startRegistration()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func disconnect() {
        stopStreaming()
        session?.stop()
        Task {
            do {
                try await wearables.startUnregistration()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Forwards Meta AI callbacks (registration and permission results) to the SDK.
    func handle(url: URL) {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
        else { return }
        Task {
            do {
                _ = try await wearables.handleUrl(url)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: Streaming

    func startStreaming() async {
        guard camera == nil else { return }
        do {
            let session = try await startedSession()
            if try await wearables.checkPermissionStatus(.camera) != .granted {
                // Opens the Meta AI app so the wearer can allow camera access.
                guard try await wearables.requestPermission(.camera) == .granted else {
                    errorMessage = "Camera access on the glasses was not allowed."
                    return
                }
            }
            // Raw frames decode straight to UIImage. The SDK lowers resolution by itself if
            // Bluetooth bandwidth runs short; board tracking copes with that.
            let config = StreamConfiguration(videoCodec: .raw, resolution: .high, frameRate: 15)
            guard let camera = try session.addCamera(config: config) else {
                errorMessage = "Couldn't open the glasses camera. Try again."
                return
            }
            self.camera = camera
            observe(camera.stream)
            streamState = .starting
            camera.stream.start()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopStreaming() {
        guard let camera else { return }
        streamState = .stopping
        camera.stop()
    }

    // MARK: Session

    private func startedSession() async throws -> DeviceSession {
        if let session, session.state == .started { return session }
        session?.stop()
        sessionTokens.clear()

        let session = try wearables.createSession(deviceSelector: deviceSelector)
        self.session = session
        session.statePublisher.listen { [weak self] state in
            Task { @MainActor in self?.handleSessionState(state) }
        }.store(in: sessionTokens)
        session.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.errorMessage = error.localizedDescription }
        }.store(in: sessionTokens)

        sessionState = .starting
        try session.start()
        // Wait up to 30 s for the glasses to accept the session.
        var waited = 0
        while session.state != .started, session.state != .stopped, waited < 300 {
            try await Task.sleep(nanoseconds: 100_000_000)
            waited += 1
        }
        guard session.state == .started else {
            throw GlassesError.sessionDidNotStart
        }
        sessionState = .started
        return session
    }

    private func handleSessionState(_ state: DeviceSessionState) {
        sessionState = state
        if state == .stopped {
            sessionTokens.clear()
            session = nil
            clearStream()
        }
    }

    private func observe(_ stream: MWDATCamera.Stream) {
        stream.statePublisher.listen { [weak self] state in
            Task { @MainActor in self?.handleStreamState(state) }
        }.store(in: streamTokens)

        stream.videoFramePublisher.listen { [weak self] frame in
            guard let image = frame.makeUIImage() else { return }
            Task { @MainActor in
                guard let self else { return }
                self.latestFrame = image
                self.onFrame?(image)
            }
        }.store(in: streamTokens)

        stream.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.errorMessage = error.localizedDescription }
        }.store(in: streamTokens)
    }

    private func handleStreamState(_ state: StreamState) {
        streamState = state
        if state == .stopped { clearStream() }
    }

    private func clearStream() {
        streamTokens.clear()
        camera?.stop()
        camera = nil
        streamState = .stopped
        latestFrame = nil
    }
}

enum GlassesError: LocalizedError {
    case sessionDidNotStart

    var errorDescription: String? {
        "Couldn't connect to the glasses. Make sure they're on, unfolded and connected in the Meta AI app."
    }
}
