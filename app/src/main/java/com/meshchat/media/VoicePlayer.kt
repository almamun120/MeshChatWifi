package com.meshchat.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val id: String? = null,          // message currently loaded (playing or paused)
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1f,
)

/**
 * WhatsApp-style voice playback: one message plays at a time; tap to play/pause, drag the waveform to seek,
 * cycle speed 1x -> 1.5x -> 2x. When a clip finishes it rewinds to the start. Must be used from the main thread.
 */
class VoicePlayer(private val context: Context, private val scope: CoroutineScope) {
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Tap on the play/pause button of message [id]. */
    fun toggle(id: String, path: String, durationHint: Int) {
        val cur = _state.value
        if (cur.id == id && mp != null) {
            if (cur.playing) pause() else resume()
            return
        }
        if (!load(id, path, durationHint)) return
        resume()
    }

    /** Drag/tap on the waveform of message [id]: 0f..1f. Loads the clip (paused) if it is not the current one. */
    fun seekTo(id: String, path: String, durationHint: Int, fraction: Float) {
        if (_state.value.id != id || mp == null) {
            if (!load(id, path, durationHint)) return
        }
        val player = mp ?: return
        val dur = _state.value.durationMs.takeIf { it > 0 } ?: durationHint
        val pos = (fraction.coerceIn(0f, 1f) * dur).toInt()
        try {
            player.seekTo(pos)
        } catch (_: IllegalStateException) {
        }
        _state.value = _state.value.copy(positionMs = pos)
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _state.value = _state.value.copy(speed = next)
        if (_state.value.playing) applySpeed()      // changing PlaybackParams on a paused player can start it on some devices
    }

    fun stop() {
        ticker?.cancel()
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        mp = null
        _state.value = PlayerState(speed = _state.value.speed)
    }

    fun release() = stop()

    // ------------------------------------------------------------------

    private fun load(id: String, path: String, durationHint: Int): Boolean {
        val speed = _state.value.speed
        stop()
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            player.setDataSource(path)
            player.prepare()                           // clips are < 150 KB: instant
            player.setOnCompletionListener {
                ticker?.cancel()
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                try {
                    it.seekTo(0)
                } catch (_: IllegalStateException) {
                }
            }
            player.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp = player
            val dur = player.duration.takeIf { it > 0 } ?: durationHint
            _state.value = PlayerState(id = id, playing = false, positionMs = 0, durationMs = dur, speed = speed)
            true
        } catch (e: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun resume() {
        val player = mp ?: return
        try {
            if (_state.value.positionMs >= _state.value.durationMs - 50) player.seekTo(0)
            applySpeedBeforeStart(player)
            player.start()
            applySpeed()
        } catch (e: IllegalStateException) {
            stop()
            return
        }
        _state.value = _state.value.copy(playing = true)
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val p = mp ?: break
                try {
                    if (p.isPlaying) _state.value = _state.value.copy(positionMs = p.currentPosition)
                } catch (_: IllegalStateException) {
                    break
                }
                delay(60)
            }
        }
    }

    private fun pause() {
        try {
            mp?.pause()
        } catch (_: IllegalStateException) {
        }
        ticker?.cancel()
        mp?.let { _state.value = _state.value.copy(playing = false, positionMs = runCatching { it.currentPosition }.getOrDefault(_state.value.positionMs)) }
    }

    private fun applySpeedBeforeStart(player: MediaPlayer) {
        // nothing to do: speed is applied right after start() so that start() cannot reset it
    }

    private fun applySpeed() {
        val player = mp ?: return
        try {
            player.playbackParams = PlaybackParams().setSpeed(_state.value.speed)
        } catch (_: Exception) {
        }
    }
}
