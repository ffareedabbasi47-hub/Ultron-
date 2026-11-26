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
