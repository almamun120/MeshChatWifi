package com.meshchat.core

import java.io.ByteArrayOutputStream

/**
 * BLE-friendly fragmentation.
 *
 * Frame = fragId(1) | index(1) | total(1) | chunk.
 * `maxFrame` is the usable ATT payload (MTU - 3). With the default MTU 23 that is 20 bytes -> 17 bytes of data.
 */
class Fragmenter {
    private var nextId = 0

    @Synchronized
    fun split(data: ByteArray, maxFrame: Int): List<ByteArray> {
        val chunkSize = (maxFrame - HEADER).coerceAtLeast(1)
        val total = maxOf(1, (data.size + chunkSize - 1) / chunkSize)
        require(total <= 255) { "packet too large to fragment: ${data.size} bytes" }
        val id = nextId
        nextId = (nextId + 1) and 0xFF
        return List(total) { i ->
            val from = i * chunkSize
            val to = minOf(data.size, from + chunkSize)
            val frame = ByteArray(HEADER + (to - from))
            frame[0] = id.toByte()
            frame[1] = i.toByte()
            frame[2] = total.toByte()
            System.arraycopy(data, from, frame, HEADER, to - from)
            frame
        }
    }

    companion object {
        const val HEADER = 3
    }
}

/**
 * Reassembles frames of one logical channel from one peer. GATT delivers frames of a single link in order,
 * and senders serialize whole packets, so a strictly sequential state machine is sufficient.
 * Any gap, id mismatch or timeout discards the partial packet.
 */
class Reassembler(
    private val timeoutMs: Long = 10_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var currentId = -1
    private var expected = 0
    private var total = 0
    private var startedAt = 0L
    private var buffer = ByteArrayOutputStream()

    /** Returns the complete packet bytes when the last fragment arrives, otherwise null. */
    @Synchronized
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.size < Fragmenter.HEADER) return null
        val id = frame[0].toInt() and 0xFF
        val index = frame[1].toInt() and 0xFF
        val tot = frame[2].toInt() and 0xFF
        if (tot == 0 || index >= tot) return null
        val now = clock()

        if (index == 0) {
            reset()
            currentId = id
            total = tot
            expected = 0
            startedAt = now
        } else {
            if (currentId != id || tot != total || index != expected || now - startedAt > timeoutMs) {
                reset()
                return null
            }
        }
        buffer.write(frame, Fragmenter.HEADER, frame.size - Fragmenter.HEADER)
        expected = index + 1
        if (expected == total) {
            val out = buffer.toByteArray()
            reset()
            return out
        }
        return null
    }

    private fun reset() {
        currentId = -1
        expected = 0
        total = 0
        buffer = ByteArrayOutputStream()
    }
}
