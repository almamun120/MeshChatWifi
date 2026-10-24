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
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.10: LoRa relay recognition and compatibility with MeshChat 1.x. */
class BridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3, keyWaitMs = 2500, syncOverrideMs = 10_000_000L,
    )

    private class TestNode(val id: Identity, val radio: FakeTransport, val engine: MeshEngine)

    private fun node(name: String): TestNode {
        val id = Identity.generate()
        val radio = FakeTransport(id.nodeId, net)
        val engine = MeshEngine(id, { name }, radio, InMemoryStore(), scope, config)
        engine.start()
        return TestNode(id, radio, engine)
    }

    @After
    fun tearDown() { scope.cancel() }

    private suspend fun until(what: String, timeoutMs: Long = 6000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    @Test
    fun hopLimitIsCappedForOldPhones() = runBlocking {
        val a = FakeTransport("A", net)
        val b = FakeTransport("B", net)
        net.link("A", "B")
        val seen = ArrayList<Int>()
        a.dropHook = { _, data -> seen.add(data[3].toInt() and 0xFF); false }
        var limit = 8
        val clamp = TtlClampTransport(a) { limit }
        fun packet(ttl: Int) = ByteArray(40).also { it[3] = ttl.toByte(); it[39] = 7 }
        assertTrue(clamp.send("B", Channel.MSG, packet(15)))
        assertTrue(clamp.send("B", Channel.MSG, packet(5)))
        limit = 16
        assertTrue(clamp.send("B", Channel.MSG, packet(15)))
        assertEquals(listOf(8, 5, 15), seen)
    }

    @Test
    fun clampKeepsTheRestOfThePacketIntact() = runBlocking {
        val a = FakeTransport("A", net)
        FakeTransport("B", net)
        net.link("A", "B")
        var got: ByteArray? = null
        a.dropHook = { _, data -> got = data; false }
        val src = ByteArray(64) { (it * 3).toByte() }.also { it[3] = 15 }
        TtlClampTransport(a) { 8 }.send("B", Channel.MSG, src)
        val out = got!!
        assertEquals(8, out[3].toInt())
        for (i in src.indices) if (i != 3) assertEquals(src[i], out[i])
        assertEquals(15, src[3].toInt())                // the caller's array is not modified
    }

    @Test
    fun relayIsRecognisedFromItsAdvertisement() = runBlocking {
        val a = node("A")
        val relay = node("Relay")
        val far = node("Far")
        net.link(a.id.nodeId, relay.id.nodeId)
        net.link(relay.id.nodeId, far.id.nodeId)
        a.radio.advert(relay.id.nodeId, Cap.CHAT or Cap.RELAY or Cap.LORA)
        a.radio.advert(far.id.nodeId, Cap.ALL)
        until("relay noticed") { a.engine.isBridge(relay.id.nodeId) }
        assertFalse(a.engine.isBridge(far.id.nodeId))
        until("route to far") { a.engine.isReachable(far.id.nodeId) }
        assertTrue(a.engine.isViaBridge(far.id.nodeId))         // the way to Far starts at the relay
        assertFalse(a.engine.isViaBridge(relay.id.nodeId))      // the relay itself is a direct neighbour
    }

    @Test
    fun capLoraIsNotPartOfPhoneCapabilities() {
        assertEquals(0, Cap.ALL and Cap.LORA)
        assertEquals(8, Cap.LORA)
    }
}
