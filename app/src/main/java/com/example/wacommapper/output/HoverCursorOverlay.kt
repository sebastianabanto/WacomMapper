package com.example.wacommapper.output

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicLong

/** Programmatic, non-interactive hover marker. One window is reused and moved on display frames. */
object HoverCursorOverlay {
    private const val TAG = "HOVER_CURSOR"
    private const val DEFAULT_CURSOR_DIAMETER_DP = 3f
    private const val WINDOW_EXTRA_DP = 2f
    private const val WINDOW_ALPHA = 0.60f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val frameCount = AtomicLong(0L)
    @Volatile private var appContext: Context? = null
    @Volatile private var enabled = true
    @Volatile private var cursorDiameterDp = DEFAULT_CURSOR_DIAMETER_DP
    @Volatile private var cursorColor = DEFAULT_HOVER_CURSOR_COLOR
    @Volatile private var lastFrameLatencyMillis: Float? = null
    private var windowManager: WindowManager? = null
    private var cursorView: HoverCursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var latestX = 0f
    private var latestY = 0f
    private var latestVisible = false
    private var latestMappedAtNanos = 0L
    private var hasPendingFrame = false
    private var framePosted = false
    private var lastPositionX = Int.MIN_VALUE
    private var lastPositionY = Int.MIN_VALUE

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val snapshot = synchronized(lock) {
            framePosted = false
            if (!hasPendingFrame) return@FrameCallback
            hasPendingFrame = false
            FrameSnapshot(latestX, latestY, latestVisible && enabled, latestMappedAtNanos)
        }
        try {
            applyFrame(snapshot, frameTimeNanos)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Could not update hover overlay", throwable)
            removeWindow()
        }
        synchronized(lock) {
            if (hasPendingFrame && !framePosted) postFrameLocked()
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        appContext = context.applicationContext
        enabled = value
        if (!value) {
            remove()
        } else {
            scheduleFrame()
        }
    }

    fun setCursorSize(context: Context, diameterDp: Float) {
        cursorDiameterDp = diameterDp.coerceIn(2f, 12f)
        appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) removeWindow() else mainHandler.post(::removeWindow)
        scheduleFrame()
    }

    fun setCursorColor(context: Context, color: Int) {
        appContext = context.applicationContext
        cursorColor = color or (0xFF shl 24)
        val update = Runnable { cursorView?.setColor(cursorColor) }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else mainHandler.post(update)
    }

    fun show(context: Context, screenX: Float, screenY: Float, mappedAtNanos: Long): Boolean {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        if (!enabled || !Settings.canDrawOverlays(applicationContext)) return false
        synchronized(lock) {
            latestX = screenX
            latestY = screenY
            latestVisible = true
            latestMappedAtNanos = mappedAtNanos
            hasPendingFrame = true
            if (!framePosted) postFrameLocked()
        }
        return true
    }

    fun hide() {
        synchronized(lock) {
            if (!latestVisible && !hasPendingFrame) return
            latestVisible = false
            hasPendingFrame = true
            if (!framePosted) postFrameLocked()
        }
    }

    fun remove() {
        enabled = false
        synchronized(lock) {
            latestVisible = false
            hasPendingFrame = false
            framePosted = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) removeWindow() else mainHandler.post(::removeWindow)
    }

    fun lastUpdateLatencyMillis(): Float? = lastFrameLatencyMillis

    private fun postFrameLocked() {
        framePosted = true
        mainHandler.post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun scheduleFrame() {
        synchronized(lock) {
            hasPendingFrame = true
            if (!framePosted) postFrameLocked()
        }
    }

    private fun applyFrame(snapshot: FrameSnapshot, frameTimeNanos: Long) {
        val context = appContext ?: return
        if (!snapshot.visible && cursorView == null) return
        if (!enabled || !Settings.canDrawOverlays(context)) {
            removeWindow()
            return
        }
        val manager = windowManager ?: context.getSystemService(WindowManager::class.java).also { windowManager = it }
        var view = cursorView
        if (view == null) {
            val density = context.resources.displayMetrics.density
            val diameterPx = ((cursorDiameterDp + WINDOW_EXTRA_DP) * density + 0.5f).toInt().coerceAtLeast(1)
            view = HoverCursorView(context, cursorDiameterDp, cursorColor).apply {
                layoutParams = WindowManager.LayoutParams(diameterPx, diameterPx)
                visibility = if (snapshot.visible) View.VISIBLE else View.INVISIBLE
            }
            val params = WindowManager.LayoutParams(
                diameterPx,
                diameterPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                alpha = WINDOW_ALPHA
                title = "WacomMapper hover cursor"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            }
            params.x = (snapshot.x - diameterPx / 2f).toInt()
            params.y = (snapshot.y - diameterPx / 2f).toInt()
            manager.addView(view, params)
            cursorView = view
            layoutParams = params
            lastPositionX = params.x
            lastPositionY = params.y
            Log.i(TAG, "Overlay attached; non-touchable alpha=$WINDOW_ALPHA diameter=${diameterPx}px")
        }

        val params = layoutParams ?: return
        val diameter = params.width
        val nextX = (snapshot.x - diameter / 2f).toInt()
        val nextY = (snapshot.y - diameter / 2f).toInt()
        val attachedView = view ?: return
        attachedView.visibility = if (snapshot.visible) View.VISIBLE else View.INVISIBLE
        if (snapshot.visible) attachedView.setCircleCenter(snapshot.x - nextX, snapshot.y - nextY)
        if (snapshot.visible && (nextX != lastPositionX || nextY != lastPositionY)) {
            params.x = nextX
            params.y = nextY
            manager.updateViewLayout(attachedView, params)
            lastPositionX = nextX
            lastPositionY = nextY
        }
        val latency = if (snapshot.mappedAtNanos > 0L) {
            ((frameTimeNanos - snapshot.mappedAtNanos).coerceAtLeast(0L) / 1_000_000f)
        } else null
        lastFrameLatencyMillis = latency
        val frame = frameCount.incrementAndGet()
        if (frame == 1L || frame % 600L == 0L) {
            Log.d(TAG, "visible=${snapshot.visible} screen=(${snapshot.x},${snapshot.y}) mapToFrameMs=${latency ?: "n/a"}")
        }
    }

    private fun removeWindow() {
        val view = cursorView ?: return
        try {
            windowManager?.removeView(view)
        } catch (throwable: IllegalArgumentException) {
            Log.w(TAG, "Overlay view was already detached", throwable)
        } finally {
            cursorView = null
            layoutParams = null
            lastPositionX = Int.MIN_VALUE
            lastPositionY = Int.MIN_VALUE
            Log.i(TAG, "Overlay detached")
        }
    }

    private data class FrameSnapshot(
        val x: Float,
        val y: Float,
        val visible: Boolean,
        val mappedAtNanos: Long,
    )
}

/** 12 dp outlined ring; its window center tracks the exact mapped stylus coordinate. */
private class HoverCursorView(context: Context, diameterDp: Float, initialColor: Int) : View(context) {
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private val circleRadius = (diameterDp * resources.displayMetrics.density) / 2f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = initialColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    fun setCircleCenter(x: Float, y: Float) {
        circleCenterX = x
        circleCenterY = y
        invalidate()
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = circleRadius - paint.strokeWidth / 2f
        if (radius > 0f) {
            val centerX = if (circleCenterX == 0f) width / 2f else circleCenterX
            val centerY = if (circleCenterY == 0f) height / 2f else circleCenterY
            canvas.drawCircle(centerX, centerY, radius, paint)
        }
    }
}
