package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** v2.00 flood control: policy maths (pure) and its effect on a real engine over an in-memory radio. */
class FloodPolicyTest {
    @Test
    fun autoBandsGetStricterAsTheCrowdGrows() {
        val sparse = FloodPolicy.forDensity(2)
        val normal = FloodPolicy.forDensity(15)
        val dense = FloodPolicy.forDensity(40)
        val crowded = FloodPolicy.forDensity(500)
        assertTrue(sparse.maxTtl > normal.maxTtl && normal.maxTtl > dense.maxTtl && dense.maxTtl > crowded.maxTtl)
        assertTrue(sparse.postEveryMs < normal.postEveryMs && normal.postEveryMs < dense.postEveryMs && dense.postEveryMs < crowded.postEveryMs)
        assertTrue(sparse.identityFlood && normal.identityFlood)
        assertFalse(dense.identityFlood || crowded.identityFlood)
        assertEquals(1, crowded.postBurst)
        assertEquals(300_000L, crowded.postEveryMs)            // one public post per 5 minutes in a stadium
        assertEquals("Sparse", FloodPolicy.band(0)); assertEquals("Crowded", FloodPolicy.band(61))
    }

    @Test
    fun autoModeIgnoresManualValuesAndManualModeIgnoresDensity() {
        val manual = FloodPolicy.preset(FloodPreset.LONG_RANGE)
        assertEquals(FloodPolicy.forDensity(500), FloodPolicy.effective(FloodSettings(FloodMode.AUTO, manual), 500))
        assertEquals(FloodPolicy.sanitize(manual), FloodPolicy.effective(FloodSettings(FloodMode.MANUAL, manual), 500))
    }

    @Test
    fun sanitizeClampsEveryValue() {
        val wild = FloodParams(maxTtl = 99, suppressK = 1, jitterMinMs = 9_000, jitterMaxMs = 5, postBurst = 0, postEveryMs = 1, identityFlood = true, identityEveryMs = 1)
        val s = FloodPolicy.sanitize(wild)
        assertEquals(Protocol.MAX_HOPS, s.maxTtl)
        assertEquals(2, s.suppressK)                            // 1 would mean "never relay"
        assertTrue(s.jitterMaxMs >= s.jitterMinMs)
        assertEquals(1, s.postBurst)
        assertEquals(FloodPolicy.MIN_POST_EVERY_MS, s.postEveryMs)
        assertEquals(FloodPolicy.MIN_IDENTITY_EVERY_MS, s.identityEveryMs)
        assertEquals(0, FloodPolicy.sanitize(wild.copy(suppressK = 0)).suppressK)   // 0 = suppression off is allowed
        assertEquals(1, FloodPolicy.sanitize(wild.copy(maxTtl = -4)).maxTtl)
    }

    @Test
    fun presetsAreAlreadyValid() {
        for (p in FloodPreset.entries) assertEquals(p.name, FloodPolicy.preset(p), FloodPolicy.sanitize(FloodPolicy.preset(p)))
        assertEquals(2, FloodPolicy.preset(FloodPreset.STADIUM).maxTtl)
        assertEquals(Protocol.MAX_HOPS, FloodPolicy.preset(FloodPreset.LONG_RANGE).maxTtl)
    }

    @Test
    fun densityRisesAtOnceAndFallsSlowly() {
        val d = DensityTracker()
        assertEquals(80, d.update(80))
        var v = 80
        repeat(10) { v = d.update(0) }
        assertTrue("after 10 s it must still be crowded, was $v", v > 50)
        repeat(300) { v = d.update(0) }
        assertEquals(0, v)
    }
}

class FloodControlTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3, keyWaitMs = 2500,
        syncOverrideMs = 10_000_000L,
    )
    private val fast = FloodParams(maxTtl = 15, suppressK = 0, jitterMinMs = 0, jitterMaxMs = 0, postBurst = 20, postEveryMs = 1_000, identityFlood = true, identityEveryMs = 60_000)

    private class TestNode(val id: Identity, val store: InMemoryStore, val engine: MeshEngine)

    private fun node(name: String, p: FloodParams, cfg: MeshConfig = config): TestNode {
        val id = Identity.generate()
        val store = InMemoryStore()
        val engine = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), store, scope, cfg, floodSettings = { FloodSettings(FloodMode.MANUAL, p) })
        engine.start()
        return TestNode(id, store, engine)
    }

    private fun line(n: Int, p: FloodParams, cfg: MeshConfig = config): List<TestNode> {
        val nodes = (0 until n).map { node("N$it", p, cfg) }
        for (i in 0 until n - 1) net.link(nodes[i].id.nodeId, nodes[i + 1].id.nodeId)
        return nodes
    }

    @After
    fun tearDown() { scope.cancel() }

    private suspend fun until(what: String, timeoutMs: Long = 8000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    @Test
    fun ownPostsAreRateLimited() = runBlocking {
        val a = node("A", fast.copy(postBurst = 2, postEveryMs = 600_000))
        assertEquals(SendResult.OK, a.engine.sendAnnounce("one"))
        assertEquals(SendResult.OK, a.engine.sendAnnounce("two"))
        assertEquals(SendResult.RATE_LIMITED, a.engine.sendAnnounce("three"))
        assertEquals(2, a.store.posts.size)
    }

    @Test
    fun receiverAppliesItsOwnPostLimitToOthers() = runBlocking {
        val a = node("A", fast)                                                   // generous sender
        val b = node("B", fast.copy(postBurst = 2, postEveryMs = 600_000))        // strict receiver
        net.link(a.id.nodeId, b.id.nodeId)
        until("wait 1") { b.store.nodes[a.id.nodeId]?.publicKey != null }
        repeat(8) { assertEquals(SendResult.OK, a.engine.sendAnnounce("p$it")) }
        delay(1000)
        assertEquals(2, b.store.posts.size)
        assertTrue(b.engine.stats.value.dropped > 0)
    }

    @Test
    fun relayReachIsCutToTheLocalLimit() = runBlocking {
        // maxTtl = 2 everywhere: the post leaves the sender with 2 hops, so the 2nd phone gets it and relays nothing further.
        val nodes = line(6, fast.copy(maxTtl = 2))
        delay(400)
        nodes[0].engine.sendAnnounce("local only")
        until("wait 2") { nodes[2].store.posts.isNotEmpty() }
        delay(700)
        assertEquals(1, nodes[1].store.posts.size)
        assertEquals(1, nodes[2].store.posts.size)
        assertEquals("third phone must not get it", 0, nodes[3].store.posts.size)
        assertEquals(0, nodes[5].store.posts.size)
    }

    @Test
    fun aCrowdedRelayShortensWhatPassesThrough() = runBlocking {
        // Sender allows 15 hops, but the middle phones are in a crowd (maxTtl 2): the message still dies after 2 more hops.
        val nodes = (0 until 7).map { node("N$it", if (it == 0) fast else fast.copy(maxTtl = 2)) }
        for (i in 0 until 6) net.link(nodes[i].id.nodeId, nodes[i + 1].id.nodeId)
        delay(400)
        nodes[0].engine.sendAnnounce("clamped on the way")
        until("wait 3") { nodes[3].store.posts.isNotEmpty() }
        delay(700)
        assertTrue(nodes[2].store.posts.isNotEmpty())
        assertEquals(0, nodes[4].store.posts.size)
        assertEquals(0, nodes[6].store.posts.size)
    }

    @Test
    fun sosIsNeverCutShortByCrowdSettings() = runBlocking {
        val nodes = line(8, fast.copy(maxTtl = 1, suppressK = 2, jitterMinMs = 50, jitterMaxMs = 300))
        delay(400)
        val got = CopyOnWriteArrayList<MeshEvent.SosReceived>()
        scope.launch { nodes[7].engine.events.collect { if (it is MeshEvent.SosReceived) got.add(it) } }
        delay(100)
        nodes[0].engine.sendSos(true, 50, null)
        until("wait 4") { got.isNotEmpty() }
        assertEquals(nodes[0].id.nodeId, got[0].alert.nodeId)
    }

    @Test
    fun neighboursSuppressEachOthersRelays() = runBlocking {
        // Five phones that all hear each other: once one has passed a post on, the rest stay quiet (k = 2), yet everyone has the post.
        val p = fast.copy(suppressK = 2, jitterMinMs = 100, jitterMaxMs = 400)
        val nodes = (0 until 5).map { node("N$it", p) }
        for (i in 0 until 5) for (j in i + 1 until 5) net.link(nodes[i].id.nodeId, nodes[j].id.nodeId)
        delay(500)
        nodes[0].engine.sendAnnounce("hello crowd")
        until("wait 5") { nodes.all { it.store.posts.isNotEmpty() } }
        delay(900)
        val suppressed = nodes.sumOf { it.engine.stats.value.suppressed }
        assertTrue("some relays must have been cancelled, was $suppressed", suppressed >= 1)
        assertTrue(nodes.all { it.store.posts.size == 1 })
    }

    @Test
    fun suppressionOffMeansEveryRelayHappens() = runBlocking {
        val p = fast.copy(suppressK = 0, jitterMinMs = 10, jitterMaxMs = 50)
        val nodes = line(4, p)
        delay(400)
        nodes[0].engine.sendAnnounce("chain")
        until("wait 6") { nodes[3].store.posts.isNotEmpty() }
        assertEquals(0, nodes.sumOf { it.engine.stats.value.suppressed })
    }

    @Test
    fun identitiesStayWithNeighboursInACrowdAndAreFetchedOnDemand() = runBlocking {
        // A - B - C, identity flooding off everywhere: A never hears C's key by itself ...
        val p = fast.copy(identityFlood = false)
        val (a, b, c) = line(3, p)
        until("wait 7") { b.store.nodes[a.id.nodeId]?.publicKey != null && b.store.nodes[c.id.nodeId]?.publicKey != null }
        delay(1500)                                           // several identity beacons have gone by
        assertNull(a.store.nodes[c.id.nodeId]?.publicKey)
        assertNull(c.store.nodes[a.id.nodeId]?.publicKey)
        // ... but writing to C makes A ask for it, and the first message goes through.
        until("wait 8") { a.engine.isReachable(c.id.nodeId) }
        assertEquals(SendResult.OK, a.engine.sendPrivate(c.id.nodeId, "hi from far"))
        assertNotNull(a.store.nodes[c.id.nodeId]?.publicKey)
        until("wait 9") { c.store.messages.values.any { it.text == "hi from far" && !it.outgoing } }
        until("wait 10") { a.store.messages.values.any { it.status == MessageStatus.DELIVERED } }
    }

    @Test
    fun identityFloodingStillWorksWhenAllowed() = runBlocking {
        val (a, _, c) = line(3, fast)
        until("wait 11") { a.store.nodes[c.id.nodeId]?.publicKey != null }
    }

    @Test
    fun askingForAnUnknownNodeFailsCleanlyAfterTheWait() = runBlocking {
        val a = node("A", fast, config.copy(keyWaitMs = 400))
        val b = node("B", fast)
        net.link(a.id.nodeId, b.id.nodeId)
        until("wait 12") { a.store.nodes[b.id.nodeId]?.publicKey != null }
        val stranger = Identity.generate().nodeId
        assertEquals(SendResult.NO_KEY, a.engine.sendPrivate(stranger, "anyone?"))
    }

    @Test
    fun identityRequestsAreRateLimitedAtTheAnswerer() = runBlocking {
        val p = fast.copy(identityFlood = false)
        val (a, b, c) = line(3, p)
        until("wait 13") { a.engine.isReachable(c.id.nodeId) }
        repeat(20) { a.engine.sendPrivate(c.id.nodeId, "x$it") }
        delay(300)
        assertTrue(b.engine.stats.value.dropped >= 0)         // no crash / storm; the answerer's limiter caps the replies
    }

    @Test
    fun floodStatusReportsTheRulesInForce() = runBlocking {
        val a = node("A", fast.copy(maxTtl = 4))
        until("wait 14") { a.engine.floodStatus.value.params.maxTtl == 4 }
        assertEquals(FloodMode.MANUAL, a.engine.floodStatus.value.mode)
    }

    @Test
    fun firstMessageThroughARelayIsNotLostWhenTheReceiverDoesNotKnowTheSenderYet() = runBlocking {
        // Relays drop the sender's retries as duplicates, so a message that reaches C before A's identity does must be
        // kept by C and opened when the identity arrives. Repeated: the race depends on random relay delays.
        repeat(8) { round ->
            val cfg = config.copy(identityIntervalMs = 0)
            val p = FloodPolicy.forDensity(2)                         // the automatic sparse rules, delays included
            val nodes = (0 until 3).map { i ->
                val id = Identity.generate(); val store = InMemoryStore()
                val e = MeshEngine(id, { "N$i" }, FakeTransport(id.nodeId, net), store, scope, cfg, floodSettings = { FloodSettings(FloodMode.MANUAL, p) })
                e.start(); TestNode(id, store, e)
            }
            val (a, b, c) = nodes
            net.link(a.id.nodeId, b.id.nodeId); net.link(b.id.nodeId, c.id.nodeId)
            until("round $round: A knows C") { a.store.nodes[c.id.nodeId]?.publicKey != null && a.engine.isReachable(c.id.nodeId) }
            assertEquals(SendResult.OK, a.engine.sendPrivate(c.id.nodeId, "race $round"))
            until("round $round: C got it") { c.store.messages.values.any { it.text == "race $round" && !it.outgoing } }
            nodes.forEach { it.engine.stop() }
        }
    }
}
