package com.ultron.ai.data.avatar

import java.util.Locale

/** Ids stored in settings. A custom avatar id is "custom:<file name>.glb". */
object AvatarIds {
    const val CORE = "core"
    const val ULTRON3D = "ultron3d"
    const val CUSTOM_PREFIX = "custom:"

    fun custom(fileName: String) = CUSTOM_PREFIX + fileName
    fun isCustom(id: String) = id.startsWith(CUSTOM_PREFIX)
    fun fileOf(id: String): String? = if (isCustom(id)) id.removePrefix(CUSTOM_PREFIX).takeIf(GlbFiles::isSafeFileName) else null
}

/** Pure validation helpers for user-supplied GLB files (JVM unit-tested). */
object GlbFiles {
    const val MAX_BYTES = 50L * 1024 * 1024
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,59}\\.glb$")

    /** True only for names we generated ourselves: no separators, no traversal, .glb suffix. */
    fun isSafeFileName(name: String): Boolean = SAFE.matches(name) && !name.contains("..")

    /** "My Model (v2).GLB" -> "My-Model-v2.glb" */
    fun sanitize(raw: String?): String {
        var base = (raw ?: "").trim().substringAfterLast('/').substringAfterLast('\\').substringBefore('?').substringBefore('#')
        if (base.lowercase(Locale.ROOT).endsWith(".glb")) base = base.dropLast(4)
        base = base.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_')
        if (base.isEmpty()) base = "model"
        return base.take(40).trim('-', '.', '_').ifEmpty { "model" } + ".glb"
    }

    /** Header = first 12 bytes of a glTF binary: magic "glTF", version 2, total length. */
    fun checkHeader(header: ByteArray, actualSize: Long): String? {
        if (header.size < 12) return "File bahut chhoti hai"
        val magic = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or ((header[3].toInt() and 0xFF) shl 24)
        if (magic != 0x46546C67) return "Ye GLB (glTF binary) file nahi hai"
        val version = le32(header, 4)
        if (version != 2L) return "Sirf glTF 2.0 GLB supported hai"
        val declared = le32(header, 8)
        if (declared != actualSize) return "GLB adhoori ya kharab hai (download poora nahi hua?)"
        if (actualSize > MAX_BYTES) return "File 50 MB se badi hai"
        return null
    }

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))

    fun isHttpsUrl(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true) && !url.contains(' ')

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
