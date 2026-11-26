package com.ultron.ai.domain

/**
 * Picks the first real video out of a YouTube search results page. Pure and unit-tested.
 * It reads the public search page; if YouTube changes the page the result is null and the caller falls back to the results screen.
 */
object YouTubeResults {
    private val video = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private val anyId = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    fun firstVideoId(html: String): String? {
        video.find(html)?.let { return it.groupValues[1] }
        return anyId.find(html)?.groupValues?.get(1)
    }

    data class Candidate(val id: String, val title: String, val channel: String?, val durationSec: Int?, val ageMinutes: Long?, val views: Long?)

    private val titleRe = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val ownerRe = Regex("\"(?:ownerText|longBylineText)\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val lenRe = Regex("\"simpleText\":\"(\\d{1,2}(?::\\d{2}){1,2})\"")
    private val pubRe = Regex("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"")
    private val viewRe = Regex("\"viewCountText\":\\{\"simpleText\":\"([^\"]+)\"")

    private fun unescape(t: String) = t.replace("\\\"", "\"").replace("\\\\", "\\").replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }

    /** Real videos in page order (ads/shorts shelves use other renderers). Anything not present in the page stays null: nothing is invented. */
    fun candidates(html: String, max: Int = 12): List<Candidate> {
        val marker = "\"videoRenderer\":{\"videoId\":\""
        val out = ArrayList<Candidate>()
        var i = html.indexOf(marker)
        while (i >= 0 && out.size < max) {
            val start = i + marker.length
            val next = html.indexOf(marker, start).let { if (it < 0) html.length else it }
            val chunk = html.substring(start, minOf(next, start + 6000))
            val id = chunk.take(11)
            if (Regex("^[A-Za-z0-9_-]{11}$").matches(id)) {
                val title = titleRe.find(chunk)?.groupValues?.get(1)?.let(::unescape)
                if (!title.isNullOrBlank()) out.add(Candidate(id, title, ownerRe.find(chunk)?.groupValues?.get(1)?.let(::unescape), lenRe.find(chunk)?.groupValues?.get(1)?.let(::parseDuration), pubRe.find(chunk)?.groupValues?.get(1)?.let(::parseAgeMinutes), viewRe.find(chunk)?.groupValues?.get(1)?.let(::parseViews)))
            }
            i = if (next >= html.length) -1 else next
        }
        return out
    }

    fun parseDuration(t: String): Int? { val p = t.split(":").map { it.toIntOrNull() ?: return null }; return p.fold(0) { a, v -> a * 60 + v } }

    fun parseAgeMinutes(t: String): Long? {
        val m = Regex("(\\d+)\\s+(minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE).find(t) ?: return null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2].lowercase()) { "minute" -> 1L; "hour" -> 60L; "day" -> 1440L; "week" -> 10080L; "month" -> 43200L; else -> 525600L }
    }

    fun parseViews(t: String): Long? {
        val m = Regex("([\\d,.]+)\\s*([KMB]?)\\s+views?", RegexOption.IGNORE_CASE).find(t) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return (n * when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun resultsUrl(encodedQuery: String) = "https://www.youtube.com/results?search_query=$encodedQuery"
}

/** Words of the Settings screens the toggle code looks for (per switch), lower-case. Pure data, unit-tested. */
object ToggleHints {
    fun hints(t: ToggleTarget): List<String> = when (t) {
        ToggleTarget.WIFI -> listOf("wi-fi", "wifi", "wlan")
        ToggleTarget.HOTSPOT -> listOf("hotspot", "personal hotspot")
        ToggleTarget.BLUETOOTH -> listOf("bluetooth")
        ToggleTarget.AIRPLANE -> listOf("airplane", "flight")
        ToggleTarget.MOBILE_DATA -> listOf("mobile data", "cellular data")
        ToggleTarget.LOCATION -> listOf("location", "use location", "gps")
        ToggleTarget.NFC -> listOf("nfc")
        ToggleTarget.TORCH -> emptyList()
    }
}

/** What the user asked for -> a clean search query, plus whether "latest/recent" matters. Pure. */
object YouTubeQuery {
    private val filler = setOf("mujhe", "mere", "meri", "ki", "ka", "ke", "ko", "par", "pe", "per", "me", "mein", "on", "the", "a", "an", "youtube", "yt", "video", "videos", "chalao", "chala", "do", "play", "karo", "kar", "search", "dikhao", "ek", "koi", "please")
    private val latestWords = setOf("latest", "new", "naya", "nayi", "naye", "recent", "aaj", "today", "taaza", "news")

    fun wantsLatest(raw: String) = raw.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { it in latestWords }

    fun tokens(raw: String): List<String> = raw.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in filler }

    fun clean(raw: String): String = tokens(raw).joinToString(" ").ifBlank { raw.trim() }
}

/** Picks the best video, not blindly the first: topic match first, then recency (only when asked), then mild quality signals. */
object YouTubeRanker {
    data class Pick(val candidate: YouTubeResults.Candidate, val score: Double, val reason: String)

    fun pick(query: String, cands: List<YouTubeResults.Candidate>): Pick? {
        val toks = YouTubeQuery.tokens(query).filter { it !in setOf("latest", "new", "naya", "nayi", "naye", "recent", "aaj", "today", "taaza") }
        val latest = YouTubeQuery.wantsLatest(query)
        var best: Pick? = null
        for ((idx, c) in cands.withIndex()) {
            val hay = (c.title + " " + (c.channel ?: "")).lowercase()
            val titleLow = c.title.lowercase()
            val matched = toks.count { hay.contains(it) }
            val inTitle = toks.count { titleLow.contains(it) }
            var score = if (toks.isEmpty()) 0.0 else 10.0 * matched / toks.size + 3.0 * inTitle / toks.size
            val notes = ArrayList<String>()
            notes += "topic $matched/${toks.size}"
            if (c.durationSec != null && c.durationSec < 60) { score -= 4; notes += "short clip" }
            if (c.durationSec == null) { score -= 1; notes += "live/unknown length" }
            if (latest && c.ageMinutes != null) { val days = c.ageMinutes / 1440.0; score += if (days <= 2) 4.0 else if (days <= 14) 3.0 else if (days <= 90) 1.5 else 0.0; notes += "age ${c.ageMinutes / 1440}d" }
            c.views?.let { if (it > 0) { score += minOf(1.5, Math.log10(it.toDouble()) / 5.0); } }
            score -= idx * 0.15 // tie-break toward YouTube's own relevance order
            if (best == null || score > best.score) best = Pick(c, score, notes.joinToString(", "))
        }
        return best
    }
}
