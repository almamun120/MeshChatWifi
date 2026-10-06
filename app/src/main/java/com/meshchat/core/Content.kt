package com.meshchat.core

import java.nio.ByteBuffer

/** Size limits that keep media realistic over BLE (see README "Media over BLE"). */
object MediaLimits {
    const val CHUNK = 900                    // bytes of encrypted blob per mesh packet
    const val MAX_CHUNKS = 200
    const val MAX_BLOB = 170_000             // encrypted blob (nonce + ciphertext + tag)
    const val MAX_VOICE_MS = 60_000
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
    TEXT(0), LOCATION(1), IMAGE(2), VOICE(3);

    companion object {
        fun fromCode(c: Int): ContentKind? = entries.firstOrNull { it.code == c }
    }
}

/**
 * What a private message carries. Always encrypted end to end before it leaves the phone.
 * LOCATION exists only as an explicit, per-recipient user action: nothing is ever broadcast.
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
        ContentKind.VOICE -> "🎤 Voice message (${formatDuration(durationMs)})"
    }

    companion object {
        fun ofText(t: String) = Content(ContentKind.TEXT, text = t)
        fun ofLocation(lat: Double, lon: Double, accuracyM: Int) =
            Content(ContentKind.LOCATION, lat = lat, lon = lon, accuracyM = accuracyM)

        fun ofImage(jpeg: ByteArray) = Content(ContentKind.IMAGE, data = jpeg)
        fun ofVoice(codec: Int, durationMs: Int, waveform: ByteArray, audio: ByteArray) =
            Content(ContentKind.VOICE, durationMs = durationMs, codec = codec, waveform = waveform, data = audio)

        fun formatDuration(ms: Int): String {
            val s = (ms + 500) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/**
 * Plaintext layout inside the encrypted blob:
 *  TEXT     kind(1) utf8
 *  LOCATION kind(1) lat(8) lon(8) accuracyM(4)
 *  IMAGE    kind(1) jpeg
 *  VOICE    kind(1) codec(1) durationMs(4) waveLen(1) wave[waveLen] audio
 */
object ContentCodec {

    fun encode(c: Content): ByteArray? = when (c.kind) {
        ContentKind.TEXT -> byteArrayOf(0) + c.text.toByteArray(Charsets.UTF_8)
        ContentKind.LOCATION -> {
            if (!validLocation(c.lat, c.lon)) null
            else ByteBuffer.allocate(21).put(1).putDouble(c.lat).putDouble(c.lon).putInt(c.accuracyM).array()
        }
        ContentKind.IMAGE -> {
            val d = c.data
            if (d == null || !isJpeg(d) || d.size > MediaLimits.IMAGE_MAX_BYTES) null else byteArrayOf(2) + d
        }
        ContentKind.VOICE -> {
            val d = c.data
            if (d == null || d.isEmpty() || c.durationMs !in 1..MediaLimits.MAX_VOICE_MS + 2000 ||
                (c.codec != VoiceCodec.OPUS_OGG && c.codec != VoiceCodec.AAC_M4A) || c.waveform.size > 64
            ) null
            else {
                val b = ByteBuffer.allocate(1 + 1 + 4 + 1 + c.waveform.size + d.size)
                b.put(3).put(c.codec.toByte()).putInt(c.durationMs).put(c.waveform.size.toByte()).put(c.waveform).put(d)
                b.array()
            }
        }
    }

    fun decode(bytes: ByteArray): Content? {
        if (bytes.isEmpty()) return null
        val kind = ContentKind.fromCode(bytes[0].toInt() and 0xFF) ?: return null
        return try {
            val b = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
            when (kind) {
                ContentKind.TEXT -> {
                    val t = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                    if (t.length > MediaLimits.MAX_TEXT_CHARS) null else Content.ofText(t)
                }
                ContentKind.LOCATION -> {
                    if (bytes.size != 21) return null
                    val lat = b.getDouble()
                    val lon = b.getDouble()
                    val acc = b.getInt()
                    if (!validLocation(lat, lon) || acc < 0) null else Content.ofLocation(lat, lon, acc)
                }
                ContentKind.IMAGE -> {
                    val d = bytes.copyOfRange(1, bytes.size)
                    if (!isJpeg(d) || d.size > MediaLimits.IMAGE_MAX_BYTES) null else Content.ofImage(d)
                }
                ContentKind.VOICE -> {
                    if (bytes.size < 8) return null
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
            }
        } catch (e: Exception) {
            null
        }
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
