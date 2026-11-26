package com.ultron.ai.domain

/** Pulls the deliverable (usually one HTML file) out of a model reply that may wrap it in markdown fences or chatter. */
object CodeExtract {
    private val fence = Regex("```[a-zA-Z0-9]*\\s*\\n([\\s\\S]*?)```")

    fun clean(raw: String): String {
        val t = raw.trim()
        val fenced = fence.findAll(t).map { it.groupValues[1].trim() }.maxByOrNull { it.length }
        val body = fenced ?: t.removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        val start = body.indexOf("<!doctype", ignoreCase = true).let { if (it >= 0) it else body.indexOf("<html", ignoreCase = true) }
        val end = body.lastIndexOf("</html>", ignoreCase = true)
        return if (start >= 0 && end > start) body.substring(start, end + 7) else body
    }

    fun looksLikeHtml(s: String) = s.contains("<html", true) || s.contains("<body", true) || s.contains("<div", true) || s.contains("<h1", true)

    fun titleOf(code: String, fallback: String): String {
        val m = Regex("<title>([^<]{1,80})</title>", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.trim()
        return (m?.takeIf { it.isNotEmpty() } ?: fallback).take(60).ifBlank { "Website" }
    }

    fun fileName(title: String, ext: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "ultron" }.take(40) + "." + ext
}
