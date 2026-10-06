package com.meshchat.share

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Lets a person pass this app itself to someone who does not have it yet. The installed APK is copied to the cache
 * and offered through the system share sheet, which lists Bluetooth, Quick Share / Nearby Share (Wi-Fi Direct based),
 * WhatsApp, Telegram and anything else installed. The receiver opens the .apk to install it.
 */
object AppShare {
    fun send(ctx: Context) {
        try {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "MeshChat.apk")
            File(ctx.applicationInfo.sourceDir).copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MeshChat")
                putExtra(Intent.EXTRA_TEXT, "MeshChat: chat and call with people nearby, no internet needed.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share MeshChat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not prepare the app for sharing", Toast.LENGTH_LONG).show()
        }
    }
}
