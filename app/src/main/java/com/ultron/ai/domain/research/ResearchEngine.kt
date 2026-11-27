package com.ultron.ai.domain.research

import com.ultron.ai.domain.WebSearchWire
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ProviderOutcome {
    data class Ok(val result: WebSearchWire.Result) : ProviderOutcome
    /** Quota / rate limit of THIS provider. Never bypassed: the engine moves to the next provider. */
    data class Quota(val message: String) : ProviderOutcome
    data class Transient(val message: String) : ProviderOutcome
    data class NotConfigured(val message: String) : ProviderOutcome
    data class Failed(val message: String) : ProviderOutcome
}

interface SearchProvider {
    val name: String
    suspend fun search(query: String): ProviderOutcome
}

data class Attempt(val provider: String, val outcome: String)

sealed interface ResearchResult {
    data class Answer(val provider: String, val result: WebSearchWire.Result, val cached: Boolean, val attempts: List<Attempt>) : ResearchResult
    data class Unavailable(val attempts: List<Attempt>) : ResearchResult
}

/**
 * Research is independent of the Gemini Live conversation (its session/token limits are a different thing).
 * Providers are tried in order. Quota -> next provider (and that provider rests for [quotaCooldownMs]);
 * transient errors -> exponential backoff retries, then next provider; identical queries are served from a short cache
 * and run one at a time (no duplicate requests). If nothing works the result is Unavailable: nothing is invented.
 */
class ResearchEngine(
    private val providers: List<SearchProvider>,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val ttlMs: Long = 10 * 60_000L,
    private val maxRetries: Int = 2,
    private val baseBackoffMs: Long = 600L,
    private val quotaCooldownMs: Long = 2 * 60_000L,
) {
    private val lock = Mutex()
    private val cache = HashMap<String, Pair<Long, Pair<String, WebSearchWire.Result>>>()
    private val restingUntil = HashMap<String, Long>()

    private fun key(q: String) = q.lowercase().replace(Regex("\\s+"), " ").trim()

    suspend fun research(query: String): ResearchResult = lock.withLock {
        val k = key(query)
        cache[k]?.let { (t, v) -> if (now() - t <= ttlMs) return ResearchResult.Answer(v.first, v.second, true, emptyList()) }
        val attempts = ArrayList<Attempt>()
        for (p in providers) {
            if ((restingUntil[p.name] ?: 0L) > now()) { attempts += Attempt(p.name, "quota cooldown: skipped"); continue }
            var tries = 0
            while (true) {
                when (val o = p.search(query)) {
                    is ProviderOutcome.Ok -> {
                        cache[k] = now() to (p.name to o.result)
                        attempts += Attempt(p.name, "ok")
                        return ResearchResult.Answer(p.name, o.result, false, attempts)
                    }
                    is ProviderOutcome.Quota -> { restingUntil[p.name] = now() + quotaCooldownMs; attempts += Attempt(p.name, "quota: ${o.message}"); break }
                    is ProviderOutcome.NotConfigured -> { attempts += Attempt(p.name, "not configured: ${o.message}"); break }
                    is ProviderOutcome.Failed -> { attempts += Attempt(p.name, "failed: ${o.message}"); break }
                    is ProviderOutcome.Transient -> {
                        if (tries >= maxRetries) { attempts += Attempt(p.name, "transient after ${tries + 1} tries: ${o.message}"); break }
                        sleep(baseBackoffMs shl tries); tries++
                    }
                }
            }
        }
        ResearchResult.Unavailable(attempts)
    }
}
