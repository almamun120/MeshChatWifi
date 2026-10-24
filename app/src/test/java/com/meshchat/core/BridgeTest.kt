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

/** v2.10: LoRa relay recognition and LoRa-path routes (what cannot cross a radio link is refused). */
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
    fun loraFlagSurvivesTheHelloCodec() {
        val hello = Payloads.Hello("R", Cap.CHAT or Cap.RELAY or Cap.LORA, listOf(RouteAd("0A0B0C0D", 3, true), RouteAd("01020304", 2, false), RouteAd("F0F0F0F0", 16, true)))
        val back = Payloads.decodeHello(Payloads.encodeHello(hello))!!
        assertEquals(3, back.routes.size)
        assertEquals(RouteAd("0A0B0C0D", 3, true), back.routes[0])
        assertEquals(RouteAd("01020304", 2, false), back.routes[1])
        assertEquals(RouteAd("F0F0F0F0", 16, true), back.routes[2])
    }

    @Test
    fun routeTableKeepsAndAdvertisesTheFlag() {
        val t = RoutingTable(60_000)
        assertTrue(t.update("aa", "n1", 3, 0, lora = true))
        assertTrue(t.lookup("aa", 1)!!.lora)
        assertTrue(t.advertisementsFor("other", 1, 10).single().lora)
        t.update("bb", "n1", 2, 0)
        assertFalse(t.lookup("bb", 1)!!.lora)
    }

    @Test
    fun relayIsRecognisedFromItsAdvertisement() = runBlocking {
        val a = node("A")
        val relay = node("Relay")
        net.link(a.id.nodeId, relay.id.nodeId)
        var tries = 0
        while (!a.engine.isBridge(relay.id.nodeId) && tries++ < 60) {          // adverts repeat every second on a real phone
            a.radio.advert(relay.id.nodeId, Cap.CHAT or Cap.RELAY or Cap.LORA)
            delay(100)
        }
        assertTrue(a.engine.isBridge(relay.id.nodeId))
        assertFalse(a.engine.isBridge("deadbeef"))
    }

    @Test
    fun nodesBehindARadioLinkCannotGetPhotosCallsOrFiles() = runBlocking {
        val a = node("A")
        val relayId = Identity.generate().nodeId
        val far = Identity.generate()
        val relayRadio = FakeTransport(relayId, net)
        net.link(a.id.nodeId, relayId)
        // the relay tells A: "far" is reachable, over a radio link
        val hello = Payloads.encodeHello(Payloads.Hello("Relay", Cap.CHAT or Cap.RELAY or Cap.LORA, listOf(RouteAd(far.nodeId, 2, true))))
        val pkt = MeshPacket(PacketType.HELLO, 0, 1, 0, ByteArray(8) { 7 }, relayId, a.id.nodeId, System.currentTimeMillis(), hello)
        // a real relay repeats its HELLO every 10 s; here it is repeated until A has processed the new link
        var tries = 0
        while (!a.engine.isLoraPath(far.nodeId) && tries++ < 60) {
            val fresh = MeshPacket(PacketType.HELLO, 0, 1, 0, ByteArray(8) { tries.toByte() }, relayId, a.id.nodeId, System.currentTimeMillis(), hello)
            relayRadio.send(a.id.nodeId, Channel.CONTROL, fresh.encode())
            delay(100)
        }
        assertTrue("route with LoRa flag learned", a.engine.isLoraPath(far.nodeId))
        assertFalse(a.engine.isReachable(far.nodeId))                  // calls and file transfers need a live path
        assertEquals(SendResult.LORA_PATH, a.engine.sendContent(far.nodeId, Content.ofImage(ByteArray(300) { it.toByte() })))
        assertEquals(SendResult.LORA_PATH, a.engine.sendContent(far.nodeId, Content.ofVoice(0, 1000, ByteArray(0), ByteArray(200))))
    }

    @Test
    fun capLoraIsNotPartOfPhoneCapabilities() {
        assertEquals(0, Cap.ALL and Cap.LORA)
        assertEquals(8, Cap.LORA)
    }
}
