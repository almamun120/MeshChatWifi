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
import java.util.concurrent.atomic.AtomicInteger

class SosTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    private class N(val id: Identity, val engine: MeshEngine, val sos: CopyOnWriteArrayList<SosAlert>)

    private fun node(name: String): N {
        val id = Identity.generate()
        val e = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), InMemoryStore(), scope, config)
        val list = CopyOnWriteArrayList<SosAlert>()
        scope.launch { e.events.collect { if (it is MeshEvent.SosReceived) list.add(it.alert) } }
        e.start()
        return N(id, e, list)
    }


    @After fun tearDown() { scope.cancel() }

    private suspend fun until(timeoutMs: Long = 8000, cond: () -> Boolean) { withTimeout(timeoutMs) { while (!cond()) delay(25) } }

    @Test
    fun payloadRoundTripAndGarbageIsRejected() {
        val p = SosPayload(true, 63, SosLocation(23.810331, 90.412521, 18), "রহিম")
        val d = SosCodec.decode(SosCodec.encode(p))!!
        assertTrue(d.active); assertEquals(63, d.battery); assertEquals("রহিম", d.name)
        assertEquals(23.810331, d.location!!.lat, 1e-6); assertEquals(90.412521, d.location!!.lon, 1e-6); assertEquals(18, d.location!!.accuracyM)
        assertNull(SosCodec.decode(SosCodec.encode(SosPayload(false, 255, null, "x")))!!.location)
        assertNull(SosCodec.decode(byteArrayOf(2, 50, 0, 0)))                      // bad state
        assertNull(SosCodec.decode(byteArrayOf(1, 50, 0, 5, 65)))                  // name shorter than declared
        assertNull(SosCodec.decode(ByteArray(0)))
    }

    @Test
    fun sosCrossesTwoHopsWithSignatureAndSafeCancelsIt() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId); net.link(b.id.nodeId, c.id.nodeId)
        delay(1200)                                                              // identities exchanged
        a.engine.sendSos(true, 41, SosLocation(1.0, 2.0, 5))
        until { c.sos.isNotEmpty() && b.sos.isNotEmpty() }
        val got = c.sos.first()
        assertEquals(a.id.nodeId, got.nodeId); assertEquals("A", got.name); assertTrue(got.active)
        assertEquals(41, got.battery); assertNotNull(got.location)
        assertEquals(2, got.hops)
        assertTrue(a.sos.isEmpty())                                             // the sender is not alerted about itself
        a.engine.sendSos(false, 40, null)
        until { c.sos.any { !it.active } }
    }

    @Test
    fun repeatsFromOneSenderAreRateLimitedButSafeAlwaysPasses() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        delay(800)
        repeat(5) { a.engine.sendSos(true, 50, null) }
        until { b.sos.isNotEmpty() }
        delay(400)
        assertEquals(1, b.sos.count { it.active })
        a.engine.sendSos(false, 50, null)
        until { b.sos.any { !it.active } }
    }

    @Test
    fun sessionRepeatsUntilSafeThenSendsSafeRepeatedly() = runBlocking {
        val actives = AtomicInteger(); val safes = AtomicInteger()
        val s = SosSession(scope, intervalMs = 60, jitterMs = 20, safeRepeats = 3, safeGapMs = 20) { if (it) actives.incrementAndGet() else safes.incrementAndGet() }
        s.start(); s.start()                                                    // second start is ignored
        until { actives.get() >= 3 }
        assertTrue(s.isActive)
        s.stopSafe()
        until { safes.get() == 3 }
        val frozen = actives.get()
        delay(250)
        assertEquals(frozen, actives.get())
        assertFalse(s.isActive)
    }
}
