package com.meshchat.core

import java.nio.ByteBuffer

/** Size limits that keep media realistic over BLE (see README "Media over BLE"). */
object MediaLimits {
    const val CHUNK = 900                    // bytes of encrypted blob per mesh packet
    const val MAX_CHUNKS = 200
    const val MAX_BLOB = 170_000             // encrypted blob (nonce + ciphertext + tag)
    const val MAX_VOICE_MS = 60_000
    const val MAX_PTT_MS = 15_000            // a push-to-talk clip is a short burst, not a message
    const val IMAGE_TARGET_BYTES = 48_000    // the image compressor aims below this
    const val IMAGE_MAX_BYTES = 110_000
    const val WAVE_BARS = 40
    const val MAX_TEXT_CHARS = 1000
}

/** Voice codecs. Both are lossy speech codecs played by the platform MediaPlayer. */
object VoiceCodec {
    const val OPUS_OGG = 1   // Opus in Ogg, ~16 kbps, Android 10+ recorder
    const val AAC_M4A = 2    // AAC-LC in MP4, ~24 kbps, fallback for Android 8-9
}

enum class ContentKind(val code: Int) {
    TEXT(0), LOCATION(1), IMAGE(2), VOICE(3),
    /** Control: "delete message [Content.targetId] for everyone" (only honoured when the sender wrote that message). */
    DELETE(4),
    /** Control: you were added to a private group ([Content.groupId], [Content.text] = name, [Content.members]). */
    GROUP_INVITE(5),
    /** Control: the sender left group [Content.groupId]. */
    GROUP_LEAVE(6);

    /** Control messages are never shown in a chat. */
    val isControl: Boolean get() = this == DELETE || this == GROUP_INVITE || this == GROUP_LEAVE

    companion object {
        fun fromCode(c: Int): ContentKind? = entries.firstOrNull { it.code == c }
    }
}

object GroupLimits {
    const val MAX_MEMBERS = 8          // including the creator: every message is sent once per member over BLE
    const val MAX_NAME = 24
}

/** Group chats use a 16-hex-digit id; a person's Node ID is 8 hex digits, so a chat key tells them apart. */
object GroupIds {
    fun isGroup(chatKey: String) = chatKey.length == 16
    fun isValid(id: String) = id.length == 16 && id.all { it in '0'..'9' || it in 'A'..'F' }
}

/**
 * What a private message carries. Always encrypted end to end before it leaves the phone.
 * LOCATION exists only as an explicit, per-recipient user action: nothing is ever broadcast.
 *
 * Optional envelope (any kind): [ttlSec] disappearing-message timer, [replyToId]+[replyQuote] reply reference,
 * [groupId]+[logicalId] group routing (a group message is sent as one private packet per member; [logicalId] is the id
 * every member stores it under, so replies and "delete for everyone" mean the same message on every phone).
 */
@Suppress("ArrayInDataClass")
class Content(
    val kind: ContentKind,
    val text: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracyM: Int = 0,
    val durationMs: Int = 0,
    val codec: Int = 0,
    val waveform: ByteArray = ByteArray(0),
    val data: ByteArray? = null,
    val ttlSec: Int = 0,
    val replyToId: String = "",
    val replyQuote: String = "",
    val groupId: String = "",
    val logicalId: String = "",
    val targetId: String = "",
    val members: List<String> = emptyList(),
    /** Voice only: a push-to-talk clip (receiver may auto-play it, or have push-to-talk switched off). */
    val ptt: Boolean = false,
) {
    val isMedia: Boolean get() = kind == ContentKind.IMAGE || kind == ContentKind.VOICE

    fun extension(): String = when (kind) {
        ContentKind.IMAGE -> "jpg"
        ContentKind.VOICE -> if (codec == VoiceCodec.OPUS_OGG) "ogg" else "m4a"
        else -> ""
    }

    /** One-line text for chat lists and notifications. */
    fun preview(): String = when (kind) {
        ContentKind.TEXT -> text
        ContentKind.LOCATION -> "📍 Location"
        ContentKind.IMAGE -> "📷 Photo"
        ContentKind.VOICE -> (if (ptt) "📻 Push-to-talk" else "🎤 Voice message") + " (${formatDuration(durationMs)})"
        ContentKind.DELETE -> "Message deleted"
        ContentKind.GROUP_INVITE -> "Added you to group $text"
        ContentKind.GROUP_LEAVE -> "Left the group"
    }

    fun copyEnvelope(ttlSec: Int = this.ttlSec, replyToId: String = this.replyToId, replyQuote: String = this.replyQuote,
                     groupId: String = this.groupId, logicalId: String = this.logicalId, ptt: Boolean = this.ptt) =
        Content(kind, text, lat, lon, accuracyM, durationMs, codec, waveform, data, ttlSec, replyToId, replyQuote, groupId, logicalId, targetId, members, ptt)

    companion object {
        fun ofText(t: String) = Content(ContentKind.TEXT, text = t)
        fun ofLocation(lat: Double, lon: Double, accuracyM: Int) =
            Content(ContentKind.LOCATION, lat = lat, lon = lon, accuracyM = accuracyM)

        fun ofImage(jpeg: ByteArray) = Content(ContentKind.IMAGE, data = jpeg)
        fun ofVoice(codec: Int, durationMs: Int, waveform: ByteArray, audio: ByteArray) =
            Content(ContentKind.VOICE, durationMs = durationMs, codec = codec, waveform = waveform, data = audio)

        fun ofDelete(targetId: String) = Content(ContentKind.DELETE, targetId = targetId)
        fun ofGroupInvite(groupId: String, name: String, members: List<String>) =
            Content(ContentKind.GROUP_INVITE, text = name, groupId = groupId, members = members)
        fun ofGroupLeave(groupId: String) = Content(ContentKind.GROUP_LEAVE, groupId = groupId)

        fun formatDuration(ms: Int): String {
            val s = (ms + 500) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/**
 * Plaintext layout inside the encrypted blob:
 *  header   kindByte(1) [flags(1) [ttlSec(4)] [replyId(8) quoteLen(1) quote] [groupId(8) logicalId(8)]]
 *           (bit 0x80 of kindByte says an envelope follows; flags: 1 = ttl, 2 = reply, 4 = group, 8 = push-to-talk clip)
 *  TEXT     utf8
 *  LOCATION lat(8) lon(8) accuracyM(4)
 *  IMAGE    jpeg
 *  VOICE    codec(1) durationMs(4) waveLen(1) wave[waveLen] audio
 *  DELETE   targetId(8)
 *  GROUP_INVITE groupId(8) nameLen(1) name count(1) member(4)*     (group id is in the body, not the envelope)
 *  GROUP_LEAVE  groupId(8)
 */
object ContentCodec {
    private const val ENV = 0x80
    private const val F_TTL = 1
    private const val F_REPLY = 2
    private const val F_GROUP = 4
    private const val F_PTT = 8
    const val MAX_QUOTE_BYTES = 120
    const val MAX_TTL_SEC = 30 * 24 * 3600

    fun encode(c: Content): ByteArray? {
        val body = encodeBody(c) ?: return null
        var flags = 0
        if (c.ttlSec > 0) flags = flags or F_TTL
        if (c.replyToId.isNotEmpty()) flags = flags or F_REPLY
        if (c.groupId.isNotEmpty() && c.kind != ContentKind.GROUP_INVITE && c.kind != ContentKind.GROUP_LEAVE) flags = flags or F_GROUP
        if (c.ptt) {
            if (c.kind != ContentKind.VOICE || c.durationMs > MediaLimits.MAX_PTT_MS + 1500) return null
            flags = flags or F_PTT
        }
        if (c.ttlSec !in 0..MAX_TTL_SEC) return null
        if (flags == 0) return byteArrayOf(c.kind.code.toByte()) + body
        val out = java.io.ByteArrayOutputStream()
        out.write(c.kind.code or ENV)
        out.write(flags)
        if (flags and F_TTL != 0) out.write(ByteBuffer.allocate(4).putInt(c.ttlSec).array())
        if (flags and F_REPLY != 0) {
            if (!isId(c.replyToId)) return null
            out.write(Hex.decode(c.replyToId))
            val q = clipUtf8(c.replyQuote, MAX_QUOTE_BYTES)
            out.write(q.size)
            out.write(q)
        }
        if (flags and F_GROUP != 0) {
            if (!isId(c.groupId) || !isId(c.logicalId)) return null
            out.write(Hex.decode(c.groupId))
            out.write(Hex.decode(c.logicalId))
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun encodeBody(c: Content): ByteArray? = when (c.kind) {
        ContentKind.TEXT -> c.text.toByteArray(Charsets.UTF_8)
        ContentKind.LOCATION -> {
            if (!validLocation(c.lat, c.lon)) null
            else ByteBuffer.allocate(20).putDouble(c.lat).putDouble(c.lon).putInt(c.accuracyM).array()
        }
        ContentKind.IMAGE -> {
            val d = c.data
            if (d == null || !isJpeg(d) || d.size > MediaLimits.IMAGE_MAX_BYTES) null else d
        }
        ContentKind.VOICE -> {
            val d = c.data
            if (d == null || d.isEmpty() || c.durationMs !in 1..MediaLimits.MAX_VOICE_MS + 2000 ||
                (c.codec != VoiceCodec.OPUS_OGG && c.codec != VoiceCodec.AAC_M4A) || c.waveform.size > 64
            ) null
            else {
                val b = ByteBuffer.allocate(1 + 4 + 1 + c.waveform.size + d.size)
                b.put(c.codec.toByte()).putInt(c.durationMs).put(c.waveform.size.toByte()).put(c.waveform).put(d)
                b.array()
            }
        }
        ContentKind.DELETE -> if (isId(c.targetId)) Hex.decode(c.targetId) else null
        ContentKind.GROUP_LEAVE -> if (isId(c.groupId)) Hex.decode(c.groupId) else null
        ContentKind.GROUP_INVITE -> {
            val name = c.text.toByteArray(Charsets.UTF_8)
            if (!isId(c.groupId) || name.isEmpty() || c.text.length > GroupLimits.MAX_NAME || name.size > 100 ||
                c.members.size !in 2..GroupLimits.MAX_MEMBERS || c.members.any { !NodeIds.isValid(it) }
            ) null
            else {
                val b = ByteBuffer.allocate(8 + 1 + name.size + 1 + 4 * c.members.size)
                b.put(Hex.decode(c.groupId)).put(name.size.toByte()).put(name).put(c.members.size.toByte())
                c.members.forEach { b.put(NodeIds.toBytes(it)) }
                b.array()
            }
        }
    }

    fun decode(bytes: ByteArray): Content? {
        if (bytes.isEmpty()) return null
        val first = bytes[0].toInt() and 0xFF
        val kind = ContentKind.fromCode(first and ENV.inv()) ?: return null
        return try {
            val b = ByteBuffer.wrap(bytes)
            b.position(1)
            var ttl = 0
            var replyId = ""
            var quote = ""
            var gid = ""
            var logical = ""
            var ptt = false
            if (first and ENV != 0) {
                val flags = b.get().toInt() and 0xFF
                if (flags and F_TTL != 0) {
                    ttl = b.getInt()
                    if (ttl !in 1..MaxTtl) return null
                }
                if (flags and F_REPLY != 0) {
                    replyId = Hex.encode(ByteArray(8).also { b.get(it) })
                    val ql = b.get().toInt() and 0xFF
                    if (ql > MAX_QUOTE_BYTES) return null
                    quote = String(ByteArray(ql).also { b.get(it) }, Charsets.UTF_8)
                }
                if (flags and F_GROUP != 0) {
                    gid = Hex.encode(ByteArray(8).also { b.get(it) })
                    logical = Hex.encode(ByteArray(8).also { b.get(it) })
                }
                if (flags and F_PTT != 0) ptt = true
            }
            val off = b.position()
            val rest = bytes.size - off
            val base = when (kind) {
                ContentKind.TEXT -> {
                    val t = String(bytes, off, rest, Charsets.UTF_8)
                    if (t.length > MediaLimits.MAX_TEXT_CHARS) return null else Content.ofText(t)
                }
                ContentKind.LOCATION -> {
                    if (rest != 20) return null
                    val lat = b.getDouble()
                    val lon = b.getDouble()
                    val acc = b.getInt()
                    if (!validLocation(lat, lon) || acc < 0) return null else Content.ofLocation(lat, lon, acc)
                }
                ContentKind.IMAGE -> {
                    val d = bytes.copyOfRange(off, bytes.size)
                    if (!isJpeg(d) || d.size > MediaLimits.IMAGE_MAX_BYTES) return null else Content.ofImage(d)
                }
                ContentKind.VOICE -> {
                    if (rest < 7) return null
                    val codec = b.get().toInt() and 0xFF
                    val dur = b.getInt()
                    val wl = b.get().toInt() and 0xFF
                    if ((codec != VoiceCodec.OPUS_OGG && codec != VoiceCodec.AAC_M4A) ||
                        dur !in 1..MediaLimits.MAX_VOICE_MS + 2000 || wl > 64 || b.remaining() <= wl
                    ) return null
                    val wave = ByteArray(wl).also { b.get(it) }
                    val audio = ByteArray(b.remaining()).also { b.get(it) }
                    Content.ofVoice(codec, dur, wave, audio)
                }
                ContentKind.DELETE -> {
                    if (rest != 8) return null
                    Content.ofDelete(Hex.encode(ByteArray(8).also { b.get(it) }))
                }
                ContentKind.GROUP_LEAVE -> {
                    if (rest != 8) return null
                    Content.ofGroupLeave(Hex.encode(ByteArray(8).also { b.get(it) }))
                }
                ContentKind.GROUP_INVITE -> {
                    if (rest < 8 + 1 + 1 + 1) return null
                    val id = Hex.encode(ByteArray(8).also { b.get(it) })
                    val nl = b.get().toInt() and 0xFF
                    if (nl == 0 || nl > 100 || b.remaining() < nl + 1) return null
                    val name = String(ByteArray(nl).also { b.get(it) }, Charsets.UTF_8)
                    val n = b.get().toInt() and 0xFF
                    if (n !in 2..GroupLimits.MAX_MEMBERS || b.remaining() != n * 4 || name.length > GroupLimits.MAX_NAME) return null
                    val members = List(n) { Hex.encode(ByteArray(4).also { b.get(it) }) }
                    Content.ofGroupInvite(id, name, members)
                }
            }
            if (ptt && (kind != ContentKind.VOICE || base.durationMs > MediaLimits.MAX_PTT_MS + 1500)) return null
            if (ttl == 0 && replyId.isEmpty() && gid.isEmpty() && !ptt) base
            else base.copyEnvelope(ttlSec = ttl, replyToId = replyId, replyQuote = quote, groupId = gid.ifEmpty { base.groupId }, logicalId = logical, ptt = ptt)
        } catch (e: Exception) {
            null
        }
    }

    private const val MaxTtl = MAX_TTL_SEC

    private fun isId(s: String) = s.length == 16 && s.all { it in '0'..'9' || it in 'A'..'F' }

    private fun clipUtf8(s: String, max: Int): ByteArray {
        var b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= max) return b
        var n = s.length
        while (b.size > max && n > 0) { n--; b = s.substring(0, n).toByteArray(Charsets.UTF_8) }
        return b
    }

    private fun validLocation(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun isJpeg(d: ByteArray) =
        d.size > 4 && (d[0].toInt() and 0xFF) == 0xFF && (d[1].toInt() and 0xFF) == 0xD8
}

class MediaChunk(val idHex: String, val index: Int, val total: Int, val data: ByteArray)
class MediaNack(val idHex: String, val missing: List<Int>)

/** Payloads of MEDIA and MEDIA_NACK packets. */
object MediaPayloads {
    /** transferId(8) index(2) total(2) data */
    fun encodeChunk(id: ByteArray, index: Int, total: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + data.size).put(id).putShort(index.toShort()).putShort(total.toShort()).put(data).array()

    fun decodeChunk(p: ByteArray): MediaChunk? {
        if (p.size <= 12) return null
        val b = ByteBuffer.wrap(p)
        val id = ByteArray(8).also { b.get(it) }
        val index = b.getShort().toInt() and 0xFFFF
        val total = b.getShort().toInt() and 0xFFFF
        val data = ByteArray(b.remaining()).also { b.get(it) }
        return MediaChunk(Hex.encode(id), index, total, data)
    }

    /** transferId(8) count(2) index(2)* */
    fun encodeNack(id: ByteArray, missing: List<Int>): ByteArray {
        val list = missing.take(MediaLimits.MAX_CHUNKS)
        val b = ByteBuffer.allocate(10 + list.size * 2).put(id).putShort(list.size.toShort())
        list.forEach { b.putShort(it.toShort()) }
        return b.array()
    }

    fun decodeNack(p: ByteArray): MediaNack? {
        if (p.size < 10) return null
        val b = ByteBuffer.wrap(p)
        val id = ByteArray(8).also { b.get(it) }
        val n = b.getShort().toInt() and 0xFFFF
        if (n > MediaLimits.MAX_CHUNKS || b.remaining() != n * 2) return null
        return MediaNack(Hex.encode(id), List(n) { b.getShort().toInt() and 0xFFFF })
    }
}
