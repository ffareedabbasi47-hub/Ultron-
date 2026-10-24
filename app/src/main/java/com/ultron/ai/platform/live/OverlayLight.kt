package com.ultron.ai.platform.live

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Rotating edge light drawn over other apps while a live session is active. It needs the user to grant
 * "Display over other apps" in Android Settings; it is never touchable, never takes focus, and is removed
 * the moment the session stops. Nothing is shown if the permission is missing.
 */
class OverlayLight(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: GlowView? = null

    fun canShow() = Settings.canDrawOverlays(context)

    fun show(intense: Boolean) {
        if (!canShow()) return
        val v = view ?: GlowView(context).also {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            try { wm.addView(it, lp); view = it } catch (_: Exception) { return }
        }
        v.setIntense(intense)
    }

    fun hide() {
        view?.let { v -> v.stopAnim(); try { wm.removeView(v) } catch (_: Exception) {} }
        view = null
    }

    private class GlowView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private var deg = 0f
        private var intense = false
        private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { deg = it.animatedValue as Float; invalidate() }
        }
        init { setWillNotDraw(false) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        fun stopAnim() { anim.cancel() }
        fun setIntense(b: Boolean) { if (b != intense) { intense = b; anim.duration = if (b) 2000 else 3600; invalidate() } }
        override fun onDraw(canvas: Canvas) {
            val w = (if (intense) 7f else 4f) * resources.displayMetrics.density
            paint.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(Color.TRANSPARENT, 0xFF35D6E8.toInt(), 0xFF8B5CF6.toInt(), 0xFF4F9CFF.toInt(), Color.TRANSPARENT, Color.TRANSPARENT), null)
            canvas.save(); canvas.rotate(deg, width / 2f, height / 2f)
            val r = 36f * resources.displayMetrics.density
            rect.set(w / 2, w / 2, width - w / 2, height - w / 2)
            paint.strokeWidth = w * 3; paint.alpha = 55; canvas.drawRoundRect(rect, r, r, paint)
            paint.strokeWidth = w; paint.alpha = 240; canvas.drawRoundRect(rect, r, r, paint)
            canvas.restore()
        }
    }
}
