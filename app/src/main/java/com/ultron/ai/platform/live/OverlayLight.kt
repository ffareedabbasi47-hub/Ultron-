package com.ultron.ai.platform.live

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.ultron.ai.domain.live.EdgeMode
import com.ultron.ai.domain.live.EdgeModes
import com.ultron.ai.platform.accessibility.UltronAccessibilityService
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ULTRON's edge light drawn over other apps while the mic session is alive (home screen, browser, video, games...).
 *
 * - Needs the user to grant "Display over other apps" in Android Settings (or the user-enabled accessibility service).
 *   Nothing is shown if neither exists.
 * - Never touchable, never focusable; the window is ≤ 0.8 opaque, which is what Android 12+ requires so touches keep
 *   reaching the app underneath.
 * - ONE view at a time, process-wide (a second [OverlayLight] hides the first instead of stacking a duplicate).
 * - Cheap to draw: one shader-stroked outline (3 draw calls per frame), a per-state frame-rate cap, and the animation
 *   stops while the window is not visible (screen off).
 * - Limits that no app can change: it does not appear on the lock screen, on the secure system dialogs, over apps that
 *   hide system overlays (some banking apps / FLAG_SECURE screens), or when an OEM revoked the permission or killed the service.
 */
class OverlayLight(private val context: Context) {
    private val appWm = context.getSystemService(WindowManager::class.java)
    private var wm: WindowManager = appWm
    private var attachedType = 0
    private var view: GlowView? = null
    private var reduced = false
    private var flashAt = 0L

    companion object {
        private val _showing = MutableStateFlow(false)
        /** True while an edge-light window is really attached (the in-app light hides itself then, so there is never a double ring). */
        val showing: StateFlow<Boolean> = _showing
        @Volatile private var current: OverlayLight? = null
    }

    /** Works with "Display over other apps" OR with the user-enabled accessibility service (no extra permission). */
    fun canShow() = Settings.canDrawOverlays(context) || UltronAccessibilityService.instance != null

    val isShowing: Boolean get() = view != null

    fun setReducedMotion(b: Boolean) { reduced = b; view?.setReduced(b) }

    /** Old two-level API kept for existing callers. */
    fun show(intense: Boolean) = show(if (intense) EdgeMode.SPEAKING else EdgeMode.LISTENING)

    fun show(mode: EdgeMode) {
        if (mode == EdgeMode.OFF) { hide(); return }
        val app = Settings.canDrawOverlays(context)
        val a11y = UltronAccessibilityService.instance
        if (!app && a11y == null) { hide(); return }
        val type = if (app) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        if (view != null && attachedType != type) hide()
        if (view == null) {
            current?.takeIf { it !== this && it.view != null }?.hide() // never two overlays
            val gv = GlowView(if (app) context else (a11y ?: context))
            val target = if (app) appWm else (a11y?.getSystemService(WindowManager::class.java) ?: appWm)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            lp.title = "ULTRON edge light"
            // Android 12+: touches only pass through a NOT_TOUCHABLE overlay window if its opacity is <= 0.8.
            if (app) lp.alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            try { target.addView(gv, lp) } catch (_: Exception) { return }
            view = gv; wm = target; attachedType = type; current = this
            gv.setReduced(reduced)
            _showing.value = true
            // The wake event can arrive a moment BEFORE the window exists (the light is created by the same wake): still flash.
            if (SystemClock.uptimeMillis() - flashAt < 800L) gv.flash()
        }
        view?.setMode(mode)
    }

    /** Short activation burst after a wake word (returns to the real mode by itself). */
    fun flash() { flashAt = SystemClock.uptimeMillis(); view?.flash() }

    fun hide() {
        view?.let { v -> v.stopAnim(); try { wm.removeView(v) } catch (_: Exception) {} }
        view = null
        attachedType = 0
        if (current === this) { current = null; _showing.value = false }
    }

    private class GlowView(c: Context) : View(c) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val outline = Path()
        private val rotM = Matrix()
        private var gradient: SweepGradient? = null
        private var mode = EdgeMode.LISTENING
        private var reduced = false
        private var flashUntil = 0L
        private var phase = 0f
        private var lastTick = 0L
        private var lastDraw = 0L
        private val cyan = 0xFF35D6E8.toInt()
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { tick() }
        }

        init { setWillNotDraw(false) }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); startIfNeeded() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) startIfNeeded() else anim.cancel() // screen off / hidden: no animation work at all
        }

        private fun startIfNeeded() { if (!reduced && isAttachedToWindow && !anim.isRunning) { lastTick = SystemClock.uptimeMillis(); anim.start() } }
        fun stopAnim() { anim.cancel() }
        fun setReduced(b: Boolean) { reduced = b; if (b) anim.cancel() else startIfNeeded(); invalidate() }
        fun setMode(m: EdgeMode) { if (m != mode) { mode = m; invalidate() } }
        fun flash() { flashUntil = SystemClock.uptimeMillis() + EdgeModes.WAKE_FLASH_MS; invalidate() }

        private fun effective(now: Long): EdgeMode = if (now < flashUntil) EdgeMode.WAKE else mode

        private fun tick() {
            val now = SystemClock.uptimeMillis()
            val m = effective(now)
            val dt = (now - lastTick).coerceAtLeast(0L); lastTick = now
            if (m.periodMs > 0) { phase += dt.toFloat() / m.periodMs; phase -= phase.toInt() }
            if (m.fps > 0 && now - lastDraw >= 1000L / m.fps) { lastDraw = now; invalidate() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Rebuilt on every size change (rotation, fold, split screen, display size) so the ring always hugs the real edges.
            val inset = 4f * d
            outline.rewind()
            outline.addRoundRect(RectF(inset, inset, w - inset, h - inset), 36f * d, 36f * d, Path.Direction.CW)
            gradient = buildGradient(w / 2f, h / 2f)
        }

        /** Two comets: bright head, long fading tail behind it (clockwise), rotated by [matrix]. */
        private fun buildGradient(cx: Float, cy: Float): SweepGradient {
            val v = 0x8B5CF6; val c = 0x35D6E8; val lc = 0x9AF3FF; val wh = 0xFFFFFF
            fun argb(a: Int, rgb: Int) = (a shl 24) or rgb
            val half = floatArrayOf(0f, 0.20f, 0.38f, 0.47f, 0.49f, 0.499f)
            val col = intArrayOf(argb(0, v), argb(0x66, v), argb(0xCC, c), argb(0xFF, lc), argb(0xFF, wh), argb(0, wh))
            val pos = FloatArray(half.size * 2 + 1); val cols = IntArray(half.size * 2 + 1)
            for (i in half.indices) { pos[i] = half[i]; cols[i] = col[i]; pos[i + half.size] = 0.5f + half[i]; cols[i + half.size] = col[i] }
            pos[pos.size - 1] = 1f; cols[cols.size - 1] = argb(0, v)
            // The second comet starts exactly at 0.5; keep positions non-decreasing.
            pos[half.size] = 0.5f
            return SweepGradient(cx, cy, cols, pos)
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
            return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }

        override fun onDraw(canvas: Canvas) {
            val g = gradient ?: return
            val now = SystemClock.uptimeMillis()
            val m = effective(now)
            if (m == EdgeMode.OFF) return
            var w = m.widthDp * d
            var k = m.intensity
            var tint = 0f
            if (now < flashUntil) {
                // Activation burst: thicker and whiter at the start, easing back to the real mode.
                val t = (flashUntil - now).toFloat() / EdgeModes.WAKE_FLASH_MS
                w = mode.widthDp * d + (EdgeMode.WAKE.widthDp - mode.widthDp) * d * t
                tint = t
            } else if (m.pulse) {
                k *= 0.65f + 0.35f * sin(2.0 * PI * (now % 2400L) / 2400.0).toFloat()
            }
            // Faint full ring so the edge is readable even between comets.
            paint.shader = null
            paint.color = if (tint > 0f) mix(cyan, Color.WHITE, tint) else cyan
            paint.strokeWidth = w; paint.alpha = (45 * k).toInt().coerceIn(0, 255)
            canvas.drawPath(outline, paint)
            // Comets: one shader, two strokes (soft glow + sharp core).
            rotM.setRotate(phase * 360f, width / 2f, height / 2f)
            g.setLocalMatrix(rotM)
            paint.shader = g
            paint.color = Color.WHITE
            paint.strokeWidth = w * 3.2f; paint.alpha = (95 * k).toInt().coerceIn(0, 255)
            canvas.drawPath(outline, paint)
            paint.strokeWidth = w * 0.9f; paint.alpha = (255 * k).toInt().coerceIn(0, 255)
            canvas.drawPath(outline, paint)
            paint.shader = null
        }
    }
}
