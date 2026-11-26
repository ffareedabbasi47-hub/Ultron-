package com.ultron.ai.platform.builder

import android.content.Context
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import com.ultron.ai.data.ai.GeminiOneShot
import com.ultron.ai.data.db.WorkDocDao
import com.ultron.ai.data.db.WorkDocEntity
import com.ultron.ai.data.settings.SettingsRepository
import com.ultron.ai.domain.CodeExtract
import com.ultron.ai.domain.builder.BuilderPrompts
import com.ultron.ai.domain.builder.ProjectFiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BuildStage(val label: String) {
    QUEUED("Line mein"), PLAN("Stage A: architecture plan"), BUILD("Stage B: files ban rahi hain"),
    SAVING("Save ho raha hai"), DONE("Ho gaya"), FAILED("Fail"), CANCELLED("Cancel")
}

data class BuildJob(
    val id: Long, val brief: String, val stage: BuildStage = BuildStage.QUEUED,
    val message: String = "", val files: List<String> = emptyList(), val where: String = "", val plan: String = "",
) { val finished get() = stage == BuildStage.DONE || stage == BuildStage.FAILED || stage == BuildStage.CANCELLED }

/**
 * The second agent. The voice agent only hands it a brief; it works in the background (one job at a time):
 * Stage A writes an architecture plan, Stage B writes the files following the plan plus the bundled UI/UX and
 * architecture rules (assets/builder). Output goes to the user's chosen folder. Needs Cloud AI consent + Gemini key.
 */
class BuilderAgent(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val ai: GeminiOneShot,
    private val docs: WorkDocDao,
    private val store: BuilderStore,
    private val diagnostics: DiagnosticsLog,
    /** Called once per finished job (success or failure) so the app can speak / notify. */
    private val onFinished: (BuildJob) -> Unit,
) {
    private val _jobs = MutableStateFlow<List<BuildJob>>(emptyList())
    val jobs: StateFlow<List<BuildJob>> = _jobs
    private val lock = Mutex()
    private val running = HashMap<Long, Job>()
    private var nextId = 1L

    private fun asset(name: String) = runCatching { context.assets.open("builder/$name").bufferedReader().use { it.readText() } }.getOrDefault("")

    /** Returns the job id, or null when the brief is unusable. */
    fun submit(brief: String): Long? {
        val b = brief.trim().take(600)
        if (b.length < 6) return null
        val id = synchronized(this) { nextId++ }
        _jobs.update { (listOf(BuildJob(id, b)) + it).take(20) }
        running[id] = scope.launch(Dispatchers.Default) { lock.withLock { run(id, b) } }
        return id
    }

    fun cancel(id: Long) { running[id]?.cancel(); set(id) { it.copy(stage = BuildStage.CANCELLED, message = "Cancel kiya.") } }

    fun clearFinished() { _jobs.update { l -> l.filterNot { it.finished } } }

    private fun set(id: Long, f: (BuildJob) -> BuildJob) { _jobs.update { l -> l.map { if (it.id == id && !it.finished) f(it) else it } } }

    private suspend fun run(id: Long, brief: String) {
        try {
            set(id) { it.copy(stage = BuildStage.PLAN, message = "Architecture soch raha hoon…") }
            val arch = asset("skill_architecture.md"); val ui = asset("skill_ui_ux.md")
            val plan = ai.generate(BuilderPrompts.planSystem(arch), "Brief: $brief", maxTokens = 900, timeoutSec = 90)?.trim()
            if (plan.isNullOrBlank()) return fail(id, ai.lastError ?: "Plan nahi bana.")
            set(id) { it.copy(stage = BuildStage.BUILD, plan = plan, message = "Plan ready. Files likh raha hoon…") }
            val raw = ai.generate(BuilderPrompts.buildSystem(ui, arch, plan), "Brief: $brief", maxTokens = 16000, timeoutSec = 180)
            if (raw.isNullOrBlank()) return fail(id, ai.lastError ?: "Files nahi bani.")
            val files = ProjectFiles.parse(raw)
            if (files.isEmpty()) return fail(id, "Model ka jawab file format mein nahi tha. Dobara try karo.")
            set(id) { it.copy(stage = BuildStage.SAVING, message = "${files.size} files save kar raha hoon…") }
            val s = settings.settings.first()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val saved = withContext(Dispatchers.IO) {
                store.save(s.builderFolderUri, ProjectFiles.folderName(brief, stamp), files)
            }
            files.firstOrNull { it.path == "index.html" }?.let { f ->
                val now = System.currentTimeMillis()
                runCatching { docs.insert(WorkDocEntity(kind = "code", title = CodeExtract.titleOf(f.content, brief), body = f.content, createdAt = now, updatedAt = now)) }
            }
            val done = BuildJob(id, brief, BuildStage.DONE, "Ho gaya: ${saved.count} files, ${saved.where}.", files.map { it.path }, saved.where, plan)
            _jobs.update { l -> l.map { if (it.id == id) done else it } }
            onFinished(done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log("builder", "job failed ${e.javaClass.simpleName}")
            fail(id, "Save/build mein error: ${e.javaClass.simpleName}")
        } finally { running.remove(id) }
    }

    private fun fail(id: Long, why: String) {
        var snap: BuildJob? = null
        _jobs.update { l -> l.map { if (it.id == id && !it.finished) it.copy(stage = BuildStage.FAILED, message = why).also { j -> snap = j } else it } }
        snap?.let(onFinished)
    }
}
