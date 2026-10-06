package com.meshchat.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.meshchat.core.CallEnd
import com.meshchat.core.CallPhase
import com.meshchat.core.CallUi

/**
 * Sounds and vibration of a call, driven only by [CallUi]:
 *  - caller while CALLING / RINGING: the "tu-tuu" ringback tone;
 *  - callee while INCOMING: the phone's ringtone + vibration (respects silent / vibrate-only mode);
 *  - a short beep when the call ends.
 */
class CallAlerts(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var lastPhase = CallPhase.IDLE
    private var vibrating = false

    fun onState(ui: CallUi) {
        val phase = ui.phase
        val wantRingback = ui.outgoing && (phase == CallPhase.CALLING || phase == CallPhase.RINGING)
        val wantRing = !ui.outgoing && phase == CallPhase.INCOMING
        if (wantRingback) startRingback() else stopRingback()
        if (wantRing) startRing() else stopRing()
        if (phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && lastPhase != CallPhase.IDLE) endBeep(ui.endReason)
        lastPhase = phase
    }

    fun release() {
        stopRingback()
        stopRing()
    }

    // ---------------------------------------------------------------- ringback (caller)

    private fun startRingback() {
        if (tone != null) return
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }.getOrNull()
    }

    private fun stopRingback() {
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
    }

    private fun endBeep(reason: CallEnd) {
        if (reason == CallEnd.NONE) return
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            t.startTone(if (reason == CallEnd.BUSY) ToneGenerator.TONE_SUP_BUSY else ToneGenerator.TONE_PROP_PROMPT, 350)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { t.release() } }, 900)
        }
    }

    // ---------------------------------------------------------------- ringtone + vibration (callee)

    private fun startRing() {
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL && ringtone == null) {
            ringtone = runCatching {
                val chosen = com.meshchat.data.AppSettings.ringtone.value
                val uri = when {
                    chosen == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    chosen.isEmpty() -> null
                    else -> android.net.Uri.parse(chosen)
                }
                if (uri == null) null else RingtoneManager.getRingtone(appCtx, uri)?.also {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true
                    it.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    it.play()
                }
            }.getOrNull()
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && !vibrating && com.meshchat.data.AppSettings.callVibrate.value) {
            vibrating = true
            runCatching {
                val pattern = longArrayOf(0, 900, 700)
                vibrator().vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
        }
    }

    private fun stopRing() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) {
            vibrating = false
            runCatching { vibrator().cancel() }
        }
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appCtx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
