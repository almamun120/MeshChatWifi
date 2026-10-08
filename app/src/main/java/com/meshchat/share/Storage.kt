package com.meshchat.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** One browsable storage: the phone's internal storage, an SD card or a USB drive. */
data class StorageRoot(val label: String, val dir: File)

object Storage {
    /** Internal storage first, then every SD card / USB volume the system currently mounts. */
    fun roots(ctx: Context): List<StorageRoot> {
        val out = mutableListOf<StorageRoot>()
        ContextCompat.getExternalFilesDirs(ctx, null).forEachIndexed { i, d ->
            if (d == null) return@forEachIndexed
            val root = File(d.absolutePath.substringBefore("/Android/data"))
            if (!root.exists()) return@forEachIndexed
            val label = when {
                i == 0 -> "Internal storage"
                runCatching { Environment.isExternalStorageRemovable(d) }.getOrDefault(true) -> "SD card / USB (${root.name})"
                else -> "Storage ${i + 1} (${root.name})"
            }
            out += StorageRoot(label, root)
        }
        if (out.isEmpty()) out += StorageRoot("Internal storage", Environment.getExternalStorageDirectory())
        return out.distinctBy { it.dir.path }
    }

    /** Browsing every folder needs "All files access" on Android 11+, plain storage permission before that. */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Opens the system screen for "All files access" (Android 11+). Returns false before that: ask the runtime permission instead. */
    fun openAccessSettings(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }
}
