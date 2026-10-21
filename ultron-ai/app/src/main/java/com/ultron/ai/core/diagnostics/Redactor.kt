package com.ultron.ai.core.diagnostics

/** Removes secrets and obvious personal data from anything that reaches diagnostics/logs. */
object Redactor {
    private val patterns = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("\\bsk-[A-Za-z0-9_\\-]{16,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]{12,}"),
        Regex("(?i)(api[_-]?key|token|secret|password|passwd)\\s*[=:]\\s*\\S+"),
        Regex("[A-Za-z0-9+/_\\-]{40,}={0,2}"),
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        Regex("\\+?\\d[\\d\\s\\-]{8,}\\d"),
    )

    fun redact(input: String): String = patterns.fold(input) { acc, r -> r.replace(acc, "[redacted]") }
}
