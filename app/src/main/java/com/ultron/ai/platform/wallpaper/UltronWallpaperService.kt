package com.ultron.ai.platform.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.ultron.ai.AssistantState
import com.ultron.ai.UltronApp
import com.ultron.ai.avatar.AvatarMood
import com.ultron.ai.avatar.gl.AvatarGlRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Live wallpaper showing the built-in ULTRON 3D avatar, rendered natively with OpenGL ES 3.0.
 *
 * - Renders only while the wallpaper is visible, at ~30 fps (RENDERMODE_WHEN_DIRTY + a timer) to save battery.
 * - Follows the assistant state of the running ULTRON app (idle/listening/thinking/speaking) when the app process is alive.
 * - Touch moves the head; home-screen page scrolling turns the camera a little.
 * - Shows the built-in model only: custom GLB avatars are supported inside the app, not here.
 * It uses no permissions beyond binding as a wallpaper, and never touches the microphone or camera.
 */
class UltronWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = GlEngine()

    private inner class GlEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = AvatarGlRenderer()
        private var view: WallpaperGlView? = null
        private var visible = false
        private var scope: CoroutineScope? = null
        private val ticker = object : Runnable {
            override fun run() {
                if (!visible) return
                view?.requestRender()
                handler.postDelayed(this, FRAME_MS)
            }
        }

        private inner class WallpaperGlView(ctx: Context) : GLSurfaceView(ctx) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() { super.onDetachedFromWindow() }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            val v = WallpaperGlView(this@UltronWallpaperService)
            v.setEGLContextClientVersion(3)
            v.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            v.preserveEGLContextOnPause = true
            v.setRenderer(renderer)
            v.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            view = v
            val app = applicationContext as? UltronApp
            if (app != null) {
                val s = CoroutineScope(Dispatchers.Main.immediate + Job())
                scope = s
                app.container.assistantState.onEach { renderer.mood = it.toMood() }.launchIn(s)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) { view?.onResume(); handler.post(ticker) }
            else { handler.removeCallbacks(ticker); view?.onPause() }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            renderer.yaw = (xOffset - .5f) * .6f
        }

        override fun onTouchEvent(event: MotionEvent) {
            val w = surfaceHolder.surfaceFrame.width().coerceAtLeast(1)
            val h = surfaceHolder.surfaceFrame.height().coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    renderer.lookX = ((event.x / w - .5f) * 2.2f).coerceIn(-1f, 1f)
                    renderer.lookY = (-(event.y / h - .5f) * 2.2f).coerceIn(-1f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { renderer.lookX = 0f; renderer.lookY = 0f }
            }
        }

        override fun onDestroy() {
            handler.removeCallbacks(ticker)
            scope?.cancel(); scope = null
            view?.destroy(); view = null
            super.onDestroy()
        }
    }

    private fun AssistantState.toMood() = when (this) {
        AssistantState.LISTENING -> AvatarMood.LISTENING
        AssistantState.THINKING -> AvatarMood.THINKING
        AssistantState.SPEAKING -> AvatarMood.SPEAKING
        else -> AvatarMood.IDLE
    }

    private companion object { const val FRAME_MS = 33L }
}
