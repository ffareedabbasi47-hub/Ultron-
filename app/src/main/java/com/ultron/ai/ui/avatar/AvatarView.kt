package com.ultron.ai.ui.avatar

import android.annotation.SuppressLint
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.ultron.ai.AssistantState
import com.ultron.ai.data.avatar.AvatarIds
import com.ultron.ai.data.avatar.GlbFiles
import java.io.File
import com.ultron.ai.ui.LocalContainer
import org.json.JSONObject
import java.io.ByteArrayInputStream

sealed interface AvatarStatus {
    data object Loading : AvatarStatus
    data object Ready : AvatarStatus
    data class Error(val message: String) : AvatarStatus
}

private const val HOST_ROOT = "https://appassets.androidplatform.net"

/** Receives events from the page. Only our own bundled page can reach it (navigation is locked). */
private class Bridge(
    private val readModelBytes: (String) -> ByteArray?,
    // NOT named onEvent: it would be shadowed by the @JavascriptInterface method below, which then called itself forever
    // (events never reached the app, so the 3D avatar stayed on "loading" and the main thread looped).
    private val deliverEvent: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    /** Fallback when fetch() of the model fails: only safe *.glb names; from private storage or bundled assets. */
    @JavascriptInterface fun readGlbBase64(fileName: String): String = try {
        val clean = fileName.trim()
        if (!GlbFiles.isSafeFileName(clean)) "" else Base64.encodeToString(readModelBytes(clean) ?: ByteArray(0), Base64.NO_WRAP)
    } catch (_: Throwable) { "" }
    @JavascriptInterface fun onEvent(type: String, msg: String) { main.post { deliverEvent(type, msg) } }
}

private fun moodFor(s: AssistantState) = when (s) {
    AssistantState.LISTENING -> "listening"
    AssistantState.THINKING -> "thinking"
    AssistantState.SPEAKING -> "speaking"
    else -> "idle"
}

/**
 * Real-time 3D avatar (WebGL2 page bundled in assets/avatar). Shows either the built-in ULTRON model or a
 * user-imported GLB, and follows the real assistant state. The WebView is locked down: JavaScript is on
 * only for our own page, file/content access is off, every request that is not served from our private
 * https origin is answered with 403, and navigation away is blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AvatarView(
    avatarId: String,
    state: AssistantState,
    lowPerformance: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** null = automatic (follows the assistant state); otherwise one of the page's expression names. */
    expression: String? = null,
    onStatus: (AvatarStatus) -> Unit = {},
) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val owner = LocalLifecycleOwner.current
    var pageReady by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val onStatusState = androidx.compose.runtime.rememberUpdatedState(onStatus)

    val bridge = remember {
        Bridge(
            readModelBytes = { name ->
                val f = File(container.avatars.dir, name)
                if (f.isFile && f.length() > 0L) f.readBytes()
                else runCatching { context.assets.open("models/$name").use { it.readBytes() } }.getOrNull()
            },
        ) { type, msg ->
            when (type) {
                "ready" -> pageReady = true
                "modelLoaded" -> onStatusState.value(AvatarStatus.Ready)
                "error" -> onStatusState.value(AvatarStatus.Error(msg.ifBlank { "Avatar load nahi hua" }))
            }
        }
    }

    fun js(script: String) { web?.evaluateJavascript("window.UltronAvatar&&($script)", null) }

    LaunchedEffect(pageReady, avatarId) {
        if (!pageReady) return@LaunchedEffect
        val file = AvatarIds.fileOf(avatarId)
        when {
            avatarId == AvatarIds.ULTRON3D -> { onStatusState.value(AvatarStatus.Loading); js("UltronAvatar.useBuiltin()") }
            file != null && container.avatars.exists(file) -> {
                onStatusState.value(AvatarStatus.Loading)
                js("UltronAvatar.loadModelUrl(${JSONObject.quote("$HOST_ROOT/avatars/$file")})")
            }
            file != null && runCatching { context.assets.open("models/$file").close() }.isSuccess -> {
                onStatusState.value(AvatarStatus.Loading)
                js("UltronAvatar.loadModelUrl(${JSONObject.quote("$HOST_ROOT/assets/models/$file")})")
            }
            else -> onStatusState.value(AvatarStatus.Error("Model file device par nahi mili"))
        }
    }
    LaunchedEffect(pageReady, state) { if (pageReady) js("UltronAvatar.setState('${moodFor(state)}')") }
    // Real audio-driven lip-sync from the online voice's playback position; falls back to the page's approximate motion.
    val mouth by container.cloudVoice.mouth.collectAsState()
    LaunchedEffect(pageReady, mouth) {
        if (!pageReady) return@LaunchedEffect
        if (mouth.live) js("UltronAvatar.setMouthFrame(${mouth.open}, ${mouth.shape})") else js("UltronAvatar.setMouth(null)")
    }
    LaunchedEffect(pageReady, expression) { if (pageReady) js("UltronAvatar.setExpression(${if (expression == null) "null" else JSONObject.quote(expression)})") }
    LaunchedEffect(pageReady, lowPerformance) { if (pageReady) js("UltronAvatar.setQuality('${if (lowPerformance) "low" else "high"}')") }
    LaunchedEffect(pageReady, reducedMotion) { if (pageReady) js("UltronAvatar.setReduced($reducedMotion)") }

    DisposableEffect(owner, web) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> web?.onPause()
                Lifecycle.Event.ON_RESUME -> web?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .addPathHandler("/avatars/", WebViewAssetLoader.InternalStoragePathHandler(ctx, container.avatars.dir))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                with(settings) {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setSupportZoom(false)
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                addJavascriptInterface(bridge, "AndroidAvatar")
                loadUrl("$HOST_ROOT/assets/avatar/ultron-avatar.html?embed=1&low=${if (lowPerformance) 1 else 0}&reduced=${if (reducedMotion) 1 else 0}")
                web = this
            }
        },
        onRelease = { w ->
            w.removeJavascriptInterface("AndroidAvatar")
            w.stopLoading()
            w.destroy()
            if (web === w) web = null
        },
    )
}
