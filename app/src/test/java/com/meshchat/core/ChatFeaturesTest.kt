package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEnvelopeTest {
    @Test
    fun replyTtlAndGroupSurviveEncoding() {
        val c = Content.ofText("জি, ঠিক আছে").copyEnvelope(ttlSec = 3600, replyToId = "0123456789ABCDEF", replyQuote = "কোথায় আছো?", groupId = "AAAAAAAAAAAAAAAA", logicalId = "BBBBBBBBBBBBBBBB")
        val d = ContentCodec.decode(ContentCodec.encode(c)!!)!!
        assertEquals("জি, ঠিক আছে", d.text)
        assertEquals(3600, d.ttlSec)
        assertEquals("0123456789ABCDEF", d.replyToId)
        assertEquals("কোথায় আছো?", d.replyQuote)
        assertEquals("AAAAAAAAAAAAAAAA", d.groupId)
        assertEquals("BBBBBBBBBBBBBBBB", d.logicalId)
    }

    @Test
    fun plainTextKeepsOldWireFormat() {
        assertEquals(listOf<Byte>(0, 104, 105), ContentCodec.encode(Content.ofText("hi"))!!.toList())
    }

    @Test
    fun longQuoteIsClippedNotRejected() {
        val c = Content.ofText("x").copyEnvelope(replyToId = "0123456789ABCDEF", replyQuote = "অ".repeat(200))
        val d = ContentCodec.decode(ContentCodec.encode(c)!!)!!
        assertTrue(d.replyQuote.toByteArray().size <= ContentCodec.MAX_QUOTE_BYTES)
        assertTrue(d.replyQuote.isNotEmpty())
    }

    @Test
    fun controlMessagesRoundTripAndBadOnesAreRejected() {
        val del = ContentCodec.decode(ContentCodec.encode(Content.ofDelete("0123456789ABCDEF"))!!)!!
        assertEquals(ContentKind.DELETE, del.kind); assertEquals("0123456789ABCDEF", del.targetId)
        val inv = ContentCodec.decode(ContentCodec.encode(Content.ofGroupInvite("0123456789ABCDEF", "Friends", listOf("AABBCCDD", "11223344", "55667788")))!!)!!
        assertEquals("Friends", inv.text); assertEquals(3, inv.members.size); assertEquals("11223344", inv.members[1])
        assertNull(ContentCodec.encode(Content.ofDelete("zz")))
        assertNull(ContentCodec.encode(Content.ofGroupInvite("0123456789ABCDEF", "x", listOf("AABBCCDD"))))      // needs 2+ members
        assertNull(ContentCodec.encode(Content.ofText("x").copyEnvelope(ttlSec = -5)))
        assertNull(ContentCodec.decode(byteArrayOf(0x84.toByte(), 0x00)))
    }
}

class GroupChatTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    private class T(val id: Identity, val store: InMemoryStore, val engine: MeshEngine) { val nid get() = id.nodeId }

    private fun node(name: String): T {
        val id = Identity.generate()
        val store = InMemoryStore()
        val e = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), store, scope, config)
        e.start()
        return T(id, store, e)
    }

    @After fun tearDown() { scope.cancel() }

    private suspend fun until(timeoutMs: Long = 10000, cond: () -> Boolean) { withTimeout(timeoutMs) { while (!cond()) delay(25) } }

    private suspend fun trio(): Triple<T, T, T> {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.nid, b.nid); net.link(b.nid, c.nid); net.link(a.nid, c.nid)
        until { listOf(a to b, a to c, b to c).all { (x, y) -> x.store.nodes[y.nid]?.publicKey != null && y.store.nodes[x.nid]?.publicKey != null } }
        return Triple(a, b, c)
    }

    @Test
    fun groupMessageReachesEveryMemberUnderTheSameId() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Team", listOf(b.nid, c.nid))!!
        until { b.store.groups.containsKey(gid) && c.store.groups.containsKey(gid) }
        assertEquals(SendResult.OK, a.engine.sendContent(gid, Content.ofText("hello team")))
        until { listOf(b, c).all { n -> n.store.messages.values.any { it.text == "hello team" && it.peerId == gid && it.senderId == a.nid } } }
        val ids = listOf(a, b, c).map { n -> n.store.messages.values.single { it.text == "hello team" }.msgId }.toSet()
        assertEquals(1, ids.size)                                   // same logical id on all three phones
        // B replies inside the group; A and C see it from B
        assertEquals(SendResult.OK, b.engine.sendContent(gid, Content.ofText("hi").copyEnvelope(replyToId = ids.first(), replyQuote = "hello team")))
        until { listOf(a, c).all { n -> n.store.messages.values.any { it.text == "hi" && it.senderId == b.nid && it.content.replyToId == ids.first() } } }
    }

    @Test
    fun deleteForEveryoneRemovesOnlyTheSendersOwnMessage() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Team", listOf(b.nid, c.nid))!!
        until { b.store.groups.containsKey(gid) && c.store.groups.containsKey(gid) }
        a.engine.sendContent(gid, Content.ofText("oops"))
        until { b.store.messages.values.any { it.text == "oops" } && c.store.messages.values.any { it.text == "oops" } }
        val id = a.store.messages.values.single { it.text == "oops" }.msgId
        // B cannot delete A's message for everyone
        b.engine.deleteMessage(gid, id, forEveryone = true)
        delay(600)
        assertTrue(c.store.messages.containsKey(id))
        assertTrue(a.store.messages.containsKey(id))
        // A can
        a.engine.deleteMessage(gid, id, forEveryone = true)
        until { !c.store.messages.containsKey(id) && !b.store.messages.containsKey(id) }
        assertFalse(a.store.messages.containsKey(id))
    }

    @Test
    fun oneToOneDeleteForEveryone() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.nid, b.nid)
        until { a.store.nodes[b.nid]?.publicKey != null && b.store.nodes[a.nid]?.publicKey != null }
        a.engine.sendContent(b.nid, Content.ofText("secret"))
        until { b.store.messages.values.any { it.text == "secret" } }
        val id = a.store.messages.values.single().msgId
        a.engine.deleteMessage(b.nid, id, true)
        until { b.store.messages.isEmpty() && a.store.messages.isEmpty() }
    }

    @Test
    fun outsiderAndUnknownGroupMessagesAreNotStored() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Duo", listOf(b.nid))!!              // C is not a member
        until { b.store.groups.containsKey(gid) }
        // C pretends to post into the group by wrapping a group id into a private message
        c.store.groups[gid] = GroupRecord(gid, "Duo", a.nid, listOf(a.nid, b.nid, c.nid), 0)
        c.engine.sendContent(gid, Content.ofText("let me in"))
        delay(1200)
        assertTrue(a.store.messages.values.none { it.text == "let me in" })
        assertTrue(b.store.messages.values.none { it.text == "let me in" })
    }

    @Test
    fun leavingRemovesTheMemberEverywhere() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Team", listOf(b.nid, c.nid))!!
        until { b.store.groups.containsKey(gid) && c.store.groups.containsKey(gid) }
        c.engine.leaveGroup(gid)
        until { a.store.groups[gid]!!.members.size == 2 && b.store.groups[gid]!!.members.size == 2 }
        assertNull(c.store.groups[gid])
    }

    @Test
    fun groupLimitsAndMissingKeys() = runBlocking {
        val a = node("A")
        assertNull(a.engine.createGroup("x", emptyList()))                          // needs another member
        assertNull(a.engine.createGroup("x", listOf("DEADBEEF")))                   // key unknown
        assertEquals(SendResult.NO_GROUP, a.engine.sendContent("0123456789ABCDEF", Content.ofText("hi")))
    }

    @Test
    fun replyAndDisappearingTimerTravelWithOneToOneMessages() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.nid, b.nid)
        until { a.store.nodes[b.nid]?.publicKey != null && b.store.nodes[a.nid]?.publicKey != null }
        a.engine.sendContent(b.nid, Content.ofText("first"))
        until { b.store.messages.values.any { it.text == "first" } }
        val firstId = b.store.messages.values.single { it.text == "first" }.msgId
        b.engine.sendContent(a.nid, Content.ofText("answer").copyEnvelope(replyToId = firstId, replyQuote = "first", ttlSec = 60))
        until { a.store.messages.values.any { it.text == "answer" } }
        val m = a.store.messages.values.single { it.text == "answer" }
        assertEquals(firstId, m.content.replyToId)
        assertEquals(60, m.content.ttlSec)
        assertNotNull(a.store.messages[firstId])                                    // the quoted message has the same id on both phones
    }
}

class PttClipTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    @After fun tearDown() { scope.cancel() }

    private fun clip(ms: Int = 6000) = Content.ofVoice(VoiceCodec.OPUS_OGG, ms, ByteArray(MediaLimits.WAVE_BARS) { 5 }, ByteArray(4000) { (it * 7).toByte() }).copyEnvelope(ptt = true)

    @Test
    fun clipFlagSurvivesEncodingAndLongClipsAreRefused() {
        val d = ContentCodec.decode(ContentCodec.encode(clip())!!)!!
        assertTrue(d.ptt); assertEquals(ContentKind.VOICE, d.kind); assertEquals(6000, d.durationMs)
        assertTrue(d.preview().startsWith("📻"))
        assertNull(ContentCodec.encode(clip(40_000)))                               // too long for push-to-talk
        assertNull(ContentCodec.encode(Content.ofText("x").copyEnvelope(ptt = true)))  // only voice can be a PTT clip
        assertFalse(ContentCodec.decode(ContentCodec.encode(Content.ofVoice(1, 6000, ByteArray(4), ByteArray(100)))!!)!!.ptt)
    }

    private suspend fun until(timeoutMs: Long = 15000, cond: () -> Boolean) { withTimeout(timeoutMs) { while (!cond()) delay(25) } }

    private fun node(name: String, allowed: () -> Boolean = { true }): Triple<Identity, InMemoryStore, MeshEngine> {
        val id = Identity.generate()
        val st = InMemoryStore()
        val e = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), st, scope, config, pttAllowed = allowed)
        e.start()
        return Triple(id, st, e)
    }

    @Test
    fun clipIsDeliveredAndFlaggedWhenTheReceiverAllowsPushToTalk() = runBlocking {
        val (ia, sa, ea) = node("A"); val (ib, sb, _) = node("B")
        net.link(ia.nodeId, ib.nodeId)
        until { sa.nodes[ib.nodeId]?.publicKey != null && sb.nodes[ia.nodeId]?.publicKey != null }
        assertEquals(SendResult.OK, ea.sendContent(ib.nodeId, clip()))
        until { sb.messages.values.any { it.content.ptt } }
        until { sa.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun receiverWithPushToTalkOffStoresNothingButTheSenderStopsRetrying() = runBlocking {
        val (ia, sa, ea) = node("A"); val (ib, sb, _) = node("B") { false }
        net.link(ia.nodeId, ib.nodeId)
        until { sa.nodes[ib.nodeId]?.publicKey != null && sb.nodes[ia.nodeId]?.publicKey != null }
        ea.sendContent(ib.nodeId, clip())
        until { sa.messages.values.any { it.status == MessageStatus.DELIVERED } }
        assertTrue(sb.messages.isEmpty())
        // ordinary voice messages are not affected by the switch
        ea.sendContent(ib.nodeId, Content.ofVoice(VoiceCodec.OPUS_OGG, 5000, ByteArray(MediaLimits.WAVE_BARS), ByteArray(3000) { it.toByte() }))
        until { sb.messages.values.any { it.content.kind == ContentKind.VOICE && !it.content.ptt } }
    }
}
