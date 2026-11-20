package com.chesslens.android.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.chesslens.android.screen.ScreenWatchService
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.chesslens.android.CrashReport
import com.chesslens.android.GameViewModel
import com.chesslens.android.glasses.GlassesSdk
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    private var started = false

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }

    // Screen reading: notification permission (for the "running" notice), then Android's
    // screen-capture consent, then the service starts and ChessLens steps aside.
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenWatchService.start(this, result.resultCode, data)
            Toast.makeText(this, "ChessLens is watching. Open your chess app.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Screen reading needs permission to capture the screen.", Toast.LENGTH_LONG).show()
        }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askScreenCapture() }

    /** Starts reading the chess board from the screen (asks for what's needed first). */
    fun startScreenWatch() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow ChessLens to display over other apps, then come back and tap Start again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askScreenCapture()
    }

    private fun askScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // The whole screen (not a single app), so the outlines line up with the board.
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        screenCapture.launch(intent)
    }

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val glassesPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            permissionContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
            permissionContinuation = null
        }

    /** Asks the Meta AI app for a glasses permission (camera) and waits for the answer. */
    suspend fun requestGlassesPermission(permission: Permission): PermissionStatus = permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
            glassesPermission.launch(permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) start()
        else bluetoothPermission.launch(needed)
    }

    /** The Meta Wearables SDK is initialised once Bluetooth permission has been answered. */
    private fun start() {
        if (started) return
        started = true
        // The glasses toolkit may fail (no Meta AI app, unsupported phone…); the app must still open.
        try {
            Wearables.initialize(this)
                .onSuccess { GlassesSdk.ready = true }
                .onFailure { error, _ ->
                    GlassesSdk.problem = error.description
                    Log.e("ChessLens", "Failed to initialize Meta Wearables SDK: ${error.description}")
                }
        } catch (t: Throwable) {
            GlassesSdk.problem = t.message ?: t.javaClass.simpleName
            Log.e("ChessLens", "Meta Wearables SDK crashed while starting", t)
        }
        val crash = CrashReport.take(this)
        val viewModel: GameViewModel by viewModels()
        setContent {
            ChessLensTheme {
                MainScreen(
                    game = viewModel,
                    crashReport = crash,
                    startScreenWatch = ::startScreenWatch,
                    activity = this,
                    requestGlassesPermission = ::requestGlassesPermission,
                    keepScreenOn = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                )
            }
        }
    }
}
