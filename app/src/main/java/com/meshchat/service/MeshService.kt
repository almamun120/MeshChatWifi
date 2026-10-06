package com.meshchat.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.meshchat.MeshChatApp

/**
 * Foreground service (type connectedDevice) that keeps the BLE mesh alive while the app is in the background.
 * Android 8+ kills plain background services; without this the node stops relaying within minutes.
 * It must be started from a visible Activity (Android 12+ forbids starting it from the background).
 */
class MeshService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as MeshChatApp).repo
        if (intent?.action == ACTION_STOP) {
            repo.stopMesh()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CALL) {
            callActive = intent.getBooleanExtra(EXTRA_ACTIVE, false)
            callVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        }
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        // Android 14+: mic / camera may only be used in the background by a service that declares it (and only if granted).
        if (callActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (has(android.Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (callVideo && has(android.Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, Notifier.serviceNotification(this), type)
        repo.startMesh()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as MeshChatApp).repo.stopMesh()
        super.onDestroy()
    }

    private var callActive = false
    private var callVideo = false

    private fun has(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.meshchat.action.STOP"
        const val ACTION_CALL = "com.meshchat.action.CALL"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_VIDEO = "video"

        /** Tells the running service a call started / ended so it can hold the microphone / camera foreground type. */
        fun setCallActive(ctx: android.content.Context, active: Boolean, video: Boolean) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, MeshService::class.java).setAction(ACTION_CALL).putExtra(EXTRA_ACTIVE, active).putExtra(EXTRA_VIDEO, video),
                )
            }
        }
    }
}
