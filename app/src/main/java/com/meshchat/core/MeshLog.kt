package com.meshchat.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small in-memory event log (last [CAPACITY] lines) so link problems can be diagnosed without a PC:
 * it is shown on the Debug screen and mirrored to Android logcat under the tag "MeshBle" via [sink].
 * Never contains message text, names or keys: only Node IDs (8 hex chars), states and error codes.
 */
object MeshLog {
    const val CAPACITY = 150

    /** Set by the Android layer to forward each line to logcat. */
    @Volatile var sink: ((String) -> Unit)? = null

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(msg: String) {
        val t = System.currentTimeMillis() % 86_400_000L
        val line = "%02d:%02d:%02d %s".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, msg)
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
        try {
            sink?.invoke(line)
        } catch (_: Throwable) {
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
