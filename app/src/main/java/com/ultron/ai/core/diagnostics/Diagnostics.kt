package com.ultron.ai.core.diagnostics

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory ring buffer of redacted log lines (never written to disk, never contains prompts). */
class DiagnosticsLog(private val capacity: Int = 200) {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] ${Redactor.redact(message)}"
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
        _entries.value = lines.toList()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _entries.value = emptyList()
    }
}

data class LatencySnapshot(
    val samples: Int = 0,
    val avgFirstTokenMs: Long? = null,
    val avgFirstAudioMs: Long? = null,
    val avgTotalMs: Long? = null,
    val errors: Int = 0,
    val lastUsageTotalTokens: Int? = null,
)

/** Measured-only metrics. Anything never measured stays null; nothing is estimated or invented. */
class LatencyStats(private val window: Int = 30) {
    private val ttft = ArrayDeque<Long>()
    private val ttfa = ArrayDeque<Long>()
    private val total = ArrayDeque<Long>()
    private var errors = 0
    private var lastUsage: Int? = null
    private val _snapshot = MutableStateFlow(LatencySnapshot())
    val snapshot: StateFlow<LatencySnapshot> = _snapshot

    @Synchronized fun recordFirstToken(ms: Long) { push(ttft, ms); publish() }
    @Synchronized fun recordFirstAudio(ms: Long) { push(ttfa, ms); publish() }
    @Synchronized fun recordTotal(ms: Long, usageTotalTokens: Int?) {
        push(total, ms)
        if (usageTotalTokens != null) lastUsage = usageTotalTokens
        publish()
    }
    @Synchronized fun recordError() { errors++; publish() }

    private fun push(q: ArrayDeque<Long>, v: Long) {
        if (q.size >= window) q.removeFirst()
        q.addLast(v)
    }

    private fun avg(q: ArrayDeque<Long>): Long? = if (q.isEmpty()) null else q.sum() / q.size

    private fun publish() {
        _snapshot.value = LatencySnapshot(total.size, avg(ttft), avg(ttfa), avg(total), errors, lastUsage)
    }
}
