package com.meshchat.call

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.meshchat.core.CallPhase
import com.meshchat.core.CallUi
import com.meshchat.core.MeshLog

/**
 * Keeps a call alive when the screen goes off or the app is in the background, and handles the ear sensor.
 *
 *  - PARTIAL_WAKE_LOCK: the CPU keeps running (audio / WebRTC threads are not frozen when the screen is off).
 *  - WifiLock: Wi-Fi power-save would otherwise throttle the Wi-Fi Direct link the call runs over.
 *  - Proximity wake lock: an audio call held to the ear turns the display off (and back on) like the phone app.
 *    Not used on speaker, with a headset, in video calls or in walkie-talkie sessions.
 *  - Audio watchdog: some phones reset the audio mode / speaker route when the screen turns off; it is put back.
 */
class CallKeeper(context: Context) {
    private val ctx = context.applicationContext
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var proxLock: PowerManager.WakeLock? = null
    private var ui = CallUi()
    private var running = false
    private var receiverOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reapply() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            reapply()
            main.postDelayed(this, 3_000)
        }
    }

    /** Call with every call-state change (any thread). */
    fun update(state: CallUi) {
        main.post { apply(state) }
    }

    private fun apply(state: CallUi) {
        ui = state
        if (state.inCall) start() else stop()
        if (running) {
            updateProximity()
            reapply()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun start() {
        if (running) return
        running = true
        runCatching {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshChat:call").apply { setReferenceCounted(false); acquire(4 * 3600_000L) }
        }
        runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifiManager?.createWifiLock(mode, "MeshChat:call")?.apply { setReferenceCounted(false); acquire() }
        }
        if (!receiverOn) {
            ctx.registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
            receiverOn = true
        }
        main.postDelayed(tick, 3_000)
        MeshLog.log("callkeeper: wake + wifi locks held")
    }

    private fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        runCatching { if (receiverOn) ctx.unregisterReceiver(screenReceiver) }
        receiverOn = false
        releaseProximity()
        runCatching { cpuLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        wifiLock = null
        MeshLog.log("callkeeper: locks released")
    }

    // ------------------------------------------------------------------ proximity

    private fun wantProximity(): Boolean {
        val s = ui
        val live = s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING || s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE
        return live && !s.video && !s.speaker && !s.ptt && !headsetConnected()
    }

    private fun updateProximity() {
        if (wantProximity()) {
            if (proxLock?.isHeld == true) return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                MeshLog.log("callkeeper: no proximity sensor wake lock on this phone")
                return
            }
            runCatching {
                proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "MeshChat:proximity").apply { setReferenceCounted(false); acquire(4 * 3600_000L) }
                MeshLog.log("callkeeper: proximity on")
            }
        } else releaseProximity()
    }

    private fun releaseProximity() {
        val l = proxLock ?: return
        proxLock = null
        // WAIT_FOR_NO_PROXIMITY: if the phone is still at the ear when the call ends, the screen stays off until it is moved away.
        runCatching { if (l.isHeld) l.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }

    private fun headsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
    }

    // ------------------------------------------------------------------ audio watchdog

    @Suppress("DEPRECATION")
    private fun reapply() {
        if (!running) return
        updateProximity()
        val live = ui.phase == CallPhase.CONNECTING || ui.phase == CallPhase.ACTIVE
        if (!live) return
        runCatching {
            if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
                MeshLog.log("callkeeper: audio mode was reset, restoring")
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (ui.phase == CallPhase.ACTIVE && audio.isSpeakerphoneOn != ui.speaker) {
                audio.isSpeakerphoneOn = ui.speaker
            }
        }
    }
}
