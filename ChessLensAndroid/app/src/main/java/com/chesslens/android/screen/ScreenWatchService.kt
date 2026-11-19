package com.chesslens.android.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import com.chesslens.android.AppSettings
import com.chesslens.android.Speaker
import com.chesslens.android.chess.Brilliant
import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.UciMove
import com.chesslens.android.engine.StockfishEngine
import com.chesslens.android.ui.MainActivity
import com.chesslens.android.vision.PieceTemplate
import com.chesslens.android.vision.RgbImage
import com.chesslens.android.vision.ScreenGrid
import com.chesslens.android.vision.ScreenVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the phone's screen while you play in a chess app: finds the board, follows the game,
 * and on your turn shows the best (or brilliant) move in a floating bubble, outlines it on the
 * board and says it. Runs as a foreground service so it keeps going while ChessLens is minimised.
 * Frames are only processed on the phone.
 */
class ScreenWatchService : Service() {
    companion object {
        private const val CHANNEL = "screen_watch"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.chesslens.android.STOP_SCREEN_WATCH"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val INTERVAL_MS = 600L

        private val runningFlow = MutableStateFlow(false)
        val running: StateFlow<Boolean> = runningFlow

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenWatchService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenWatchService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var settings: AppSettings
    private lateinit var engine: StockfishEngine
    private lateinit var speaker: Speaker
    private lateinit var overlay: Overlay
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var scale = 1.0

    // Board reading state (only touched on the capture thread).
    private var grid: ScreenGrid? = null
    private var templates: Map<String, PieceTemplate> = emptyMap()
    private var whiteAtBottom = true
    private var pending: String? = null
    private var lastEmitted: String? = null

    // Game state (only touched on the main thread).
    private var position: ChessPosition = ChessPosition.START
    private var player = PieceColor.WHITE
    private var analysis: Job? = null
    private var analyzed: ChessPosition? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("chesslens_screen", MODE_PRIVATE) }
    private var brilliantMode: Boolean
        get() = prefs.getBoolean("brilliant", false)
        set(value) { prefs.edit().putBoolean("brilliant", value).apply() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        engine = StockfishEngine(this)
        speaker = Speaker(this)
        templates = ScreenVision.deserialize(prefs.getString("pieces", null))
        overlay = Overlay(this, onToggleMode = ::toggleMode, onStop = { stopSelf() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || projection != null) return START_NOT_STICKY
        try {
            begin(code, data)
        } catch (e: Exception) {
            overlay.show()
            overlay.setStatus("Screen capture didn't start: ${e.message}")
            main.postDelayed({ stopSelf() }, 5000)
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(code, data) ?: throw IllegalStateException("permission was not given")
        projection = mp
        val t = HandlerThread("chesslens-screen").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, h)

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        scale = if (width > 900) 0.5 else 1.0
        val cw = (width * scale).toInt()
        val ch = (height * scale).toInt()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "ChessLens", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
        )
        runningFlow.value = true
        overlay.show()
        overlay.setMode(brilliantMode)
        overlay.setStatus(if (templates.size >= 12) "Looking for a chess board…" else "Open a new game once so I can learn your pieces.")
        scope.launch { try { engine.start() } catch (_: Exception) {} }
        h.postDelayed(::tick, INTERVAL_MS)
    }

    // ---------------------------------------------------------------------------------------------
    // Capture thread

    private fun tick() {
        val h = handler ?: return
        try {
            val image = reader?.acquireLatestImage()
            if (image != null) {
                val frame = image.use { toRgb(it) }
                read(frame)
            }
        } catch (e: Exception) {
            main.post { overlay.setStatus("Reading the screen failed: ${e.message}") }
        }
        h.postDelayed(::tick, INTERVAL_MS)
    }

    private fun toRgb(image: android.media.Image): RgbImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val hgt = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val pixels = IntArray(w * hgt)
        val row = ByteArray(rowStride)
        for (y in 0 until hgt) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            var o = 0
            val base = y * w
            for (x in 0 until w) {
                pixels[base + x] = ((row[o].toInt() and 255) shl 16) or ((row[o + 1].toInt() and 255) shl 8) or (row[o + 2].toInt() and 255)
                o += pixelStride
            }
        }
        // Blank out the bubble so it is never read as part of the board.
        overlay.bubbleRect()?.let { rect ->
            val x0 = (rect.left * scale).toInt().coerceIn(0, w)
            val x1 = (rect.right * scale).toInt().coerceIn(0, w)
            val y0 = (rect.top * scale).toInt().coerceIn(0, hgt)
            val y1 = (rect.bottom * scale).toInt().coerceIn(0, hgt)
            for (y in y0 until y1) java.util.Arrays.fill(pixels, y * w + x0, y * w + x1, 0)
        }
        return RgbImage(w, hgt, pixels)
    }

    private fun read(img: RgbImage) {
        val found = ScreenVision.findBoard(img, grid)
        if (found == null) {
            if (grid != null) main.post { overlay.setStatus("Lost the board. Keep the whole board visible."); overlay.clearOutline() }
            else main.post { overlay.setStatus("Looking for a chess board…") }
            grid = null
            return
        }
        grid = found
        val squares = ScreenVision.readSquares(img, found)
        ScreenVision.learnFromStart(squares)?.let { (learned, bottomWhite) ->
            val first = templates.size < 12
            templates = learned
            whiteAtBottom = bottomWhite
            prefs.edit().putString("pieces", ScreenVision.serialize(learned)).apply()
            if (first) main.post { overlay.setStatus("Learned your pieces.") }
        }
        if (templates.size < 12) {
            main.post { overlay.setStatus("Board found. Open a new game once so I can learn your pieces.") }
            return
        }
        val reading = ScreenVision.recognize(squares, templates, whiteAtBottom)
        if (reading == null) {
            pending = null
            main.post { overlay.setStatus("A square is unclear (arrow, dragged piece…).") }
            return
        }
        whiteAtBottom = reading.whiteAtBottom
        val key = reading.board.placementFen + (reading.lastMover?.fenLetter ?: "-") + reading.whiteAtBottom
        if (pending != key) { pending = key; return }
        if (key == lastEmitted) return
        lastEmitted = key
        val gridNow = found
        main.post { follow(reading.board, reading.whiteAtBottom, reading.lastMover, gridNow) }
    }

    // ---------------------------------------------------------------------------------------------
    // Main thread: game following and suggestions

    private var screenGrid: ScreenGrid? = null

    private fun follow(seen: ChessBoard, bottomWhite: Boolean, lastMover: PieceColor?, gridNow: ScreenGrid) {
        screenGrid = gridNow
        player = if (bottomWhite) PieceColor.WHITE else PieceColor.BLACK
        position = when {
            seen == position.board ->
                if (lastMover != null && position.sideToMove == lastMover) position.copy(sideToMove = lastMover.opposite, enPassant = null) else position
            else -> movesBetween(position, seen) ?: run {
                val turn = lastMover?.opposite ?: position.sideToMove
                val first = ChessPosition.of(seen, turn)
                if (seen.validationIssues(turn).isEmpty()) first else ChessPosition.of(seen, turn.opposite)
            }
        }
        update()
    }

    private fun movesBetween(from: ChessPosition, seen: ChessBoard): ChessPosition? {
        for (first in from.legalMoves()) {
            val middle = from.applying(first)
            if (middle.board == seen) return middle
        }
        for (first in from.legalMoves()) {
            val middle = from.applying(first)
            for (second in middle.legalMoves()) {
                val end = middle.applying(second)
                if (end.board == seen) return end
            }
        }
        return null
    }

    private fun update() {
        if (position.legalMoves().isEmpty()) {
            analysis?.cancel()
            overlay.setMove(null, false, if (position.board.isInCheck(position.sideToMove)) "Checkmate." else "Stalemate.")
            overlay.clearOutline()
            return
        }
        if (position.sideToMove != player) {
            analysis?.cancel()
            analyzed = null
            overlay.setMove(null, false, "Opponent's move…")
            overlay.clearOutline()
            return
        }
        if (position == analyzed) return
        analyzed = position
        val target = position
        val brilliant = brilliantMode
        overlay.setMove(null, false, if (brilliant) "Looking for a brilliant move…" else "Thinking…")
        analysis?.cancel()
        analysis = scope.launch {
            try {
                val lines = engine.analyzeLines(target.fen, settings.thinkTimeMillis, if (brilliant) 6 else 1)
                val (line, isBrilliant) = Brilliant.choose(target, lines, brilliant) ?: return@launch
                val move = UciMove.parse(line.move) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (target != position) return@withContext
                    val san = target.board.shortNotation(move)
                    val note = when {
                        isBrilliant -> "Brilliant!!"
                        brilliant -> "No brilliant move. Best move."
                        else -> "Best move."
                    }
                    overlay.setMove(san, isBrilliant, note + evaluation(line))
                    outline(move, isBrilliant)
                    val words = target.board.spokenDescription(move)
                    speaker.say(if (isBrilliant) "Brilliant move! $words" else if (brilliant) "No brilliant move. $words" else words)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { overlay.setStatus(e.message ?: "Stockfish failed.") }
            }
        }
    }

    private fun evaluation(line: com.chesslens.android.chess.EngineLine): String {
        line.mate?.let { return if (it > 0) "  ·  Mate in $it" else "  ·  Mated in ${-it}" }
        val pawns = (line.centipawns ?: 0) / 100.0
        return "  ·  " + (if (pawns > 0) "+" else "") + String.format("%.1f", pawns)
    }

    private fun outline(move: UciMove, brilliant: Boolean) {
        val g = screenGrid ?: return
        fun cell(file: Int, rank: Int): android.graphics.RectF {
            val col = if (whiteAtBottom) file else 7 - file
            val row = if (whiteAtBottom) 7 - rank else rank
            val left = (g.x + col * g.cw) / scale
            val top = (g.y + row * g.ch) / scale
            return android.graphics.RectF(left.toFloat(), top.toFloat(), (left + g.cw / scale).toFloat(), (top + g.ch / scale).toFloat())
        }
        overlay.setOutline(cell(move.from.file, move.from.rank), cell(move.to.file, move.to.rank), brilliant)
    }

    private fun toggleMode() {
        brilliantMode = !brilliantMode
        overlay.setMode(brilliantMode)
        analyzed = null
        update()
    }

    // ---------------------------------------------------------------------------------------------

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reading the chess board", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenWatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ChessLens is reading your chess board")
            .setContentText("Tap to open ChessLens.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runningFlow.value = false
        handler?.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        scope.cancel()
        engine.shutdown()
        overlay.hide()
        projection = null
        super.onDestroy()
    }
}
