package com.ultron.ai.data


/** Result of one reset step, shown to the user as it really happened. */
data class ResetStep(val name: String, val ok: Boolean, val detail: String? = null)

/**
 * "Sab kuch reset karo": every step runs independently and reports its own real outcome, so one failure never
 * hides the others and the UI never says "done" for something that failed.
 */
object ResetEverything {
    class Step(val name: String, val action: suspend () -> Boolean)

    suspend fun run(steps: List<Step>): List<ResetStep> = steps.map { st ->
        val name = st.name
        try { ResetStep(name, st.action()) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ResetStep(name, false, e.javaClass.simpleName) }
    }

    fun summary(results: List<ResetStep>): String {
        val failed = results.filter { !it.ok }
        return if (failed.isEmpty()) "Sab kuch reset ho gaya (${results.size}/${results.size} steps)."
        else "Reset adhura raha: ${failed.joinToString { it.name }} nahi hua. Baaki ${results.size - failed.size} steps ho gaye."
    }

    fun deleteRecursively(dir: java.io.File?, keep: (java.io.File) -> Boolean = { false }): Boolean {
        if (dir == null || !dir.exists()) return true
        var ok = true
        dir.listFiles()?.forEach { f -> if (!keep(f) && !f.deleteRecursively()) ok = false }
        return ok
    }
}
