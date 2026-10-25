package autofruit.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating start/stop button drawn by the accessibility service (so it needs no
 * extra "draw over other apps" permission). Drag it to move it; keep it off the
 * tiles, since taps there would hit the button instead of the game.
 */
class Overlay(private val service: AccessibilityService, private val prefs: Prefs, private val onToggle: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val button = TextView(service)
    private val label = TextView(service)
    private val root = LinearLayout(service)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )
    private var shown = false

    init {
        val size = (52 * density).toInt()
        button.layoutParams = LinearLayout.LayoutParams(size, size)
        button.gravity = Gravity.CENTER
        button.textSize = 22f
        button.setTextColor(Color.WHITE)
        label.setTextColor(Color.WHITE)
        label.textSize = 11f
        label.maxWidth = (160 * density).toInt()
        label.setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        label.background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(0xAA000000.toInt())
        }
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(button)
        root.addView(label)
        setRunning(false)
        setStatus("Autofruit")

        val metrics = service.resources.displayMetrics
        params.gravity = Gravity.TOP or Gravity.START
        // Default: left edge, just under the level header, away from the tiles.
        params.x = prefs.overlayX.takeIf { it >= 0 } ?: (4 * density).toInt()
        params.y = prefs.overlayY.takeIf { it >= 0 } ?: (metrics.heightPixels * 0.19f).toInt()
        attachDrag()
    }

    fun show() {
        if (!shown) {
            wm.addView(root, params)
            shown = true
        }
    }

    fun hide() {
        if (shown) {
            wm.removeView(root)
            shown = false
        }
    }

    fun setRunning(running: Boolean) {
        button.text = if (running) "■" else "▶"
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) 0xFFD84343.toInt() else 0xFF2F7DE1.toInt())
            setStroke((2 * density).toInt(), Color.WHITE)
        }
    }

    fun setStatus(text: String) {
        label.text = text
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        button.setOnTouchListener { _: View, e: MotionEvent ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        if (shown) wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    } else onToggle()
                }
            }
            true
        }
    }
}
