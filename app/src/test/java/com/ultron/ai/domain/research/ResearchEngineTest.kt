package com.ultron.ai.domain.research

import com.ultron.ai.domain.WebSearchWire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResearchEngineTest {
    private val ok = WebSearchWire.Result("fact", listOf(WebSearchWire.Source("S", "https://s.com")))

    private class P(override val name: String, val outcomes: MutableList<ProviderOutcome>) : SearchProvider {
        var calls = 0
        override suspend fun search(query: String): ProviderOutcome { calls++; return if (outcomes.size > 1) outcomes.removeAt(0) else outcomes[0] }
    }
    private fun engine(vararg p: SearchProvider, t: () -> Long = { 0L }, sleeps: MutableList<Long> = ArrayList()) =
        ResearchEngine(p.toList(), now = t, sleep = { sleeps += it })

    @Test fun primaryOkNoFallbackUsed() = runBlocking {
        val a = P("A", mutableListOf(ProviderOutcome.Ok(ok))); val b = P("B", mutableListOf(ProviderOutcome.Ok(ok)))
        val r = engine(a, b).research("q") as ResearchResult.Answer
        assertEquals("A", r.provider); assertEquals(0, b.calls)
    }
    @Test fun primaryQuotaFallsBackToSecondary() = runBlocking {
        val a = P("A", mutableListOf(ProviderOutcome.Quota("429"))); val b = P("B", mutableListOf(ProviderOutcome.Ok(ok)))
        val r = engine(a, b).research("q") as ResearchResult.Answer
        assertEquals("B", r.provider); assertEquals(1, a.calls) // quota is NOT retried / bypassed
        assertTrue(r.attempts.first().outcome.startsWith("quota"))
    }
    @Test fun allUnavailableIsReportedNotInvented() = runBlocking {
        val a = P("A", mutableListOf(ProviderOutcome.Quota("x"))); val b = P("B", mutableListOf(ProviderOutcome.NotConfigured("no key")))
        val r = engine(a, b).research("q")
        assertTrue(r is ResearchResult.Unavailable)
        assertEquals(2, (r as ResearchResult.Unavailable).attempts.size)
    }
    @Test fun transientRetriesWithExponentialBackoff() = runBlocking {
        val sl = ArrayList<Long>()
        val a = P("A", mutableListOf(ProviderOutcome.Transient("net"), ProviderOutcome.Transient("net"), ProviderOutcome.Ok(ok)))
        val r = engine(a, sleeps = sl).research("q") as ResearchResult.Answer
        assertEquals(3, a.calls); assertEquals(listOf(600L, 1200L), sl)
    }
    @Test fun transientGivesUpThenNextProvider() = runBlocking {
        val a = P("A", mutableListOf(ProviderOutcome.Transient("net")))
        val b = P("B", mutableListOf(ProviderOutcome.Ok(ok)))
        assertEquals("B", (engine(a, b).research("q") as ResearchResult.Answer).provider)
        assertEquals(3, a.calls) // 1 try + 2 retries, then moves on
    }
    @Test fun duplicateQueryServedFromCache() = runBlocking {
        val a = P("A", mutableListOf(ProviderOutcome.Ok(ok)))
        val e = engine(a)
        e.research("Transformer  Safety"); val r = e.research("transformer safety") as ResearchResult.Answer
        assertTrue(r.cached); assertEquals(1, a.calls)
    }
    @Test fun cacheExpires() = runBlocking {
        var t = 0L; val a = P("A", mutableListOf(ProviderOutcome.Ok(ok)))
        val e = engine(a, t = { t })
        e.research("q"); t = 11 * 60_000L; e.research("q")
        assertEquals(2, a.calls)
    }
    @Test fun quotaProviderRestsThenReturns() = runBlocking {
        var t = 0L
        val a = P("A", mutableListOf(ProviderOutcome.Quota("x"), ProviderOutcome.Ok(ok))); val b = P("B", mutableListOf(ProviderOutcome.Ok(ok)))
        val e = engine(a, b, t = { t })
        e.research("one"); e.research("two") // A rests: not called again
        assertEquals(1, a.calls)
        t = 3 * 60_000L; e.research("three")
        assertEquals(2, a.calls)
    }
    @Test fun wikipediaParseKeepsUrlsAndRejectsEmpty() {
        val j = """{"query":{"pages":{"1":{"title":"Transformer","index":1,"extract":"A transformer transfers energy.","fullurl":"https://en.wikipedia.org/wiki/Transformer"}}}}"""
        val r = WikipediaWire.parse(j)!!
        assertEquals("https://en.wikipedia.org/wiki/Transformer", r.sources.single().url)
        assertNull(WikipediaWire.parse("""{"batchcomplete":""}"""))
        assertEquals("hi", WikipediaWire.langFor("ट्रांसफार्मर"))
    }
}
