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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-node tests over an in-memory radio. They run the REAL engine, packet codec, fragmentation,
 * signing and encryption; only the BLE radio is replaced.
 */
class MeshEngineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    private class TestNode(val id: Identity, val store: InMemoryStore, val engine: MeshEngine)

    private fun node(name: String, cfg: MeshConfig = config): TestNode {
        val id = Identity.generate()
        val store = InMemoryStore()
        val engine = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), store, scope, cfg)
        engine.start()
        return TestNode(id, store, engine)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun until(timeoutMs: Long = 8000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(25) }
    }

    @Test
    fun twoPhonesPrivateHelloMesh() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null }

        assertEquals(SendResult.OK, a.engine.sendPrivate(b.id.nodeId, "Hello Mesh"))
        until { b.store.messages.values.any { it.text == "Hello Mesh" && !it.outgoing } }
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun threePhonesRelayAtoBtoC() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId)
        net.link(b.id.nodeId, c.id.nodeId)

        // A has no direct link to C: it must learn C's key (flooded IDENTITY) and a 2-hop route (HELLO).
        until { a.store.nodes[c.id.nodeId]?.publicKey != null }
        until { a.engine.routes.value.any { it.dest == c.id.nodeId && it.hops == 2 && it.nextHop == b.id.nodeId } }

        assertEquals(SendResult.OK, a.engine.sendPrivate(c.id.nodeId, "A to C via B"))
        until { c.store.messages.values.any { it.text == "A to C via B" } }
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }

        // B relayed but cannot have the plaintext: it never stored a message.
        assertTrue(b.store.messages.isEmpty())
        assertTrue(b.engine.stats.value.relayed > 0)
    }

    @Test
    fun announceIsFloodedOnceAndDeduplicated() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId)
        net.link(b.id.nodeId, c.id.nodeId)
        until { a.store.nodes[c.id.nodeId]?.publicKey != null && c.store.nodes[a.id.nodeId]?.publicKey != null }

        assertEquals(SendResult.OK, a.engine.sendAnnounce("আজ বিকাল ৫টায় মাঠে মিটিং হবে।"))
        until { c.store.posts.size == 1 && b.store.posts.size == 1 }
        delay(800) // give any (wrong) duplicate time to appear
        assertEquals(1, c.store.posts.size)
        val post = c.store.posts.values.first()
        assertEquals(a.id.nodeId, post.authorId)
        assertTrue("signature must verify", post.verified)
        assertEquals("A", post.authorName)
    }

    @Test
    fun ttlLimitsPropagation() = runBlocking {
        // A line of 10 nodes: the live FLOOD of an Announce (TTL 7) cannot reach beyond 7 transmissions.
        // (Background inventory sync is switched off here; with it on, posts keep spreading hop by hop, still deduplicated.)
        val noSync = config.copy(syncOverrideMs = 10_000_000L)
        val nodes = (0 until 10).map { node("N$it", noSync) }
        for (i in 0 until 9) net.link(nodes[i].id.nodeId, nodes[i + 1].id.nodeId)
        delay(500)
        nodes[0].engine.sendAnnounce("far away")
        until { nodes[7].store.posts.isNotEmpty() }
        delay(800) // give any (wrong) further propagation time to appear
        assertTrue("7 transmissions is the TTL limit", nodes[7].store.posts.isNotEmpty())
        assertEquals("node 8 must NOT receive (TTL expired)", 0, nodes[8].store.posts.size)
        assertEquals("node 9 must NOT receive (TTL expired)", 0, nodes[9].store.posts.size)
    }

    @Test
    fun storeAndForwardDeliversWhenDestinationAppears() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId)
        // A knows C's key (e.g. met earlier) but C is currently unreachable.
        a.store.upsertNodeIdentity(c.id.nodeId, "C", c.id.publicBytes, 0)
        c.store.upsertNodeIdentity(a.id.nodeId, "A", a.id.publicBytes, 0)

        assertEquals(SendResult.OK, a.engine.sendPrivate(c.id.nodeId, "see you later"))
        delay(600)
        assertEquals(1, a.store.pending.size)                  // held, not lost
        assertEquals(MessageStatus.QUEUED, a.store.messages.values.first().status)

        net.link(b.id.nodeId, c.id.nodeId)                      // C appears
        until { c.store.messages.values.any { it.text == "see you later" } }
        until { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
        assertTrue(a.store.pending.isEmpty())
    }

    @Test
    fun announceHistorySyncsToLateJoiner() = runBlocking {
        val a = node("A"); val b = node("B")
        a.engine.sendAnnounce("old news 1")
        a.engine.sendAnnounce("old news 2")
        assertEquals(2, a.store.posts.size)

        net.link(a.id.nodeId, b.id.nodeId)                      // B was not around when posted
        until { b.store.posts.size == 2 }
        assertNotNull(b.store.posts.values.firstOrNull { it.content == "old news 1" })
        assertTrue(a.engine.stats.value.synced >= 2)
    }

    @Test
    fun postSpamIsRateLimited() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { b.store.nodes[a.id.nodeId]?.publicKey != null }
        repeat(12) { a.engine.sendAnnounce("spam $it") }
        delay(1200)
        assertTrue("limiter should cap accepted posts", b.store.posts.size in 1..6)
        assertTrue(b.engine.stats.value.dropped > 0)
    }
}
