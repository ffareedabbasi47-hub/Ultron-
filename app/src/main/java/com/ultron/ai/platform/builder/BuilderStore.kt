package com.ultron.ai.platform.builder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ultron.ai.domain.builder.BuiltFile
import java.io.File

/** Where a finished project went, in words the user can follow. */
data class SavedProject(val where: String, val count: Int)

/**
 * Saves a project into the folder the user chose (Storage Access Framework, persisted permission). With no folder, or
 * when the folder is no longer writable, it falls back to the app's private "builds" folder and says so honestly.
 */
class BuilderStore(private val context: Context) {
    private val privateRoot = File(context.filesDir, "builds").also { it.mkdirs() }

    fun save(treeUri: String, folder: String, files: List<BuiltFile>): SavedProject {
        if (treeUri.isNotBlank()) runCatching { return saveToTree(Uri.parse(treeUri), folder, files) }
        return saveToPrivate(folder, files, treeUri.isNotBlank())
    }

    private fun saveToTree(tree: Uri, folder: String, files: List<BuiltFile>): SavedProject {
        val cr = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projDir = DocumentsContract.createDocument(cr, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, folder)
            ?: error("folder create failed")
        val dirs = HashMap<String, Uri>()
        var n = 0
        for (f in files) {
            val parts = f.path.split('/')
            var parent = projDir
            var key = ""
            for (d in parts.dropLast(1)) {
                key += "/$d"
                parent = dirs.getOrPut(key) { DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, d) ?: error("dir") }
            }
            val name = parts.last()
            val doc = DocumentsContract.createDocument(cr, parent, mimeOf(name), name) ?: error("file create failed")
            cr.openOutputStream(doc, "wt")?.use { it.write(f.content.toByteArray(Charsets.UTF_8)) } ?: error("open failed")
            n++
        }
        return SavedProject("aapka chuna hua folder → $folder", n)
    }

    private fun saveToPrivate(folder: String, files: List<BuiltFile>, folderFailed: Boolean): SavedProject {
        val root = File(privateRoot, folder).canonicalFile
        require(root.path.startsWith(privateRoot.canonicalPath)) { "bad folder" }
        var n = 0
        for (f in files) {
            val target = File(root, f.path).canonicalFile
            if (!target.path.startsWith(root.path)) continue
            target.parentFile?.mkdirs()
            target.writeText(f.content, Charsets.UTF_8)
            n++
        }
        val why = if (folderFailed) "chuna hua folder likh nahi paya, isliye " else "folder set nahi hai, isliye "
        return SavedProject("${why}app ke andar (builds/$folder). Builder screen se share/export karo", n)
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"; "json" -> "application/json"
        "svg" -> "image/svg+xml"; "xml" -> "text/xml"; else -> "text/plain"
    }
}
