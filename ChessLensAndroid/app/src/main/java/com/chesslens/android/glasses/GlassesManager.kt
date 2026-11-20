package com.chesslens.android.glasses

import android.app.Activity
import android.graphics.Bitmap
import com.chesslens.android.vision.Frame
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether Meta's glasses toolkit started. When it didn't (Meta AI app missing, an unsupported
 * phone…), the rest of the app keeps working and Screen mode is unaffected.
 */
object GlassesSdk {
    @Volatile var ready = false
    @Volatile var problem: String? = null
}

/**
 * Owns the connection to Ray-Ban Meta glasses through the Meta Wearables Device Access Toolkit:
 * registration with the Meta AI app, the device session, and the camera stream.
 */
class GlassesManager(private val scope: CoroutineScope) {
    val registrationState: StateFlow<RegistrationState> =
        if (GlassesSdk.ready) runCatching { Wearables.registrationState }.getOrElse { MutableStateFlow(RegistrationState.UNAVAILABLE) }
        else MutableStateFlow(RegistrationState.UNAVAILABLE)
    val sessionState = MutableStateFlow(DeviceSessionState.IDLE)
    val streamState = MutableStateFlow(StreamState.STOPPED)
    val hasActiveDevice = MutableStateFlow(false)
    val preview = MutableStateFlow<Bitmap?>(null)
    val error = MutableStateFlow<String?>(
        if (GlassesSdk.ready) null else "Glasses connection isn't available: ${GlassesSdk.problem ?: "Meta's toolkit didn't start"}. Is the Meta AI app installed? Screen mode still works.",
    )

    /** Latest frame (grayscale), for checks triggered from the UI. */
    @Volatile var latestFrame: Frame? = null
        private set

    /** Called on a background thread with each decoded frame while streaming. */
    var onFrame: ((Frame) -> Unit)? = null

    private val selector by lazy { AutoDeviceSelector() }
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private val jobs = mutableListOf<Job>()
    private var lastPreview = 0L

    init {
        if (GlassesSdk.ready) {
            scope.launch {
                try {
                    selector.activeDeviceFlow().collect { hasActiveDevice.value = it != null }
                } catch (e: Exception) {
                    error.value = "Couldn't watch for glasses: ${e.message}"
                }
            }
        }
    }

    val isRegistered: Boolean get() = registrationState.value == RegistrationState.REGISTERED
    val isStreaming: Boolean get() = streamState.value == StreamState.STREAMING

    fun connect(activity: Activity) {
        if (!GlassesSdk.ready) return
        try {
            Wearables.startRegistration(activity)
        } catch (e: Exception) {
            error.value = "Couldn't open Meta AI to connect: ${e.message}"
        }
    }

    fun disconnect(activity: Activity) {
        if (!GlassesSdk.ready) return
        try {
            stopStreaming()
            session?.stop()
            Wearables.startUnregistration(activity)
        } catch (e: Exception) {
            error.value = "Couldn't disconnect: ${e.message}"
        }
    }

    /**
     * Starts the session if needed, makes sure camera access is granted (asking through the Meta AI
     * app via [requestPermission]), then starts the camera stream.
     */
    suspend fun startStreaming(requestPermission: suspend (Permission) -> PermissionStatus) {
        if (!GlassesSdk.ready) return
        try {
            startStreamingUnsafe(requestPermission)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = "The glasses camera didn't start: ${e.message}"
        }
    }

    private suspend fun startStreamingUnsafe(requestPermission: suspend (Permission) -> PermissionStatus) {
        if (camera != null) return
        val current = startedSession() ?: return
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (status != PermissionStatus.Granted && requestPermission(Permission.CAMERA) != PermissionStatus.Granted) {
            error.value = "Camera access on the glasses was not allowed."
            return
        }
        // Raw (uncompressed) frames: the brightness plane is used directly for board detection.
        current.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15, compressVideo = false))
            .onSuccess { added ->
                camera = added
                jobs += scope.launch { added.stream.state.collect { state -> onStreamState(state) } }
                jobs += scope.launch(Dispatchers.Default) { added.stream.videoStream.collect { handleFrame(it) } }
                jobs += scope.launch { added.stream.errorStream.collect { e -> error.value = e.description } }
                streamState.value = StreamState.STARTING
                added.stream.start().onFailure { e, _ -> error.value = e.description }
            }
            .onFailure { e, _ -> error.value = e.description }
    }

    fun stopStreaming() {
        val current = camera ?: return
        streamState.value = StreamState.STOPPING
        current.stop()
    }

    private fun onStreamState(state: StreamState) {
        streamState.value = state
        if (state == StreamState.STOPPED || state == StreamState.CLOSED) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            camera = null
            session?.removeCamera()
            latestFrame = null
            preview.value = null
            streamState.value = StreamState.STOPPED
        }
    }

    private suspend fun startedSession(): DeviceSession? {
        session?.let { if (it.state.value == DeviceSessionState.STARTED) return it }
        session?.stop()
        val result = Wearables.createSession(selector)
        val created = result.getOrNull() ?: run {
            error.value = result.errorOrNull()?.description ?: "Couldn't start a session with the glasses."
            return null
        }
        session = created
        scope.launch {
            created.state.collect { state ->
                sessionState.value = state
                if (state == DeviceSessionState.STOPPED && session === created) session = null
            }
        }
        scope.launch { created.errors.collect { e -> error.value = e.description } }
        created.start()
        val started = withTimeoutOrNull(30_000) { created.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
        if (started != DeviceSessionState.STARTED) {
            error.value = "Couldn't connect to the glasses. Make sure they're on, unfolded and connected in the Meta AI app."
            return null
        }
        return created
    }

    private fun handleFrame(videoFrame: VideoFrame) {
        if (videoFrame.isCompressed) return
        val frame = toFrame(videoFrame) ?: return
        latestFrame = frame
        onFrame?.invoke(frame)
        val now = System.currentTimeMillis()
        if (now - lastPreview >= 100) { // ~10 fps preview
            lastPreview = now
            preview.value = toBitmap(frame)
        }
    }

    /**
     * The brightness (Y) plane of a raw YUV 4:2:0 frame. Y comes first in every common layout
     * (I420, NV12, NV21); the row stride is derived from the buffer size.
     */
    private fun toFrame(f: VideoFrame): Frame? {
        val w = f.width
        val h = f.height
        if (w <= 0 || h <= 0) return null
        val buffer = f.buffer.duplicate()
        val total = buffer.remaining()
        val stride = maxOf(w, (total * 2L / (3L * h)).toInt())
        if (total < stride * h) return null
        val luma = ByteArray(w * h)
        val start = buffer.position()
        for (row in 0 until h) {
            buffer.position(start + row * stride)
            buffer.get(luma, row * w, w)
        }
        return Frame(w, h, luma)
    }

    private fun toBitmap(frame: Frame): Bitmap {
        val pixels = IntArray(frame.width * frame.height) {
            val v = frame.luma[it].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }
}
