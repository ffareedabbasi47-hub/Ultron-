package com.ultron.ai.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFailureTest {
    private fun c(ex: String? = null, msg: String? = null, http: Int? = null, code: Int? = null, reason: String? = null, phase: String = "ready", net: Boolean = true) =
        LiveFailureClassifier.classify(ex, msg, http, code, reason, phase, net)

    @Test fun connectTimeoutIsRetryableAndNamedByPhase() {
        val f = c("SocketTimeoutException", "timeout", phase = "connect")
        assertEquals(LiveFailureKind.CONNECT_TIMEOUT, f.kind); assertTrue(f.retryable)
    }
    @Test fun pingPongTimeoutIsDistinguishedFromConnectTimeout() {
        val f = c("SocketTimeoutException", "sent ping but didn't receive pong within 20000ms (after 0 successful ping/pongs)", phase = "ready")
        assertEquals(LiveFailureKind.PING_TIMEOUT, f.kind); assertTrue(f.retryable)
    }
    @Test fun userTextNeverContainsRawExceptionName() {
        for (ex in listOf("SocketTimeoutException", "UnknownHostException", "SSLHandshakeException", "IOException")) {
            val f = c(ex, "x", phase = "connect")
            assertFalse(f.userText, f.userText.contains("Exception"))
            assertTrue(f.technical.contains(ex))
        }
    }
    @Test fun noInternetWinsOverTimeout() {
        val f = c("SocketTimeoutException", phase = "connect", net = false)
        assertEquals(LiveFailureKind.NO_NETWORK, f.kind); assertTrue(f.retryable)
    }
    @Test fun authModelQuotaAreNotRetried() {
        assertFalse(c(http = 403).retryable); assertEquals(LiveFailureKind.AUTH, c(http = 401).kind)
        assertEquals(LiveFailureKind.MODEL, c(http = 404).kind); assertFalse(c(http = 404).retryable)
        assertEquals(LiveFailureKind.QUOTA, c(http = 429).kind); assertFalse(c(http = 429).retryable)
        assertEquals(LiveFailureKind.QUOTA, c(code = 1011, reason = "Quota exceeded").kind)
    }
    @Test fun setupWatchdogIsItsOwnKind() {
        assertEquals(LiveFailureKind.SETUP_TIMEOUT, c(phase = "setup").kind)
    }
    @Test fun badRequestCloseIsNotRetried() {
        val f = c(code = 1007, reason = "invalid argument", phase = "setup")
        assertEquals(LiveFailureKind.SERVER_CLOSED, f.kind); assertFalse(f.retryable)
    }
}
