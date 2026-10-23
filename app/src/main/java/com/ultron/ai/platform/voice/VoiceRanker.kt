package com.ultron.ai.platform.voice

data class VoiceInfo(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

/**
 * Picks the voices worth showing. "Good" means usable now (installed, or an online voice) and either an
 * online voice or high quality, in a language ULTRON actually speaks (Indian English reads Roman Hinglish best,
 * then Hindi, then other English). Everything else is hidden instead of cluttering the list.
 */
object VoiceRanker {
    private fun localeScore(tag: String, prefer: String): Int {
        val t = tag.replace('_', '-').lowercase()
        val hindiFirst = prefer.lowercase().startsWith("hi")
        return when {
            t.startsWith("en-in") -> if (hindiFirst) 55 else 70
            t.startsWith("hi") -> if (hindiFirst) 80 else 50
            t.startsWith("en-gb") -> 30
            t.startsWith("en-au") -> 26
            t.startsWith("en-us") -> 25
            t.startsWith("en") -> 15
            else -> 0
        }
    }

    fun score(v: VoiceInfo, preferTag: String): Int {
        val loc = localeScore(v.localeTag, preferTag)
        if (loc == 0) return Int.MIN_VALUE
        var s = loc + (v.quality / 100) * 8
        if (v.needsNetwork) s += 25
        if (v.name.contains("network", ignoreCase = true)) s += 5
        if (!v.installed && !v.needsNetwork) s -= 60
        return s
    }

    fun isGood(v: VoiceInfo): Boolean =
        localeScore(v.localeTag, "en") > 0 && (v.installed || v.needsNetwork) && (v.needsNetwork || v.quality >= 400)

    fun best(voices: List<VoiceInfo>, preferTag: String): VoiceInfo? =
        voices.filter { score(it, preferTag) != Int.MIN_VALUE && (it.installed || it.needsNetwork) }
            .maxByOrNull { score(it, preferTag) }

    /** Up to [limit] good voices, best first. Falls back to the best usable ones if none qualify. */
    fun shortlist(voices: List<VoiceInfo>, preferTag: String, limit: Int = 6): List<VoiceInfo> {
        val good = voices.filter(::isGood).sortedByDescending { score(it, preferTag) }
        if (good.isNotEmpty()) return good.take(limit)
        return voices.filter { score(it, preferTag) != Int.MIN_VALUE && (it.installed || it.needsNetwork) }
            .sortedByDescending { score(it, preferTag) }.take(limit)
    }
}
