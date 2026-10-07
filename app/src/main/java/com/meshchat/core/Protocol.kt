package com.meshchat.core

import java.nio.ByteBuffer
import java.util.UUID

/** Logical GATT channel a packet type travels on. */
enum class Channel { MSG, SYNC, CONTROL }

enum class PacketType(val code: Int, val channel: Channel) {
    HELLO(1, Channel.CONTROL),
    IDENTITY(2, Channel.MSG),
    ANNOUNCE(3, Channel.MSG),
    PRIVATE(4, Channel.MSG),
    ACK(5, Channel.MSG),
    SYNC_INV(6, Channel.SYNC),
    SYNC_WANT(7, Channel.SYNC),
    MEDIA(8, Channel.MSG),          // one chunk of an encrypted image/voice blob (routed unicast)
    MEDIA_NACK(9, Channel.MSG),     // receiver asks the sender to resend missing chunks
    CALL(10, Channel.MSG),
    SOS(11, Channel.MSG);          // signed emergency broadcast (flooded, never stored); repeated by the sender until "I'm safe"          // encrypted session signalling (call / file-transfer offers, accept, Wi-Fi link info); never stored or queued

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PacketType? = byCode[code]
    }
}

object Cap {
    const val CHAT = 1
    const val RELAY = 2
    const val STORE = 4
    const val ALL = CHAT or RELAY or STORE
}

object Flags {
    const val SIGNED = 1
}

object Protocol {
    const val VERSION = 1
    const val HEADER_SIZE = 31
    const val MAX_PACKET = 2048
    const val DEFAULT_TTL = 7
    const val MAX_HOPS = 8
    const val MAX_NAME_BYTES = 32
    const val MAX_POST_CHARS = 280
    const val MAX_PRIVATE_CHARS = 500
    const val MAX_ROUTE_ADS = 40
    const val COMPANY_ID = 0xFFFF

    // GATT UUIDs
    val SERVICE_UUID: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000001")
    val CHAR_NODE_INFO: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000002")
    val CHAR_MSG_RX: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000003")
    val CHAR_MSG_TX: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000004")
    val CHAR_SYNC: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000005")
    val CHAR_CONTROL: UUID = UUID.fromString("4d455348-4348-4154-8000-000000000006")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertisement manufacturer data: proto(1) | caps(1) | nodeId(4). Contains NO personal data. */
    fun encodeAdvert(caps: Int, nodeId: String): ByteArray =
        byteArrayOf(VERSION.toByte(), caps.toByte()) + NodeIds.toBytes(nodeId)

    /** Returns (caps, nodeId) or null. */
    fun decodeAdvert(data: ByteArray?): Pair<Int, String>? {
        if (data == null || data.size < 6) return null
        if ((data[0].toInt() and 0xFF) != VERSION) return null
        return (data[1].toInt() and 0xFF) to Hex.encode(data.copyOfRange(2, 6))
    }
}

/**
 * Wire packet (big-endian):
 * ver(1) type(1) flags(1) ttl(1) hops(1) msgId(8) src(4) dst(4) timestamp(8) len(2) payload [sigLen(1) sig]
 */
@Suppress("ArrayInDataClass")
class MeshPacket(
    val type: PacketType,
    val flags: Int,
    val ttl: Int,
    val hops: Int,
    val msgId: ByteArray,
    val src: String,
    val dst: String,
    val timestamp: Long,
    val payload: ByteArray,
    val signature: ByteArray? = null,
) {
    val msgIdHex: String get() = Hex.encode(msgId)

    /** Duplicate-cache key: Message ID + Source Node ID. */
    val key: String get() = "$src:$msgIdHex"

    val isBroadcast: Boolean get() = dst == NodeIds.BROADCAST

    fun withTtl(newTtl: Int) = MeshPacket(type, flags, newTtl, hops, msgId, src, dst, timestamp, payload, signature)

    /** Copy for relaying: TTL-1, hops+1. */
    fun forwarded() = MeshPacket(type, flags, ttl - 1, hops + 1, msgId, src, dst, timestamp, payload, signature)

    fun withSignature(sig: ByteArray) =
        MeshPacket(type, flags or Flags.SIGNED, ttl, hops, msgId, src, dst, timestamp, payload, sig)

    /** Bytes covered by the signature: everything immutable in transit (ttl/hops excluded). */
    fun signedBytes(): ByteArray {
        val b = ByteBuffer.allocate(1 + 8 + 4 + 4 + 8 + payload.size)
        b.put(type.code.toByte()).put(msgId).put(NodeIds.toBytes(src)).put(NodeIds.toBytes(dst))
        b.putLong(timestamp).put(payload)
        return b.array()
    }

    fun encode(): ByteArray {
        val sig = signature
        val flagsOut = if (sig != null) flags or Flags.SIGNED else flags and Flags.SIGNED.inv()
        val size = Protocol.HEADER_SIZE + payload.size + (if (sig != null) 1 + sig.size else 0)
        val b = ByteBuffer.allocate(size)
        b.put(Protocol.VERSION.toByte())
        b.put(type.code.toByte())
        b.put(flagsOut.toByte())
        b.put(ttl.toByte())
        b.put(hops.toByte())
        b.put(msgId)
        b.put(NodeIds.toBytes(src))
        b.put(NodeIds.toBytes(dst))
        b.putLong(timestamp)
        b.putShort(payload.size.toShort())
        b.put(payload)
        if (sig != null) {
            b.put(sig.size.toByte())
            b.put(sig)
        }
        return b.array()
    }

    companion object {
        fun decode(bytes: ByteArray): MeshPacket? {
            if (bytes.size < Protocol.HEADER_SIZE || bytes.size > Protocol.MAX_PACKET) return null
            return try {
                val b = ByteBuffer.wrap(bytes)
                if ((b.get().toInt() and 0xFF) != Protocol.VERSION) return null
                val type = PacketType.fromCode(b.get().toInt() and 0xFF) ?: return null
                val flags = b.get().toInt() and 0xFF
                val ttl = b.get().toInt() and 0xFF
                val hops = b.get().toInt() and 0xFF
                if (ttl > Protocol.MAX_HOPS || hops > 64) return null
                val msgId = ByteArray(8).also { b.get(it) }
                val src = Hex.encode(ByteArray(4).also { b.get(it) })
                val dst = Hex.encode(ByteArray(4).also { b.get(it) })
                val ts = b.getLong()
                val len = b.getShort().toInt() and 0xFFFF
                if (b.remaining() < len) return null
                val payload = ByteArray(len).also { b.get(it) }
                var sig: ByteArray? = null
                if (flags and Flags.SIGNED != 0) {
                    if (!b.hasRemaining()) return null
                    val sl = b.get().toInt() and 0xFF
                    if (sl == 0 || b.remaining() < sl) return null
                    sig = ByteArray(sl).also { b.get(it) }
                }
                if (b.hasRemaining()) return null
                MeshPacket(type, flags, ttl, hops, msgId, src, dst, ts, payload, sig)
            } catch (e: Exception) {
                null
            }
        }

        /** Source Node ID straight from raw bytes (used by the GATT server to bind a connection to a peer). */
        fun peekSrc(bytes: ByteArray): String? {
            if (bytes.size < Protocol.HEADER_SIZE) return null
            if ((bytes[0].toInt() and 0xFF) != Protocol.VERSION) return null
            return Hex.encode(bytes.copyOfRange(13, 17))
        }
    }
}

data class PacketRef(val src: String, val msgIdHex: String) {
    val key: String get() = "$src:$msgIdHex"
}

data class RouteAd(val nodeId: String, val hops: Int)

/** Payload encoders/decoders. DOB and GPS have no field in any payload, by design. */
object Payloads {

    data class Hello(val name: String, val caps: Int, val routes: List<RouteAd>)
    data class Identity(val caps: Int, val name: String, val publicKey: ByteArray, val email: String = "")

    const val MAX_EMAIL_BYTES = 64
    data class Announce(val authorName: String, val content: String)

    fun clampUtf8(s: String, maxBytes: Int): ByteArray {
        val full = s.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val n = chunk.toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes) break
            sb.append(chunk)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun ByteBuffer.putShortStr(s: String, max: Int) {
        val b = clampUtf8(s, max)
        put(b.size.toByte())
        put(b)
    }

    private fun ByteBuffer.getShortStr(): String? {
        if (!hasRemaining()) return null
        val n = get().toInt() and 0xFF
        if (remaining() < n) return null
        val b = ByteArray(n)
        get(b)
        return String(b, Charsets.UTF_8)
    }

    fun encodeHello(h: Hello): ByteArray {
        val nameBytes = clampUtf8(h.name, Protocol.MAX_NAME_BYTES)
        val routes = h.routes.take(Protocol.MAX_ROUTE_ADS)
        val b = ByteBuffer.allocate(1 + nameBytes.size + 1 + 1 + routes.size * 5)
        b.put(nameBytes.size.toByte()).put(nameBytes)
        b.put(h.caps.toByte())
        b.put(routes.size.toByte())
        for (r in routes) {
            b.put(NodeIds.toBytes(r.nodeId))
            b.put(r.hops.toByte())
        }
        return b.array()
    }

    fun decodeHello(data: ByteArray): Hello? {
        return try {
        val b = ByteBuffer.wrap(data)
        val name = b.getShortStr() ?: return null
        if (b.remaining() < 2) return null
        val caps = b.get().toInt() and 0xFF
        val n = b.get().toInt() and 0xFF
        if (n > Protocol.MAX_ROUTE_ADS || b.remaining() < n * 5) return null
        val routes = ArrayList<RouteAd>(n)
        repeat(n) {
            val id = Hex.encode(ByteArray(4).also { b.get(it) })
            val hops = b.get().toInt() and 0xFF
            routes.add(RouteAd(id, hops))
        }
        Hello(name, caps, routes)
        } catch (e: Exception) {
            null
        }
    }

    fun encodeIdentity(i: Identity): ByteArray {
        val nameBytes = clampUtf8(i.name, Protocol.MAX_NAME_BYTES)
        // Optional e-mail goes after the key. Without one the bytes are exactly the old format, so phones on older
        // builds keep accepting this identity; only identities that carry an e-mail need the newer build.
        val emailBytes = clampUtf8(i.email.trim(), MAX_EMAIL_BYTES)
        val extra = if (emailBytes.isEmpty()) 0 else 1 + emailBytes.size
        val b = ByteBuffer.allocate(1 + 1 + nameBytes.size + 1 + i.publicKey.size + extra)
        b.put(i.caps.toByte())
        b.put(nameBytes.size.toByte()).put(nameBytes)
        b.put(i.publicKey.size.toByte()).put(i.publicKey)
        if (emailBytes.isNotEmpty()) b.put(emailBytes.size.toByte()).put(emailBytes)
        return b.array()
    }

    fun decodeIdentity(data: ByteArray): Identity? {
        return try {
        val b = ByteBuffer.wrap(data)
        val caps = b.get().toInt() and 0xFF
        val name = b.getShortStr() ?: return null
        if (!b.hasRemaining()) return null
        val kl = b.get().toInt() and 0xFF
        if (kl == 0 || b.remaining() < kl) return null
        val key = ByteArray(kl).also { b.get(it) }
        val email = if (b.hasRemaining()) (b.getShortStr() ?: return null) else ""
        if (b.hasRemaining()) return null
        Identity(caps, name, key, email)
        } catch (e: Exception) {
            null
        }
    }

    fun encodeAnnounce(a: Announce): ByteArray {
        val nameBytes = clampUtf8(a.authorName, Protocol.MAX_NAME_BYTES)
        val content = a.content.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(1 + nameBytes.size + content.size)
        b.put(nameBytes.size.toByte()).put(nameBytes).put(content)
        return b.array()
    }

    fun decodeAnnounce(data: ByteArray): Announce? {
        return try {
        val b = ByteBuffer.wrap(data)
        val name = b.getShortStr() ?: return null
        val rest = ByteArray(b.remaining()).also { b.get(it) }
        Announce(name, String(rest, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun encodeRefs(refs: List<PacketRef>): ByteArray {
        val list = refs.take(100)
        val b = ByteBuffer.allocate(1 + list.size * 12)
        b.put(list.size.toByte())
        for (r in list) {
            b.put(NodeIds.toBytes(r.src))
            b.put(Hex.decode(r.msgIdHex))
        }
        return b.array()
    }

    fun decodeRefs(data: ByteArray): List<PacketRef>? {
        return try {
        val b = ByteBuffer.wrap(data)
        val n = b.get().toInt() and 0xFF
        if (b.remaining() < n * 12) return null
        val out = ArrayList<PacketRef>(n)
        repeat(n) {
            val s = Hex.encode(ByteArray(4).also { b.get(it) })
            val m = Hex.encode(ByteArray(8).also { b.get(it) })
            out.add(PacketRef(s, m))
        }
        out
        } catch (e: Exception) {
            null
        }
    }
}
