package com.ultron.ai.domain

import com.ultron.ai.platform.voice.VoiceInfo
import com.ultron.ai.platform.voice.VoiceRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SmallTalkAndVoiceTest {
    private val st = SmallTalk("Fardeen") { LocalDateTime.of(2026, 10, 23, 15, 5) }

    @Test fun timeUsesClock() { assertTrue(st.respond("time kya hua")!!.contains("3:05 PM")) }
    @Test fun dateUsesClock() { assertTrue(st.respond("aaj ki date")!!.contains("Friday, 23 October 2026")) }
    @Test fun whoAreMentionsCreator() { assertTrue(st.respond("tum kaun ho")!!.contains("Fardeen")) }
    @Test fun greetingAnswered() { assertNotNull(st.respond("hello ultron")) }
    @Test fun realQuestionsFallThrough() {
        assertNull(st.respond("photosynthesis kya hota hai samjhao"))
        assertNull(st.respond("a".repeat(200)))
    }

    private fun v(n: String, l: String, q: Int, net: Boolean, inst: Boolean) = VoiceInfo(n, l, q, net, inst)

    @Test fun prefersOnlineIndianEnglish() {
        val list = listOf(v("us-local", "en-US", 400, false, true), v("in-net", "en-IN", 400, true, true), v("fr", "fr-FR", 500, true, true))
        assertEquals("in-net", VoiceRanker.best(list, "en-IN")!!.name)
    }
    @Test fun hindiPreferenceWins() {
        val list = listOf(v("in-net", "en-IN", 400, true, true), v("hi-net", "hi-IN", 400, true, true))
        assertEquals("hi-net", VoiceRanker.best(list, "hi-IN")!!.name)
    }
    @Test fun otherLanguagesAndUninstalledOfflineHidden() {
        val list = listOf(v("fr", "fr-FR", 500, true, true), v("notinstalled", "en-IN", 500, false, false))
        assertNull(VoiceRanker.best(list, "en-IN"))
        assertTrue(VoiceRanker.shortlist(list, "en-IN").isEmpty())
    }
    @Test fun shortlistLimitedAndOrdered() {
        val list = (1..10).map { v("n$it", "en-IN", 400, true, true) } + v("us", "en-US", 400, false, true)
        assertEquals(6, VoiceRanker.shortlist(list, "en-IN").size)
    }
}
