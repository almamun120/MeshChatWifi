package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ContentTest {
    private fun fakeJpeg(n: Int) = ByteArray(n).also { Random(7).nextBytes(it); it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }

    @Test
    fun allKindsRoundTrip() {
        val text = ContentCodec.decode(ContentCodec.encode(Content.ofText("হ্যালো Mesh 👋"))!!)!!
        assertEquals("হ্যালো Mesh 👋", text.text)

        val loc = ContentCodec.decode(ContentCodec.encode(Content.ofLocation(23.81031, 90.41252, 12))!!)!!
        assertEquals(23.81031, loc.lat, 1e-9)
        assertEquals(90.41252, loc.lon, 1e-9)
        assertEquals(12, loc.accuracyM)

        val jpg = fakeJpeg(30_000)
        val img = ContentCodec.decode(ContentCodec.encode(Content.ofImage(jpg))!!)!!
        assertEquals(ContentKind.IMAGE, img.kind)
        assertArrayEquals(jpg, img.data)

        val wave = ByteArray(MediaLimits.WAVE_BARS) { (it * 6).toByte() }
        val audio = ByteArray(20_000).also { Random(3).nextBytes(it) }
        val v = ContentCodec.decode(ContentCodec.encode(Content.ofVoice(VoiceCodec.OPUS_OGG, 12_300, wave, audio))!!)!!
        assertEquals(12_300, v.durationMs)
        assertEquals(VoiceCodec.OPUS_OGG, v.codec)
        assertArrayEquals(wave, v.waveform)
        assertArrayEquals(audio, v.data)
        assertEquals("ogg", v.extension())
    }

    @Test
    fun invalidContentIsRejected() {
        assertNull(ContentCodec.encode(Content.ofImage(ByteArray(100))))                       // not a JPEG
        assertNull(ContentCodec.encode(Content.ofLocation(120.0, 0.0, 5)))                      // latitude out of range
        assertNull(ContentCodec.encode(Content.ofVoice(9, 1000, ByteArray(0), ByteArray(10)))) // unknown codec
        assertNull(ContentCodec.encode(Content.ofVoice(1, 90_000, ByteArray(0), ByteArray(10)))) // too long
        assertNull(ContentCodec.decode(byteArrayOf(2, 1, 2, 3, 4, 5, 6)))                       // image without JPEG magic
        assertNull(ContentCodec.decode(byteArrayOf(1, 0, 0)))                                   // truncated location
        assertNull(ContentCodec.decode(byteArrayOf()))
        assertNull(ContentCodec.decode(byteArrayOf(9, 1, 2)))                                   // unknown kind
    }

    @Test
    fun chunkAndNackPayloads() {
        val id = ByteArray(8) { it.toByte() }
        val c = MediaPayloads.decodeChunk(MediaPayloads.encodeChunk(id, 3, 11, byteArrayOf(5, 6, 7)))!!
        assertEquals(3, c.index); assertEquals(11, c.total); assertArrayEquals(byteArrayOf(5, 6, 7), c.data)
        assertEquals(Hex.encode(id), c.idHex)
        val n = MediaPayloads.decodeNack(MediaPayloads.encodeNack(id, listOf(1, 4, 9)))!!
        assertEquals(listOf(1, 4, 9), n.missing)
        assertNull(MediaPayloads.decodeChunk(ByteArray(12)))
        assertNull(MediaPayloads.decodeNack(ByteArray(9)))
    }

    @Test
    fun voiceFitsBleBudget() {
        // 60 s of 16 kbps speech ~ 120 KB must fit the transfer limit (with its header, waveform and AES-GCM overhead)
        val audio = ByteArray(60 * 16_000 / 8)
        val plain = ContentCodec.encode(Content.ofVoice(VoiceCodec.OPUS_OGG, 60_000, ByteArray(40), audio))!!
        assertTrue(plain.size + 28 <= MediaLimits.MAX_BLOB)
        assertTrue((plain.size + 28 + MediaLimits.CHUNK - 1) / MediaLimits.CHUNK <= MediaLimits.MAX_CHUNKS)
    }
}

/** Image/voice/location over the real engine with an in-memory radio. */
class MediaEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        nackIdleMs = 400, mediaRetryBackoffMs = listOf(60_000L), maxMediaAttempts = 3,
    )

    private class TestNode(val id: Identity, val store: InMemoryStore, val engine: MeshEngine, val radio: FakeTransport)

    private fun node(name: String): TestNode {
        val id = Identity.generate()
        val store = InMemoryStore()
        val radio = FakeTransport(id.nodeId, net)
        val engine = MeshEngine(id, { name }, radio, store, scope, config)
        engine.start()
        return TestNode(id, store, engine, radio)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun until(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(25) }
    }

    private fun jpeg(n: Int) = ByteArray(n).also { Random(11).nextBytes(it); it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }

    private fun TestNode.received(kind: ContentKind) =
        store.messages.values.firstOrNull { !it.outgoing && it.content.kind == kind }

    @Test
    fun imageTravelsAtoBtoCEncrypted() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId); net.link(b.id.nodeId, c.id.nodeId)
        until { a.store.nodes[c.id.nodeId]?.publicKey != null }
        until { a.engine.routes.value.any { it.dest == c.id.nodeId && it.hops == 2 } }

        val photo = jpeg(45_000)
        assertEquals(SendResult.OK, a.engine.sendContent(c.id.nodeId, Content.ofImage(photo)))
        until { c.received(ContentKind.IMAGE) != null }
        assertArrayEquals(photo, c.received(ContentKind.IMAGE)!!.content.data)           // bit-exact after reassembly + decrypt
        until { a.store.messages.values.any { it.outgoing && it.status == MessageStatus.DELIVERED } }
        until { a.store.outTransfers.isEmpty() }                                           // outbox cleared after the ACK
        assertTrue("relay must hold no message", b.store.messages.isEmpty())
        assertTrue("relay must hold no media chunks", b.store.outTransfers.isEmpty() && b.store.pending.isEmpty())
    }

    @Test
    fun voiceMessageKeepsWaveformAndDuration() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null }
        val wave = ByteArray(MediaLimits.WAVE_BARS) { (255 - it * 5).toByte() }
        val audio = ByteArray(60_000).also { Random(5).nextBytes(it) }
        assertEquals(SendResult.OK, a.engine.sendContent(b.id.nodeId, Content.ofVoice(VoiceCodec.OPUS_OGG, 31_000, wave, audio)))
        until { b.received(ContentKind.VOICE) != null }
        val v = b.received(ContentKind.VOICE)!!.content
        assertEquals(31_000, v.durationMs)
        assertArrayEquals(wave, v.waveform)
        assertArrayEquals(audio, v.data)
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun locationIsEncryptedAndDelivered() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null }
        assertEquals(SendResult.OK, a.engine.sendContent(b.id.nodeId, Content.ofLocation(23.8103, 90.4125, 9)))
        until { b.received(ContentKind.LOCATION) != null }
        val l = b.received(ContentKind.LOCATION)!!.content
        assertEquals(23.8103, l.lat, 1e-9); assertEquals(90.4125, l.lon, 1e-9)
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun imageForOfflineUserIsKeptThenDelivered() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId)
        a.store.upsertNodeIdentity(c.id.nodeId, "C", c.id.publicBytes, 0)                // A met C earlier
        c.store.upsertNodeIdentity(a.id.nodeId, "A", a.id.publicBytes, 0)

        val photo = jpeg(20_000)
        assertEquals(SendResult.OK, a.engine.sendContent(c.id.nodeId, Content.ofImage(photo)))
        delay(700)
        assertEquals(1, a.store.outTransfers.size)                                         // saved locally, waiting
        assertEquals(MessageStatus.QUEUED, a.store.messages.values.first().status)
        assertTrue(b.store.outTransfers.isEmpty() && b.store.pending.isEmpty() && c.store.messages.isEmpty())

        net.link(b.id.nodeId, c.id.nodeId)                                                 // C becomes reachable
        until { c.received(ContentKind.IMAGE) != null }
        assertArrayEquals(photo, c.received(ContentKind.IMAGE)!!.content.data)
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
        assertTrue(a.store.outTransfers.isEmpty())
    }

    @Test
    fun lostChunkIsRecoveredByNack() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null }

        // Lose chunk #3 on its first transmission only. The full-transfer retry is 60 s away, so the only way the
        // image can arrive within the timeout is the receiver's NACK + selective retransmission.
        var dropped = 0
        a.radio.dropHook = { _, data ->
            val p = MeshPacket.decode(data)
            val ch = if (p?.type == PacketType.MEDIA) MediaPayloads.decodeChunk(p.payload) else null
            if (ch != null && ch.index == 3 && dropped == 0) { dropped++; true } else false
        }
        val photo = jpeg(30_000)
        assertEquals(SendResult.OK, a.engine.sendContent(b.id.nodeId, Content.ofImage(photo)))
        until(timeoutMs = 8_000) { b.received(ContentKind.IMAGE) != null }
        assertEquals(1, dropped)
        assertArrayEquals(photo, b.received(ContentKind.IMAGE)!!.content.data)
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun oversizedOrInvalidMediaIsRefused() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null }
        assertEquals(SendResult.BAD_MEDIA, a.engine.sendContent(b.id.nodeId, Content.ofImage(ByteArray(500))))    // not a JPEG
        assertEquals(SendResult.BAD_MEDIA, a.engine.sendContent(b.id.nodeId, Content.ofImage(jpeg(MediaLimits.IMAGE_MAX_BYTES + 10))))
        assertTrue(a.store.outTransfers.isEmpty())
        assertNotNull(a.engine)
    }
}
