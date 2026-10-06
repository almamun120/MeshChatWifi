package com.meshchat.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.meshchat.core.MediaLimits
import com.meshchat.core.VoiceCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

class RecordedVoice(val file: File, val durationMs: Int, val codec: Int, val waveform: ByteArray)

/**
 * Records mono 16 kHz speech with a compressing codec:
 *  - Android 10+: Opus in an Ogg container at 16 kbps (about 2 KB per second of speech)
 *  - Android 8-9 (or if the device's Opus encoder refuses): AAC-LC in MP4 at 24 kbps (about 3 KB per second)
 * Uncompressed audio would be ~32 KB/s and impossible to push over BLE in reasonable time.
 *
 * While recording, the microphone level is sampled every 80 ms; the samples become the waveform shown in the chat
 * bubble (40 bars, sent inside the message so the receiver shows the real shape).
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var codec = 0
    private var startedAt = 0L
    private val levels = ArrayList<Int>()
    private var ticker: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Synchronized
    fun start(scope: CoroutineScope): Boolean {
        if (_active.value) return false
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val order = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(VoiceCodec.OPUS_OGG, VoiceCodec.AAC_M4A)
        } else listOf(VoiceCodec.AAC_M4A)
        for (c in order) {
            val f = File(dir, "rec_${System.currentTimeMillis()}.${if (c == VoiceCodec.OPUS_OGG) "ogg" else "m4a"}")
            val r = tryStart(c, f)
            if (r != null) {
                recorder = r
                file = f
                codec = c
                levels.clear()
                startedAt = SystemClock.elapsedRealtime()
                _elapsedMs.value = 0
                _level.value = 0f
                _active.value = true
                ticker = scope.launch {
                    while (_active.value) {
                        delay(80)
                        _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                        val amp = try {
                            recorder?.maxAmplitude ?: 0
                        } catch (e: Exception) {
                            0
                        }
                        val lv = sqrt(amp / 32767f).coerceIn(0f, 1f)
                        levels.add((lv * 255).toInt())
                        _level.value = lv
                    }
                }
                return true
            }
            f.delete()
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun tryStart(c: Int, out: File): MediaRecorder? {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (c == VoiceCodec.OPUS_OGG) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(16_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(24_000)
            }
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setMaxDuration(MediaLimits.MAX_VOICE_MS + 500)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            r
        } catch (e: Exception) {
            try {
                r.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    /** Stops and returns the finished recording, or null if it was too short / failed. */
    @Synchronized
    fun stop(): RecordedVoice? {
        val r = recorder ?: return null
        val f = file ?: return null
        val duration = (SystemClock.elapsedRealtime() - startedAt).toInt()
        _active.value = false
        ticker?.cancel()
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false                                    // stop() throws if almost nothing was recorded
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        if (!ok || duration < MIN_MS || !f.exists() || f.length() == 0L) {
            f.delete()
            return null
        }
        return RecordedVoice(f, minOf(duration, MediaLimits.MAX_VOICE_MS), codec, waveform(levels))
    }

    @Synchronized
    fun cancel() {
        val r = recorder
        val f = file
        _active.value = false
        ticker?.cancel()
        recorder = null
        file = null
        if (r != null) {
            try {
                r.stop()
            } catch (_: Exception) {
            }
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        f?.delete()
    }

    /** Down-samples the level history to [MediaLimits.WAVE_BARS] bars (max of each bucket), normalised to 0..255. */
    private fun waveform(src: List<Int>): ByteArray {
        val n = MediaLimits.WAVE_BARS
        if (src.isEmpty()) return ByteArray(n) { 24 }
        val buckets = IntArray(n)
        for (i in 0 until n) {
            val from = i * src.size / n
            val to = maxOf(from + 1, (i + 1) * src.size / n).coerceAtMost(src.size)
            var m = 0
            for (j in from until to) m = maxOf(m, src[j])
            buckets[i] = m
        }
        val peak = maxOf(buckets.max(), 1)
        return ByteArray(n) { i -> maxOf(20, buckets[i] * 255 / peak).toByte() }
    }

    private companion object {
        const val MIN_MS = 700
    }
}
