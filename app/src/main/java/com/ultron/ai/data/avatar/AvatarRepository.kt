package com.ultron.ai.data.avatar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ultron.ai.core.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class CustomAvatar(val fileName: String, val sizeBytes: Long) {
    val id get() = AvatarIds.custom(fileName)
    val displayName get() = fileName.removeSuffix(".glb")
}

sealed interface AvatarOpResult {
    data class Ok(val avatar: CustomAvatar) : AvatarOpResult
    data class Failed(val reason: String) : AvatarOpResult
    data object Cancelled : AvatarOpResult
}

/**
 * User-supplied GLB models, stored in app-private storage (files/avatars). Nothing here claims a model is
 * licensed or safe to use: the user chooses the file or URL and is told to check the licence themselves.
 * Imports and downloads are validated (GLB header, declared length, 50 MB cap) before they become visible.
 */
class AvatarRepository(private val context: Context, private val http: OkHttpClient, private val log: DiagnosticsLog) {
    val dir: File = File(context.filesDir, "avatars").also { it.mkdirs() }
    private val _items = MutableStateFlow(scan())
    val items: StateFlow<List<CustomAvatar>> = _items

    private fun scan(): List<CustomAvatar> =
        dir.listFiles { f -> f.isFile && GlbFiles.isSafeFileName(f.name) }
            ?.sortedBy { it.name.lowercase() }?.map { CustomAvatar(it.name, it.length()) } ?: emptyList()

    fun refresh() { _items.value = scan() }

    fun exists(fileName: String) = GlbFiles.isSafeFileName(fileName) && File(dir, fileName).isFile

    fun delete(fileName: String): Boolean {
        if (!GlbFiles.isSafeFileName(fileName)) return false
        val ok = File(dir, fileName).delete()
        refresh()
        return ok
    }

    private fun uniqueName(name: String): String {
        if (!File(dir, name).exists()) return name
        val stem = name.removeSuffix(".glb")
        var i = 2
        while (File(dir, "$stem-$i.glb").exists()) i++
        return "$stem-$i.glb"
    }

    suspend fun importFrom(uri: Uri): AvatarOpResult = withContext(Dispatchers.IO) {
        val tmp = File(dir, ".import-${System.nanoTime()}.part")
        try {
            val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext AvatarOpResult.Failed("File khul nahi payi")
            val written = input.use { copyLimited(it, tmp, null) }
            commit(tmp, written, GlbFiles.sanitize(display ?: uri.lastPathSegment))
        } catch (e: IOException) {
            tmp.delete(); log.log("avatar", "import failed: ${e.javaClass.simpleName}")
            AvatarOpResult.Failed(e.message ?: "Import fail hua")
        } catch (e: SecurityException) {
            tmp.delete(); AvatarOpResult.Failed("File ki permission nahi mili")
        }
    }

    /** HTTPS only. [onProgress] gets (bytesDone, totalOrMinus1). Cancelling the calling coroutine aborts the transfer. */
    suspend fun download(url: String, onProgress: (Long, Long) -> Unit): AvatarOpResult = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!GlbFiles.isHttpsUrl(clean)) return@withContext AvatarOpResult.Failed("Sirf https:// link allowed hai")
        val req = try { Request.Builder().url(clean).header("Accept", "model/gltf-binary,application/octet-stream,*/*").build() }
        catch (e: IllegalArgumentException) { return@withContext AvatarOpResult.Failed("Link galat hai") }
        val tmp = File(dir, ".dl-${System.nanoTime()}.part")
        val call = http.newCall(req)
        val job = currentCoroutineContext().job
        val handle = job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.request.url.isHttps) return@withContext AvatarOpResult.Failed("Link http par redirect hua; roka gaya")
                if (!resp.isSuccessful) return@withContext AvatarOpResult.Failed("Server ne mana kiya (HTTP ${resp.code})")
                val body = resp.body ?: return@withContext AvatarOpResult.Failed("Khaali response")
                val total = body.contentLength()
                if (total > GlbFiles.MAX_BYTES) return@withContext AvatarOpResult.Failed("File 50 MB se badi hai")
                val written = body.byteStream().use { copyLimited(it, tmp) { done -> onProgress(done, total) } }
                commit(tmp, written, GlbFiles.sanitize(resp.request.url.pathSegments.lastOrNull()))
            }
        } catch (e: IOException) {
            tmp.delete()
            if (!job.isActive) AvatarOpResult.Cancelled
            else { log.log("avatar", "download failed: ${e.javaClass.simpleName}"); AvatarOpResult.Failed(e.message ?: "Download fail hua") }
        } finally {
            handle.dispose()
            if (tmp.exists()) tmp.delete()
        }
    }

    private suspend fun copyLimited(input: InputStream, out: File, progress: ((Long) -> Unit)?): Long {
        var total = 0L
        val buf = ByteArray(32 * 1024)
        out.outputStream().use { o: OutputStream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > GlbFiles.MAX_BYTES) throw IOException("File 50 MB se badi hai")
                o.write(buf, 0, n)
                progress?.invoke(total)
            }
        }
        return total
    }

    private fun commit(tmp: File, size: Long, wanted: String): AvatarOpResult {
        val header = ByteArray(12)
        val read = tmp.inputStream().use { it.read(header) }
        val err = GlbFiles.checkHeader(if (read < 12) header.copyOf(maxOf(read, 0)) else header, size)
        if (err != null) { tmp.delete(); return AvatarOpResult.Failed(err) }
        val name = uniqueName(wanted)
        val dest = File(dir, name)
        if (!tmp.renameTo(dest)) { tmp.delete(); return AvatarOpResult.Failed("File save nahi hui") }
        refresh()
        return AvatarOpResult.Ok(CustomAvatar(name, dest.length()))
    }
}
