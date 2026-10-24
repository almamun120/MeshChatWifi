package com.meshchat.core

/**
 * v2.10 compatibility with MeshChat 1.x phones: they drop any packet whose hop limit is above 8 (v2 sends up to 16).
 * With [maxTtl] returning 8 every outgoing packet is capped, so old and new phones can share one mesh. With 16 nothing
 * changes. The hop limit is byte 3 of the header and is not covered by the signature or the encryption, so it can be
 * lowered on the way out without touching the packet's meaning. Incoming packets are always accepted up to 16.
 */
class TtlClampTransport(private val inner: MeshTransport, private val maxTtl: () -> Int) : MeshTransport by inner {
    override suspend fun send(peerId: String, channel: Channel, data: ByteArray): Boolean {
        val limit = maxTtl()
        if (data.size > TTL_INDEX && (data[TTL_INDEX].toInt() and 0xFF) > limit) {
            val copy = data.copyOf()
            copy[TTL_INDEX] = limit.toByte()
            return inner.send(peerId, channel, copy)
        }
        return inner.send(peerId, channel, data)
    }

    private companion object {
        const val TTL_INDEX = 3
    }
}
