package com.ultron.ai.domain.builder

import com.ultron.ai.domain.CodeExtract

data class BuiltFile(val path: String, val content: String)

/**
 * Pure helpers for the Builder agent: parsing the model's multi-file reply and refusing unsafe paths.
 * The model's output is untrusted: a path is accepted only if it is a short relative path made of safe characters.
 */
object ProjectFiles {
    const val MAX_FILES = 12
    const val MAX_FILE_CHARS = 200_000
    private val allowedExt = setOf("html", "css", "js", "json", "md", "txt", "svg", "kt", "xml", "kts", "gradle", "py")
    private val header = Regex("(?m)^=== FILE: (.+?) ===\\s*$")
    private val fenceStart = Regex("^```[a-zA-Z0-9]*\\s*\\n")

    /** Returns a normalised relative path ("css/style.css") or null when it must be refused. */
    fun safePath(raw: String): String? {
        val p = raw.trim().replace('\\', '/').removePrefix("./")
        if (p.isEmpty() || p.length > 80 || p.startsWith("/") || p.contains("//")) return null
        val parts = p.split('/')
        if (parts.size > 3) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") || !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$").matches(it) }) return null
        val ext = parts.last().substringAfterLast('.', "").lowercase()
        if (ext !in allowedExt) return null
        return p
    }

    fun parse(raw: String): List<BuiltFile> {
        val ms = header.findAll(raw).toList()
        val out = ArrayList<BuiltFile>()
        for ((i, m) in ms.withIndex()) {
            if (out.size >= MAX_FILES) break
            val path = safePath(m.groupValues[1]) ?: continue
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else raw.length
            var body = raw.substring(m.range.last + 1, end).trim('\n', '\r')
            body = body.replace(Regex("(?m)^=== END ===\\s*$"), "").trim()
            body = stripFence(body)
            if (body.isBlank() || body.length > MAX_FILE_CHARS) continue
            if (out.none { it.path == path }) out += BuiltFile(path, body)
        }
        if (out.isEmpty()) {
            val single = CodeExtract.clean(raw)
            if (CodeExtract.looksLikeHtml(single) && single.length <= MAX_FILE_CHARS) out += BuiltFile("index.html", single)
        }
        return out
    }

    private fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.replace(fenceStart, "").removeSuffix("```").trim()
    }

    fun folderName(brief: String, stamp: String): String {
        val base = brief.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28).ifEmpty { "project" }
        return "$base-$stamp"
    }
}

/** The two stages of the Builder agent: A = architecture plan, B = build. Skill texts are passed in (loaded from assets). */
object BuilderPrompts {
    const val FORMAT = "Output every file as a block: a line `=== FILE: relative/path.ext ===` followed by the complete file content. " +
        "No text outside blocks. At most 10 files, paths like index.html, css/style.css, js/app.js, README.md."

    fun planSystem(arch: String) = "You are the ARCHITECT stage of ULTRON's builder agent. Follow these rules:\n$arch\n" +
        "Write a compact plan (max 250 words): requirements, file tree, 2-3 key decisions with trade-offs, risks. Plain text, no code."

    fun buildSystem(ui: String, arch: String, plan: String) = "You are the BUILDER stage of ULTRON's builder agent. Produce a complete, working deliverable that follows the approved plan.\n\n" +
        "PLAN:\n$plan\n\nARCHITECTURE RULES:\n$arch\n\nUI/UX RULES (apply every one):\n$ui\n\n" +
        "Hard rules: no external network requests or CDNs, no secrets, no eval, escape user text, working code only (no TODO placeholders). $FORMAT"
}
