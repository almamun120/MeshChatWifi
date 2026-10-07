package com.meshchat.core

import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A one-time location attached to an SOS (only when the sender chose to include it). */
class SosLocation(val lat: Double, val lon: Double, val accuracyM: Int)

/** What an SOS packet says. [active] false = "I'm safe" (cancels the alert on receivers). */
class SosPayload(val active: Boolean, val battery: Int, val location: SosLocation?, val name: String)

/** An SOS as seen by a receiver. [hops] = how many hops away the sender is (1 = direct neighbour); [verified] = signature checked against a known key. */
class SosAlert(
    val nodeId: String,
    val name: String,
    val active: Boolean,
    val battery: Int,
    val location: SosLocation?,
    val timestamp: Long,
    val hops: Int,
    val verified: Boolean,
)

object SosCodec {
    const val UNKNOWN_BATTERY = 255

    /** state(1) battery(1) flags(1) [latE6(4) lonE6(4) accM(2)] nameLen(1) name */
    fun encode(p: SosPayload): ByteArray {
        val name = Payloads.clampUtf8(p.name, Protocol.MAX_NAME_BYTES)
        val loc = p.location
        val b = ByteBuffer.allocate(3 + (if (loc != null) 10 else 0) + 1 + name.size)
        b.put(if (p.active) 1 else 0).put(p.battery.coerceIn(0, 255).toByte()).put(if (loc != null) 1 else 0)
        if (loc != null) {
            b.putInt(Math.round(loc.lat * 1e6).toInt()).putInt(Math.round(loc.lon * 1e6).toInt()).putShort(loc.accuracyM.coerceIn(0, 65535).toShort())
        }
        b.put(name.size.toByte()).put(name)
        return b.array()
    }

    fun decode(data: ByteArray): SosPayload? = try {
        decodeUnsafe(data)
    } catch (e: Exception) {
        null
    }

    private fun decodeUnsafe(data: ByteArray): SosPayload? {
        val b = ByteBuffer.wrap(data)
        val state = b.get().toInt()
        val battery = b.get().toInt() and 0xFF
        val flags = b.get().toInt()
        if (state !in 0..1 || (battery > 100 && battery != UNKNOWN_BATTERY)) return null
        run {
            var loc: SosLocation? = null
            if (flags and 1 != 0) {
                val lat = b.getInt() / 1e6
                val lon = b.getInt() / 1e6
                val acc = b.getShort().toInt() and 0xFFFF
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                loc = SosLocation(lat, lon, acc)
            }
            val n = b.get().toInt() and 0xFF
            if (b.remaining() != n) return null
            return SosPayload(state == 1, battery, loc, String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8))
        }
    }
}

/**
 * The sender side of an SOS: broadcasts right away, then repeats (with a little jitter, so two SOS phones do not stay in
 * lock-step) until stopped. Stopping sends "I'm safe" a few times so it reaches phones that were out of range.
 */
class SosSession(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 45_000,
    private val jitterMs: Long = 15_000,
    private val safeRepeats: Int = 3,
    private val safeGapMs: Long = 2_000,
    private val send: suspend (active: Boolean) -> Unit,
) {
    private var job: Job? = null
    val isActive: Boolean get() = job?.isActive == true

    @Synchronized
    fun start() {
        if (isActive) return
        job = scope.launch {
            val rnd = java.util.Random()
            while (isActive) {
                runCatching { send(true) }
                delay(intervalMs + if (jitterMs > 0) (rnd.nextLong() and Long.MAX_VALUE) % jitterMs else 0)
            }
        }
    }

    /** "I'm safe": ends the alert and tells everyone. */
    fun stopSafe() {
        val old = synchronized(this) { job.also { job = null } } ?: return
        old.cancel()
        scope.launch {
            repeat(safeRepeats) { i ->
                runCatching { send(false) }
                if (i < safeRepeats - 1) delay(safeGapMs)
            }
        }
    }
}
