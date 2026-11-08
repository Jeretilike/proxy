package com.chesslens.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.chesslens.android.GameViewModel
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
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("ChessLens", "Failed to initialize Meta Wearables SDK: ${error.description}")
        }
        val viewModel: GameViewModel by viewModels()
        setContent {
            ChessLensTheme {
                MainScreen(
                    game = viewModel,
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
