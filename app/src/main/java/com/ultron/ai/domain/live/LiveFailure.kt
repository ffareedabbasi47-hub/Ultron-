package com.ultron.ai.domain.live

/** What the user may see for a Live connection. Raw exception names never reach the UI; they go to the debug log only. */
enum class LiveStatus(val text: String) {
    CONNECTING("ULTRON se connect ho raha hai"),
    LISTENING("Sun raha hu"),
    THINKING("Soch raha hu"),
    SPEAKING("Bol raha hu"),
    RECONNECTING("Dobara connect ho raha hai"),
    NO_NETWORK("Internet nahi hai"),
    NEEDS_MIC("Microphone permission chahiye"),
    FAILED("Live connection nahi ho paya"),
}

enum class LiveFailureKind { NO_NETWORK, DNS, CONNECT_TIMEOUT, TLS, PING_TIMEOUT, SETUP_TIMEOUT, STALLED, AUTH, MODEL, QUOTA, SERVER_CLOSED, UNKNOWN }

data class LiveFailure(val kind: LiveFailureKind, val userText: String, val retryable: Boolean, val technical: String)

/**
 * Pure classification of a failed Live socket. [phase] is where the session was: "connect" (before the socket opened),
 * "setup" (opened, setupComplete not yet received) or "ready".
 * A SocketTimeoutException alone is ambiguous in OkHttp (connect / write / ping-pong), so the phase and message decide.
 */
object LiveFailureClassifier {
    fun classify(exception: String?, message: String?, httpCode: Int?, closeCode: Int?, closeReason: String?, phase: String, networkUp: Boolean): LiveFailure {
        val ex = exception.orEmpty()
        val msg = message.orEmpty()
        val tech = "phase=$phase ex=$ex msg=${msg.take(120)} http=${httpCode ?: "-"} close=${closeCode ?: "-"} ${closeReason.orEmpty().take(120)}".trim()
        fun f(k: LiveFailureKind, t: String, r: Boolean) = LiveFailure(k, t, r, tech)
        return when {
            httpCode == 401 || httpCode == 403 || closeCode == 1008 && closeReason.orEmpty().contains("key", true) ->
                f(LiveFailureKind.AUTH, "Gemini key ko Live ki permission nahi hai. Key check karo.", false)
            httpCode == 404 -> f(LiveFailureKind.MODEL, "Live model nahi mila. Model setting check karo.", false)
            httpCode == 429 || closeReason.orEmpty().contains("quota", true) || closeReason.orEmpty().contains("exhaust", true) ->
                f(LiveFailureKind.QUOTA, "Live ki limit poori ho gayi. Thodi der baad try karo.", false)
            !networkUp -> f(LiveFailureKind.NO_NETWORK, LiveStatus.NO_NETWORK.text, true)
            ex.contains("UnknownHost") -> f(LiveFailureKind.DNS, "Server ka address nahi mila (DNS). Internet check karo.", true)
            ex.contains("SSL") || ex.contains("Certificate") -> f(LiveFailureKind.TLS, "Secure connection nahi bana (TLS). Date/time aur network check karo.", true)
            ex.contains("SocketTimeout") && msg.contains("pong", true) -> f(LiveFailureKind.PING_TIMEOUT, "Connection beech me ruk gaya. Dobara jod raha hu.", true)
            ex.contains("SocketTimeout") && phase == "connect" -> f(LiveFailureKind.CONNECT_TIMEOUT, "Server tak pahunchne me der lagi. Network slow hai.", true)
            ex.contains("SocketTimeout") -> f(LiveFailureKind.STALLED, "Connection beech me ruk gaya. Dobara jod raha hu.", true)
            phase == "setup" && closeCode == null && ex.isEmpty() -> f(LiveFailureKind.SETUP_TIMEOUT, "Server ne session shuru nahi kiya.", true)
            closeCode != null && closeCode != 1000 -> f(LiveFailureKind.SERVER_CLOSED, "Server ne connection band kiya. Dobara jod raha hu.", closeCode != 1007 && closeCode != 1008)
            else -> f(LiveFailureKind.UNKNOWN, LiveStatus.FAILED.text, true)
        }
    }
}
