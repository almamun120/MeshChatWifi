package com.meshchat.share

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat

enum class MediaKind { PHOTO, VIDEO, AUDIO, FILE }

/** One selectable item in the file browser. */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val kind: MediaKind,
    val dateMs: Long,
    val durationMs: Long = 0,
)

/** Lists photos, videos and music from MediaStore. Documents and anything else come from the system file picker. */
object MediaBrowser {

    fun permissionsFor(kind: MediaKind): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when (kind) {
            MediaKind.PHOTO -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaKind.VIDEO -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaKind.AUDIO -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
            MediaKind.FILE -> emptyList()
        }
        kind == MediaKind.FILE -> emptyList()
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPermission(ctx: Context, kind: MediaKind): Boolean =
        permissionsFor(kind).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun load(ctx: Context, kind: MediaKind, limit: Int = 3000): List<MediaItem> {
        val base = when (kind) {
            MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.FILE -> return emptyList()
        }
        val cols = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (kind != MediaKind.PHOTO) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = ArrayList<MediaItem>()
        runCatching {
            ctx.contentResolver.query(base, cols, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    out += MediaItem(
                        uri = ContentUris.withAppendedId(base, c.getLong(0)),
                        name = c.getString(1) ?: "file",
                        size = size,
                        kind = kind,
                        dateMs = c.getLong(3) * 1000,
                        durationMs = if (kind != MediaKind.PHOTO) c.getLong(4) else 0,
                    )
                }
            }
        }
        return out
    }

    /** Builds an item for something the user picked with the system file picker. */
    fun fromUri(ctx: Context, uri: Uri): MediaItem? {
        val src = UriFileSource.create(ctx, uri, 0) ?: return null
        val mime = src.meta.mime
        val kind = when {
            mime.startsWith("image/") -> MediaKind.PHOTO
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            else -> MediaKind.FILE
        }
        return MediaItem(uri, src.meta.name, src.meta.size, kind, System.currentTimeMillis())
    }

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Small thumbnail (Android 10+ only; older versions show an icon). */
    fun thumbnail(cr: ContentResolver, item: MediaItem): Bitmap? {
        if (item.kind == MediaKind.FILE || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val key = item.uri.toString()
        cache.get(key)?.let { return it }
        return runCatching { cr.loadThumbnail(item.uri, Size(240, 240), null) }.getOrNull()?.also { cache.put(key, it) }
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun formatSpeed(bps: Long): String = if (bps <= 0) "—" else formatBytes(bps) + "/s"

fun formatEta(sec: Long): String = when {
    sec < 0 -> "—"
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}
