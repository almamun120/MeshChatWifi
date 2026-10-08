package com.meshchat.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.meshchat.core.FileMeta
import com.meshchat.core.FileNames
import com.meshchat.core.FileSink
import com.meshchat.core.FileSource
import com.meshchat.core.SinkFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A file (or photo, video, song...) the user picked, read through its content:// Uri so nothing is copied first. */
class UriFileSource(private val ctx: Context, private val uri: Uri, override val meta: FileMeta) : FileSource {
    override fun open(): InputStream = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $uri")

    companion object {
        /** Builds a source for [uri] or null when the size cannot be determined. */
        fun create(ctx: Context, uri: Uri, index: Int): UriFileSource? {
            var name = "file"
            var size = -1L
            if (uri.scheme == "file") {                       // from the in-app file manager
                val f = java.io.File(uri.path ?: return null)
                if (!f.isFile || !f.canRead()) return null
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
                return UriFileSource(ctx, uri, FileMeta(index, FileNames.sanitize(f.name), f.length(), mime))
            }
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            if (size < 0) {
                size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            }
            if (size < 0) return null
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            return UriFileSource(ctx, uri, FileMeta(index, FileNames.sanitize(name), size, mime))
        }
    }
}

/**
 * Received files land in Downloads/MeshChat (Android 10+: through MediaStore, no storage permission needed;
 * older versions: the app's own external Downloads folder). A file only appears once it is complete and verified.
 */
class DownloadsSink(private val ctx: Context) : FileSink {

    override fun create(meta: FileMeta): SinkFile {
        val tree = com.meshchat.data.AppSettings.downloadTree.value
        if (tree != null) runCatching { return TreeFile(ctx, Uri.parse(tree), meta) }   // folder gone / no access: fall back below
        return createDefault(meta)
    }

    private fun createDefault(meta: FileMeta): SinkFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreFile(ctx, meta) else PlainFile(ctx, meta)

    /** A file inside the folder the user picked in Settings (Storage Access Framework). */
    private class TreeFile(private val ctx: Context, tree: Uri, meta: FileMeta) : SinkFile {
        private val uri: Uri = android.provider.DocumentsContract.createDocument(
            ctx.contentResolver,
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)),
            meta.mime.ifBlank { "application/octet-stream" }, meta.name,
        ) ?: throw java.io.IOException("cannot create file in the chosen folder")
        override val out: OutputStream = ctx.contentResolver.openOutputStream(uri)?.buffered(1 shl 16)
            ?: throw java.io.IOException("cannot open file")

        override fun commit(): String? {
            runCatching { out.close() }
            return uri.toString()
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
        }
    }

    private class MediaStoreFile(private val ctx: Context, meta: FileMeta) : SinkFile {
        private val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, meta.name)
            put(MediaStore.MediaColumns.MIME_TYPE, meta.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MeshChat")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        private val uri: Uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("cannot create file")
        override val out: OutputStream = ctx.contentResolver.openOutputStream(uri)?.buffered(1 shl 16)
            ?: throw java.io.IOException("cannot open file")

        override fun commit(): String? {
            runCatching { out.close() }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri.toString()
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }

    private class PlainFile(ctx: Context, meta: FileMeta) : SinkFile {
        private val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "MeshChat").apply { mkdirs() }
        private val tmp = File(dir, ".part-${System.nanoTime()}")
        private val target = unique(dir, meta.name)
        override val out: OutputStream = tmp.outputStream().buffered(1 shl 16)

        override fun commit(): String? {
            runCatching { out.close() }
            return if (tmp.renameTo(target)) target.absolutePath else null
        }

        override fun abort() {
            runCatching { out.close() }
            tmp.delete()
        }

        private fun unique(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
            return f
        }
    }
}
