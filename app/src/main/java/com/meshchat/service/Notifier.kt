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
    const val CH_SOS = "mesh_sos"
    private const val ID_CALL = 2
    const val ID_SERVICE = 1
    private const val ID_MESSAGE_BASE = 1000
    private const val ID_SOS_BASE = 200000

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

    /**
     * A notification channel's sound cannot be changed once created, so every tone gets its own channel
     * (id = mesh_sos_<tone>); channels of tones that are no longer selected are removed. Returns the channel id.
     */
    fun createSosChannel(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CH_SOS
        val tone = com.meshchat.data.AppSettings.sosTone.value
        val id = CH_SOS + "_" + when (tone) { null -> "default"; "" -> "silent"; else -> Integer.toHexString(tone.hashCode()) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notificationChannels.filter { it.id.startsWith(CH_SOS) && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
        if (nm.getNotificationChannel(id) == null) {
            val sound: android.net.Uri? = when (tone) {
                null -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                "" -> null
                else -> android.net.Uri.parse(tone)
            }
            nm.createNotificationChannel(
                NotificationChannel(id, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                    setBypassDnd(true)
                    setSound(
                        sound,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }
        return id
    }

    /** Loud heads-up notification for someone's SOS; an "I'm safe" update replaces it quietly. */
    fun notifySos(ctx: Context, nodeId: String, name: String, active: Boolean, detail: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = createSosChannel(ctx)
        val open = openApp(ctx)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(if (active) "🆘 SOS from $name" else "✅ $name is safe")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (active) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (active) b.setFullScreenIntent(open, true)
        try {
            NotificationManagerCompat.from(ctx).notify(ID_SOS_BASE + (nodeId.hashCode() and 0xFFFF), b.build())
        } catch (_: SecurityException) {
        }
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
    fun incomingCall(ctx: Context, name: String, video: Boolean, ptt: Boolean = false) {
        if (!com.meshchat.data.AppSettings.callNotifications.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = openApp(ctx)
        val screen = callScreenIntent(ctx)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(if (ptt) "Walkie-talkie request" else "Incoming " + (if (video) "video call" else "call"))
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_CALL, n)
        } catch (_: SecurityException) {
        }
        // A full-screen intent only takes over the screen when the phone is locked or off; while the phone is in use
        // Android shows a small banner. With "display over other apps" allowed the call screen is opened directly.
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(callActivityIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    private fun callActivityIntent(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_INCOMING_CALL, true)

    private fun callScreenIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 2, callActivityIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** True when the system lets this app take over the screen for a call (Android 14+ can revoke it). */
    fun canShowFullScreenCall(ctx: Context): Boolean =
        android.provider.Settings.canDrawOverlays(ctx) ||
            (Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())

    fun cancelIncomingCall(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_CALL)
    }
}
