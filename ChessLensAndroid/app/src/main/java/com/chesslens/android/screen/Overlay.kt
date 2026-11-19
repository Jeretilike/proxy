package com.chesslens.android.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * What ChessLens draws over the chess app: a small draggable bubble with the move, and outlines on
 * the move's two squares. Needs the "Display over other apps" permission.
 */
class Overlay(
    private val context: Context,
    private val onToggleMode: () -> Unit,
    private val onFlipTurn: () -> Unit,
    private val onToggleBoth: () -> Unit,
    private val onPickBoard: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var outlineView: OutlineView? = null
    private lateinit var moveText: TextView
    private lateinit var noteText: TextView
    private lateinit var infoText: TextView
    private lateinit var modeButton: TextView
    private lateinit var bothButton: TextView
    private val chips = mutableListOf<TextView>()

    /** Where the board is on screen, so the bubble can keep out of its way. */
    private var boardOnScreen: RectF? = null
    private var dragging = false

    private val prefs = context.getSharedPreferences("chesslens_screen", Context.MODE_PRIVATE)
    private val sizes = floatArrayOf(0.8f, 1f, 1.3f)
    private var sizeIndex = prefs.getInt("bubbleSize", 1).coerceIn(0, 2)

    /** Where the bubble is on screen, read from the capture thread. */
    @Volatile private var bubbleBounds: Rect? = null

    private val green = Color.rgb(129, 182, 76)
    private val teal = Color.rgb(27, 172, 166)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    fun bubbleRect(): Rect? = bubbleBounds

    private fun maxPassThroughAlpha(): Float = try {
        context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch.coerceAtMost(0.8f)
    } catch (_: Throwable) {
        0.8f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null || !Settings.canDrawOverlays(context)) return
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // Outlines: full screen, can't be touched, drawn under the bubble.
        val outline = OutlineView(context)
        val outlineParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Android 12+ blocks taps from reaching the app underneath an overlay window unless the
            // window is at most this opaque; without it the chess app stops responding to taps.
            alpha = maxPassThroughAlpha()
        }
        wm.addView(outline, outlineParams)
        outlineView = outline

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Color.argb(235, 34, 32, 28))
                setStroke(dp(1.5f).toInt(), Color.argb(255, 70, 64, 56))
            }
            elevation = dp(8f)
        }
        moveText = TextView(context).apply {
            text = "…"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        noteText = TextView(context).apply {
            setTextColor(Color.rgb(190, 182, 170))
            textSize = 12f
            maxWidth = dp(220f).toInt()
        }
        infoText = TextView(context).apply {
            setTextColor(Color.rgb(130, 124, 114))
            textSize = 10f
            maxWidth = dp(260f).toInt()
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chips.clear()
        fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(Color.rgb(58, 54, 48)) }
            setOnClickListener { onClick() }
            chips += this
        }
        modeButton = chip("Best") { onToggleMode() }
        val turn = chip("⇄ Turn") { onFlipTurn() }
        bothButton = chip("Both sides") { onToggleBoth() }
        val stop = chip("✕") { onStop() }
        val size = chip("Aa") { nextSize() }
        val pick = chip("▢ Board") { onPickBoard() }
        // Two rows so the bubble stays narrow.
        for ((i, b) in listOf(modeButton, bothButton, turn).withIndex()) {
            if (i > 0) buttons.addView(View(context), LinearLayout.LayoutParams(dp(5f).toInt(), 1))
            buttons.addView(b)
        }
        root.addView(moveText)
        root.addView(noteText)
        root.addView(infoText)
        root.addView(View(context), LinearLayout.LayoutParams(1, dp(6f).toInt()))
        root.addView(buttons)
        val buttons2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, b) in listOf(pick, size, stop).withIndex()) {
            if (i > 0) buttons2.addView(View(context), LinearLayout.LayoutParams(dp(5f).toInt(), 1))
            buttons2.addView(b)
        }
        root.addView(View(context), LinearLayout.LayoutParams(1, dp(5f).toInt()))
        root.addView(buttons2)
        applySize()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f).toInt()
            y = dp(60f).toInt()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Drag the bubble anywhere; taps still reach its buttons.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false; return@setOnTouchListener true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Dropped on the board? Slide off it again.
                    if (dragging) root.post { recordBounds(root); keepOffBoard() }
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(6f)) dragging = true
                    if (dragging) {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(root, params)
                        recordBounds(root)
                    }
                }
            }
            true
        }
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> recordBounds(v) }
        wm.addView(root, params)
        bubble = root
        bubbleParams = params
    }

    /** Bubble sizes: small, medium, large (the Aa button). */
    private fun nextSize() {
        sizeIndex = (sizeIndex + 1) % sizes.size
        prefs.edit().putInt("bubbleSize", sizeIndex).apply()
        applySize()
        bubble?.post { bubble?.let { recordBounds(it) }; keepOffBoard() }
    }

    private fun applySize() {
        val s = sizes[sizeIndex]
        moveText.textSize = 26f * s
        noteText.textSize = 12f * s
        noteText.maxWidth = dp(220f * s).toInt()
        infoText.textSize = 10f * s
        infoText.maxWidth = dp(260f * s).toInt()
        for (c in chips) {
            c.textSize = 13f * s
            c.setPadding(dp(11f * s).toInt(), dp(8f * s).toInt(), dp(11f * s).toInt(), dp(8f * s).toInt())
        }
        bubble?.setPadding(dp(12f * s).toInt(), dp(8f * s).toInt(), dp(12f * s).toInt(), dp(8f * s).toInt())
    }

    /** Tells the bubble where the board is (screen pixels); it moves if it's in the way. */
    fun avoidBoard(board: RectF) {
        val old = boardOnScreen
        boardOnScreen = board
        if (old == null || abs(old.top - board.top) > 4 || abs(old.bottom - board.bottom) > 4 || abs(old.left - board.left) > 4) keepOffBoard()
        else if (!dragging) keepOffBoard()
    }

    /** Moves the bubble above or below the board, wherever it fits, if it covers the board. */
    private fun keepOffBoard() {
        if (dragging) return
        val root = bubble ?: return
        val params = bubbleParams ?: return
        val board = boardOnScreen ?: return
        if (root.width == 0 || root.height == 0) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val w = root.width
        val h = root.height
        val margin = dp(8f)
        val current = RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + w).toFloat(), (loc[1] + h).toFloat())
        val screen = wm.maximumWindowMetrics.bounds
        val topLimit = dp(28f) // below the status bar
        val bottomLimit = screen.height() - dp(28f) // above the navigation bar
        var targetX = loc[0].toFloat().coerceIn(margin, (screen.width() - w - margin).coerceAtLeast(margin))
        var targetY = loc[1].toFloat()
        if (RectF.intersects(current, RectF(board.left - margin, board.top - margin, board.right + margin, board.bottom + margin))) {
            val above = board.top - margin - topLimit
            val below = bottomLimit - (board.bottom + margin)
            targetY = when {
                above >= h -> board.top - margin - h
                below >= h -> board.bottom + margin
                above >= below -> topLimit
                else -> bottomLimit - h
            }
        }
        val dx = (targetX - loc[0]).toInt()
        val dy = (targetY - loc[1]).toInt()
        if (dx == 0 && dy == 0) return
        params.x += dx
        params.y += dy
        wm.updateViewLayout(root, params)
        root.post { recordBounds(root) }
    }

    private fun recordBounds(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        bubbleBounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    fun hide() {
        bubble?.let { runCatching { wm.removeView(it) } }
        outlineView?.let { runCatching { wm.removeView(it) } }
        bubble = null
        outlineView = null
        bubbleBounds = null
    }

    fun setMode(brilliant: Boolean) {
        if (bubble == null) return
        modeButton.text = if (brilliant) "Brilliant !!" else "Best"
        modeButton.setTextColor(if (brilliant) teal else Color.WHITE)
    }

    fun setBothSides(on: Boolean) {
        if (bubble == null) return
        (bothButton.background as? GradientDrawable)?.setColor(if (on) green else Color.rgb(58, 54, 48))
        bothButton.setTextColor(if (on) Color.rgb(16, 31, 7) else Color.WHITE)
    }

    fun setInfo(text: String) {
        if (bubble == null) return
        infoText.text = text
    }

    fun setStatus(text: String) {
        if (bubble == null) return
        noteText.text = text
    }

    fun setMove(san: String?, brilliant: Boolean, note: String) {
        if (bubble == null) return
        moveText.text = san?.let { if (brilliant) "$it!!" else it } ?: "…"
        moveText.setTextColor(if (brilliant) teal else Color.WHITE)
        noteText.text = note
        (bubble?.background as? GradientDrawable)?.setStroke(dp(if (brilliant) 2.5f else 1.5f).toInt(), if (brilliant) teal else Color.rgb(70, 64, 56))
    }

    fun setOutline(from: RectF, to: RectF, brilliant: Boolean) {
        outlineView?.show(from, to, if (brilliant) teal else green)
    }

    fun clearOutline() {
        outlineView?.show(null, null, 0)
    }

    /** Thin outlines along the edges of the two squares, so the board stays readable underneath. */
    private inner class OutlineView(context: Context) : View(context) {
        private var from: RectF? = null
        private var to: RectF? = null
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun show(from: RectF?, to: RectF?, color: Int) {
            this.from = from
            this.to = to
            solid.color = color
            dashed.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val f = from ?: return
            val t = to ?: return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val w = t.width() * 0.035f
            solid.strokeWidth = w
            dashed.strokeWidth = w * 0.7f
            dashed.pathEffect = DashPathEffect(floatArrayOf(w * 2, w * 1.5f), 0f)
            canvas.drawRect(RectF(t.left + w / 2, t.top + w / 2, t.right - w / 2, t.bottom - w / 2), solid)
            canvas.drawRect(RectF(f.left + w / 2, f.top + w / 2, f.right - w / 2, f.bottom - w / 2), dashed)
        }
    }
}
