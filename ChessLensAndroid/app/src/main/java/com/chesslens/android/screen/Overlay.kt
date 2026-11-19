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
class Overlay(private val context: Context, private val onToggleMode: () -> Unit, private val onStop: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var outlineView: OutlineView? = null
    private lateinit var moveText: TextView
    private lateinit var noteText: TextView
    private lateinit var modeButton: TextView

    /** Where the bubble is on screen, read from the capture thread. */
    @Volatile private var bubbleBounds: Rect? = null

    private val green = Color.rgb(129, 182, 76)
    private val teal = Color.rgb(27, 172, 166)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    fun bubbleRect(): Rect? = bubbleBounds

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
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f).toInt(), dp(5f).toInt(), dp(10f).toInt(), dp(5f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(Color.rgb(58, 54, 48)) }
            setOnClickListener { onClick() }
        }
        modeButton = chip("Best") { onToggleMode() }
        val stop = chip("✕ Stop") { onStop() }
        buttons.addView(modeButton)
        buttons.addView(View(context), LinearLayout.LayoutParams(dp(6f).toInt(), 1))
        buttons.addView(stop)
        root.addView(moveText)
        root.addView(noteText)
        root.addView(View(context), LinearLayout.LayoutParams(1, dp(6f).toInt()))
        root.addView(buttons)

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
