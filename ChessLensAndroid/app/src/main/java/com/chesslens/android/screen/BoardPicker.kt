package com.chesslens.android.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen frame for marking the board by hand: drag the square to move it, drag the round
 * handle to resize it, until its 8×8 grid lines sit on the board's squares. ChessLens then reads
 * only inside it.
 */
class BoardPicker(
    private val context: Context,
    private val onDone: (RectF) -> Unit,
    private val onAuto: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    val isShowing: Boolean get() = root != null

    /** Opens the frame at [start] (screen pixels), or a centred square if null. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(start: RectF?) {
        if (root != null) return
        val screen = wm.maximumWindowMetrics.bounds
        val side = screen.width() * 0.9f
        val initial = start ?: RectF(
            (screen.width() - side) / 2, (screen.height() - side) / 2,
            (screen.width() + side) / 2, (screen.height() + side) / 2,
        )
        val frame = FrameView(context, RectF(initial))
        val container = FrameLayout(context)
        container.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val help = TextView(context).apply {
            text = "Drag the frame onto the board. Drag the round handle to resize it until the lines sit on the squares."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Color.argb(230, 34, 32, 28)) }
        }
        container.addView(help, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16f).toInt(), dp(48f).toInt(), dp(16f).toInt(), 0)
        })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, color: Int, textColor: Int, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22f).toInt(), dp(12f).toInt(), dp(22f).toInt(), dp(12f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(color) }
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Auto", Color.rgb(58, 54, 48), Color.WHITE) { hide(); onAuto() })
        buttons.addView(View(context), LinearLayout.LayoutParams(dp(12f).toInt(), 1))
        buttons.addView(button("Done", Color.rgb(129, 182, 76), Color.rgb(16, 31, 7)) {
            // The frame view draws in its own coordinates; convert to screen pixels.
            val loc = IntArray(2)
            frame.getLocationOnScreen(loc)
            val r = RectF(frame.box)
            r.offset(loc[0].toFloat(), loc[1].toFloat())
            hide()
            onDone(r)
        })
        container.addView(buttons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(0, 0, 0, dp(48f).toInt())
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(container, params)
        root = container
        // The start rectangle is in screen pixels; shift it into the view's coordinates once laid out.
        frame.post {
            val loc = IntArray(2)
            frame.getLocationOnScreen(loc)
            frame.box.offset(-loc[0].toFloat(), -loc[1].toFloat())
            frame.invalidate()
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private inner class FrameView(context: Context, val box: RectF) : View(context) {
        private val shade = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(129, 182, 76); strokeWidth = dp(3f) }
        private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 129, 182, 76); strokeWidth = dp(1.2f) }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(129, 182, 76) }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2f) }
        private val handleRadius = dp(16f)
        private var mode = 0 // 0 none, 1 move, 2 resize
        private var lastX = 0f
        private var lastY = 0f

        override fun onDraw(canvas: Canvas) {
            // Dim everything outside the frame.
            canvas.drawRect(0f, 0f, width.toFloat(), box.top, shade)
            canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, box.top, box.left, box.bottom, shade)
            canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, shade)
            for (k in 1 until 8) {
                val x = box.left + box.width() * k / 8
                val y = box.top + box.height() * k / 8
                canvas.drawLine(x, box.top, x, box.bottom, lines)
                canvas.drawLine(box.left, y, box.right, y, lines)
            }
            canvas.drawRect(box, border)
            canvas.drawCircle(box.right, box.bottom, handleRadius, handle)
            canvas.drawCircle(box.right, box.bottom, handleRadius, handleRing)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y
                    mode = when {
                        hypot(e.x - box.right, e.y - box.bottom) < handleRadius * 2.2f -> 2
                        box.contains(e.x, e.y) -> 1
                        else -> 0
                    }
                    return mode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (mode == 1) {
                        box.offset(dx, dy)
                    } else if (mode == 2) {
                        // Keep it square: grow by the larger of the two movements.
                        val grow = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) dx else dy
                        val side = max(dp(120f), min(width.toFloat(), box.width() + grow))
                        box.right = box.left + side
                        box.bottom = box.top + side
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
            }
            return false
        }
    }
}
