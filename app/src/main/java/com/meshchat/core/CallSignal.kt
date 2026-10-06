package com.meshchat.core

import java.nio.ByteBuffer

/**
 * Session signalling travels over the BLE mesh (PacketType.CALL, end-to-end encrypted like a private chat message).
 * Audio, video and file data never touch BLE: they flow over a Wi-Fi Direct link whose credentials are exchanged here.
 * The same signals drive voice calls, video calls and file transfers; [SessionKind] says which one.
 */
enum class SessionKind(val code: Int) {
    AUDIO(0), VIDEO(1), FILES(2);

    companion object {
        fun fromCode(c: Int): SessionKind? = entries.firstOrNull { it.code == c }
    }
}

enum class CallSignalType(val code: Int) {
    INVITE(1),    // starter -> peer: "my phone wants to call / send files"
    RINGING(2),   // peer -> starter: "your invite arrived, I am ringing / asking"
    LINK(3),      // starter -> peer: Wi-Fi Direct group credentials
    ACCEPT(4),    // peer -> starter: "answered / accepted"
    REJECT(5),    // peer -> starter: "declined"
    BUSY(6),      // peer -> starter: "already in another session"
    CANCEL(7),    // starter -> peer: "gave up before you answered"
    END(8);       // either side: hang up / abort

    companion object {
        fun fromCode(c: Int): CallSignalType? = entries.firstOrNull { it.code == c }
    }
}

/** Wi-Fi Direct group the starter created. [ownerMac] is the group owner's P2P device address (used on Android < 10). */
data class LinkParams(val ssid: String, val passphrase: String, val ownerMac: String, val port: Int)

/** What a file offer says before anything is sent: "3 files, 12 MB, photo.jpg…". Shown on the receiver's prompt. */
data class OfferInfo(val count: Int, val totalBytes: Long, val title: String)

@Suppress("ArrayInDataClass")
class CallSignal(
    val type: CallSignalType,
    val callId: ByteArray,
    val kind: SessionKind = SessionKind.AUDIO,
    val link: LinkParams? = null,
    val offer: OfferInfo? = null,
) {
    val callIdHex: String get() = Hex.encode(callId)
    val video: Boolean get() = kind == SessionKind.VIDEO
}

/**
 * Plaintext layout (inside the AES-GCM blob):
 *  0xC1 | type(1) | callId(8) | kind(1)
 *    INVITE + FILES: count(2) totalBytes(8) titleLen(1) title
 *    LINK:           ssidLen(1) ssid passLen(1) pass macLen(1) mac port(2)
 */
object CallSignalCodec {
    private const val MAGIC = 0xC1
    const val ID_LEN = 8
    private const val MAX_TITLE = 40

    fun encode(s: CallSignal): ByteArray? {
        if (s.callId.size != ID_LEN) return null
        val head = ByteBuffer.allocate(3 + ID_LEN).put(MAGIC.toByte()).put(s.type.code.toByte()).put(s.callId).put(s.kind.code.toByte()).array()
        if (s.type == CallSignalType.INVITE && s.kind == SessionKind.FILES) {
            val o = s.offer ?: return null
            if (o.count !in 1..0xFFFF || o.totalBytes < 0) return null
            val title = Payloads.clampUtf8(o.title, MAX_TITLE)
            return head + ByteBuffer.allocate(2 + 8 + 1 + title.size).putShort(o.count.toShort()).putLong(o.totalBytes)
                .put(title.size.toByte()).put(title).array()
        }
        if (s.type == CallSignalType.LINK) {
            val link = s.link ?: return null
            val ssid = link.ssid.toByteArray(Charsets.UTF_8)
            val pass = link.passphrase.toByteArray(Charsets.UTF_8)
            val mac = link.ownerMac.toByteArray(Charsets.UTF_8)
            if (ssid.isEmpty() || ssid.size > 40 || pass.size !in 8..63 || mac.size > 24) return null
            return head + ByteBuffer.allocate(1 + ssid.size + 1 + pass.size + 1 + mac.size + 2)
                .put(ssid.size.toByte()).put(ssid).put(pass.size.toByte()).put(pass).put(mac.size.toByte()).put(mac)
                .putShort(link.port.toShort()).array()
        }
        return head
    }

    fun decode(bytes: ByteArray): CallSignal? {
        if (bytes.size < 3 + ID_LEN || (bytes[0].toInt() and 0xFF) != MAGIC) return null
        val type = CallSignalType.fromCode(bytes[1].toInt() and 0xFF) ?: return null
        return try {
            val b = ByteBuffer.wrap(bytes, 2, bytes.size - 2)
            val id = ByteArray(ID_LEN).also { b.get(it) }
            val kind = SessionKind.fromCode(b.get().toInt() and 0xFF) ?: return null
            fun str(max: Int): String? {
                if (!b.hasRemaining()) return null
                val n = b.get().toInt() and 0xFF
                if (n > max || b.remaining() < n) return null
                return String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
            }
            if (type == CallSignalType.INVITE && kind == SessionKind.FILES) {
                if (b.remaining() < 11) return null
                val count = b.getShort().toInt() and 0xFFFF
                val total = b.getLong()
                val title = str(MAX_TITLE) ?: return null
                if (b.hasRemaining() || count == 0 || total < 0) return null
                return CallSignal(type, id, kind, offer = OfferInfo(count, total, title))
            }
            if (type == CallSignalType.LINK) {
                val ssid = str(40) ?: return null
                val pass = str(63) ?: return null
                val mac = str(24) ?: return null
                if (b.remaining() != 2) return null
                val port = b.getShort().toInt() and 0xFFFF
                if (ssid.isEmpty() || pass.length < 8 || port < 1024) return null
                return CallSignal(type, id, kind, LinkParams(ssid, pass, mac, port))
            }
            if (b.hasRemaining()) return null
            CallSignal(type, id, kind)
        } catch (e: Exception) {
            null
        }
    }
}
