package com.meshchat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshchat.MainActivity
import com.meshchat.R

object Notifier {
    const val CH_SERVICE = "mesh_service"
    const val CH_MESSAGES = "mesh_messages"
    const val CH_CALLS = "mesh_calls"
    private const val ID_CALL = 2
    const val ID_SERVICE = 1
    private const val ID_MESSAGE_BASE = 1000

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, ctx.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)           // the ringtone is played by CallAlerts so it can stop the moment the call is answered
                enableVibration(false)
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun serviceNotification(ctx: Context): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, MeshService::class.java).setAction(MeshService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(ctx.getString(R.string.notif_service_title))
            .setContentText(ctx.getString(R.string.notif_service_text))
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** Message notifications are optional: silently skipped when POST_NOTIFICATIONS is not granted. */
    fun notifyMessage(ctx: Context, key: String, title: String, text: String, enabled: Boolean = com.meshchat.data.AppSettings.messageNotifications.value) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_MESSAGE_BASE + (key.hashCode() and 0xFFFF), n)
        } catch (_: SecurityException) {
        }
    }

    /** Heads-up / full-screen notification for an incoming call; tapping it opens the Answer / Decline screen. */
    fun incomingCall(ctx: Context, name: String, video: Boolean) {
        if (!com.meshchat.data.AppSettings.callNotifications.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("Incoming " + (if (video) "video call" else "call"))
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_CALL, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelIncomingCall(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_CALL)
    }
}
