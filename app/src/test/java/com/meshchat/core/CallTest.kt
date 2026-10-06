package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class CallSignalCodecTest {
    private val id = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun everySimpleSignalRoundTripsForEveryKind() {
        for (k in SessionKind.entries) for (t in CallSignalType.entries) {
            if (t == CallSignalType.LINK || (t == CallSignalType.INVITE && k == SessionKind.FILES)) continue
            val d = CallSignalCodec.decode(CallSignalCodec.encode(CallSignal(t, id, k))!!)!!
            assertEquals(t, d.type)
            assertEquals(k, d.kind)
            assertArrayEquals(id, d.callId)
        }
        assertTrue(CallSignal(CallSignalType.INVITE, id, SessionKind.VIDEO).video)
        assertFalse(CallSignal(CallSignalType.INVITE, id, SessionKind.AUDIO).video)
    }

    @Test
    fun linkSignalCarriesTheWifiDirectGroup() {
        val p = LinkParams("DIRECT-ab-Pixel", "s3cretPass", "02:11:22:33:44:55", 48555)
        val d = CallSignalCodec.decode(CallSignalCodec.encode(CallSignal(CallSignalType.LINK, id, SessionKind.FILES, p))!!)!!
        assertEquals(p, d.link)
        assertEquals(SessionKind.FILES, d.kind)
    }

    @Test
    fun fileOfferCarriesCountSizeAndTitle() {
        val o = OfferInfo(3, 12_345_678_901L, "ছুটির ছবি.jpg")
        val d = CallSignalCodec.decode(CallSignalCodec.encode(CallSignal(CallSignalType.INVITE, id, SessionKind.FILES, offer = o))!!)!!
        assertEquals(o, d.offer)
        val long = OfferInfo(1, 5, "x".repeat(200))
        val d2 = CallSignalCodec.decode(CallSignalCodec.encode(CallSignal(CallSignalType.INVITE, id, SessionKind.FILES, offer = long))!!)!!
        assertEquals(40, d2.offer!!.title.length)
    }

    @Test
    fun garbageAndBadSignalsAreRejected() {
        assertNull(CallSignalCodec.decode(ByteArray(0)))
        assertNull(CallSignalCodec.decode(ByteArray(12)))                                             // wrong magic
        assertNull(CallSignalCodec.decode(byteArrayOf(0xC1.toByte(), 99) + id + 0))                    // unknown type
        assertNull(CallSignalCodec.decode(byteArrayOf(0xC1.toByte(), 1) + id + 9))                     // unknown kind
        assertNull(CallSignalCodec.decode(CallSignalCodec.encode(CallSignal(CallSignalType.END, id))!! + 1))   // trailing junk
        assertNull(CallSignalCodec.encode(CallSignal(CallSignalType.LINK, id)))                        // LINK without params
        assertNull(CallSignalCodec.encode(CallSignal(CallSignalType.INVITE, id, SessionKind.FILES)))   // file invite without offer
        assertNull(CallSignalCodec.encode(CallSignal(CallSignalType.INVITE, ByteArray(3))))            // bad session id
        assertNull(CallSignalCodec.encode(CallSignal(CallSignalType.LINK, id, link = LinkParams("DIRECT-x", "short", "", 48555))))
    }

    @Test
    fun sessionKeyIsSharedByBothSidesAndDifferentPerSession() {
        val a = Identity.generate()
        val b = Identity.generate()
        val id2 = ByteArray(8) { 2 }
        val ka = CryptoService.callKey(a, b.publicBytes, b.nodeId, id)
        val kb = CryptoService.callKey(b, a.publicBytes, a.nodeId, id)
        assertArrayEquals(ka, kb)
        assertEquals(32, ka.size)
        assertFalse(ka.contentEquals(CryptoService.callKey(a, b.publicBytes, b.nodeId, id2)))
    }
}

class CallManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cfg = CallConfig(
        ringTimeoutMs = 1500, connectTimeoutMs = 1500, retryMs = 40, poorAfterMs = 200, lostAfterMs = 600,
        endedLingerMs = 150, monitorMs = 30,
    )

    @After
    fun tearDown() = scope.cancel()

    /** [air] counts how many ends started their media: like a real radio, nobody hears anything until BOTH did. */
    class FakeLink(
        private val air: AtomicInteger,
        private val hostOk: Boolean = true,
        private val joinOk: Boolean = true,
        private val mediaOk: Boolean = true,
    ) : CallLink {
        @Volatile var age = 0L
        @Volatile var closed = false
        @Volatile var mutedState = false
        @Volatile var speakerOn = false
        @Volatile var cameraOn: Boolean? = null
        @Volatile var switches = 0
        @Volatile var mediaStarted = false
        @Volatile var video = false
        @Volatile var linkListener: CallLinkListener? = null
        override suspend fun host(): LinkParams? { delay(30); return if (hostOk) LinkParams("DIRECT-t1-A", "password123", "02:00:00:00:00:01", 48555) else null }
        override suspend fun join(p: LinkParams): Boolean { delay(30); return joinOk }
        override suspend fun startMedia(session: MediaSession): Boolean {
            delay(30)
            video = session.video
            air.incrementAndGet()
            if (!mediaOk) return false
            val until = System.currentTimeMillis() + 1200
            while (air.get() < 2 && System.currentTimeMillis() < until) delay(10)
            mediaStarted = air.get() >= 2
            return mediaStarted
        }
        override fun lastPacketAgeMs() = age
        override fun setMuted(muted: Boolean) { mutedState = muted }
        override fun setSpeaker(on: Boolean) { speakerOn = on }
        override fun setCamera(on: Boolean) { cameraOn = on }
        override fun switchCamera() { switches++ }
        override fun setListener(l: CallLinkListener?) { linkListener = l }
        override fun close() { closed = true }
    }

    /** A phone: its CallManager, its fake links and every call it logged. */
    inner class Phone(val id: String, linkMaker: () -> FakeLink, val gate: LinkGate = LinkGate(), var reachable: Boolean = true) {
        val links = ConcurrentLinkedQueue<FakeLink>()
        val records = ConcurrentLinkedQueue<CallRecord>()
        @Volatile var dropNext = 0
        @Volatile var peers: Map<String, Phone> = emptyMap()
        val manager: CallManager = CallManager(
            scope,
            object : CallSignaler {
                override suspend fun send(peerId: String, signal: CallSignal): Boolean {
                    if (!reachable) return false
                    if (dropNext > 0) { dropNext--; return true }          // lost on the air, sender cannot tell
                    val target = peers[peerId] ?: return false
                    scope.launch { target.manager.onSignal(id, signal) }
                    return true
                }
                override suspend fun callKey(peerId: String, callId: ByteArray) = ByteArray(32) { 1 }
                override fun isReachable(peerId: String) = reachable
            },
            gate,
            { linkMaker().also { links.add(it) } },
            { records.add(it) },
            cfg,
        )
        val ui get() = manager.ui.value
    }

    @Volatile private var phones: List<Phone> = emptyList()
    private fun dbg(n: String) = phones.firstOrNull { it.id == n }?.let { p -> "${p.ui.phase}/${p.ui.endReason}${p.records.map { r -> r.outcome }}" }

    private fun pair(a: (AtomicInteger) -> FakeLink = { FakeLink(it) }, b: (AtomicInteger) -> FakeLink = { FakeLink(it) }): Pair<Phone, Phone> {
        val air = AtomicInteger()
        val pa = Phone("A", { a(air) })
        val pb = Phone("B", { b(air) })
        pa.peers = mapOf("B" to pb)
        pb.peers = mapOf("A" to pa)
        phones = listOf(pa, pb)
        return pa to pb
    }

    private suspend fun until(timeoutMs: Long = 4000, what: String = "", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what | A=${dbg("A")} B=${dbg("B")}")
        }
    }

    @Test
    fun answeredCallConnectsBothSidesThenEnds() = runBlocking {
        val (a, b) = pair()
        assertEquals(StartResult.OK, a.manager.startCall("B"))
        assertTrue(a.ui.phase == CallPhase.CALLING || a.ui.phase == CallPhase.RINGING)
        until(what = "B rings") { b.ui.phase == CallPhase.INCOMING }
        until(what = "A shows ringing") { a.ui.phase == CallPhase.RINGING }
        assertFalse(b.ui.outgoing)

        b.manager.accept()
        until(what = "both active") { a.ui.phase == CallPhase.ACTIVE && b.ui.phase == CallPhase.ACTIVE }
        assertTrue(a.ui.connectedAtMs > 0 && b.ui.connectedAtMs > 0)
        assertTrue(a.links.single().mediaStarted && b.links.single().mediaStarted)

        delay(120)
        a.manager.hangUp()
        until(what = "both ended") { a.ui.phase == CallPhase.ENDED && b.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.LOCAL_HANGUP, a.ui.endReason)
        assertEquals(CallEnd.REMOTE_HANGUP, b.ui.endReason)
        until(what = "links closed") { a.links.single().closed && b.links.single().closed }
        until(what = "back to idle") { a.ui.phase == CallPhase.IDLE && b.ui.phase == CallPhase.IDLE }
        until(what = "both calls recorded") { a.records.isNotEmpty() && b.records.isNotEmpty() }
        val ra = a.records.single(); val rb = b.records.single()
        assertTrue(ra.outgoing && !rb.outgoing)
        assertTrue("duration recorded", ra.durationMs >= 100 && rb.durationMs >= 50)
        assertTrue(a.gate.isFree() && b.gate.isFree())
    }

    @Test
    fun declinedCall() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        b.manager.reject()
        until(what = "A sees declined") { a.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.DECLINED, a.ui.endReason)
        assertEquals(CallEnd.DECLINED, b.ui.endReason)
        assertTrue(a.links.single().closed)
    }

    @Test
    fun callerCancelsBeforeAnswerAndCalleeSeesMissedCall() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        a.manager.hangUp()
        until(what = "B missed") { b.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.CANCELLED, a.ui.endReason)
        assertEquals(CallEnd.MISSED, b.ui.endReason)
        until(what = "missed call recorded") { b.records.isNotEmpty() }
        assertEquals(CallEnd.MISSED, b.records.single().outcome)
    }

    @Test
    fun secondCallerGetsBusy() = runBlocking {
        val air = AtomicInteger()
        val a = Phone("A", { FakeLink(air) }); val b = Phone("B", { FakeLink(air) }); val c = Phone("C", { FakeLink(air) })
        a.peers = mapOf("B" to b); b.peers = mapOf("A" to a, "C" to c); c.peers = mapOf("B" to b)
        phones = listOf(a, b, c)
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        b.manager.accept()
        until { b.ui.phase == CallPhase.ACTIVE }

        assertEquals(StartResult.BUSY, b.manager.startCall("C"))           // cannot start while in a call
        assertEquals(StartResult.OK, c.manager.startCall("B"))
        until(what = "C told busy") { c.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.BUSY, c.ui.endReason)
        assertEquals("the running call is untouched", CallPhase.ACTIVE, a.ui.phase)
    }

    @Test
    fun nobodyAnswersTheCallerGivesUpAndTheCalleeStopsRinging() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until(what = "B ringing") { b.ui.phase == CallPhase.INCOMING }
        until(timeoutMs = 4000, what = "caller gives up") { a.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.NO_ANSWER, a.ui.endReason)
        until(what = "callee stops ringing") { b.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.MISSED, b.ui.endReason)
    }

    @Test
    fun peerOutOfBleRangeFailsImmediately() = runBlocking {
        val (a, _) = pair()
        a.reachable = false
        assertEquals(StartResult.UNREACHABLE, a.manager.startCall("B"))
        assertEquals(CallPhase.IDLE, a.ui.phase)
        assertTrue(a.records.isEmpty() && a.gate.isFree())
    }

    @Test
    fun lostSignalsAreRetriedUntilTheCallConnects() = runBlocking {
        val (a, b) = pair()
        a.dropNext = 3      // first invites/links vanish
        a.manager.startCall("B")
        until(what = "B rings despite loss") { b.ui.phase == CallPhase.INCOMING }
        b.dropNext = 2      // first RINGING/ACCEPT vanish too
        b.manager.accept()
        until(what = "both active despite loss") { a.ui.phase == CallPhase.ACTIVE && b.ui.phase == CallPhase.ACTIVE }
    }

    @Test
    fun wifiGroupCannotBeCreatedCallFailsCleanly() = runBlocking {
        val (a, b) = pair(a = { FakeLink(it, hostOk = false) })
        a.manager.startCall("B")
        until { a.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.FAILED, a.ui.endReason)
        until(what = "B's ring is cancelled") { b.ui.phase == CallPhase.ENDED }
    }

    @Test
    fun calleeCannotJoinWifiGroupBothSidesFail() = runBlocking {
        val (a, b) = pair(a = { FakeLink(it, mediaOk = false) }, b = { FakeLink(it, joinOk = false) })
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        b.manager.accept()
        until(what = "both ended") { a.ui.phase == CallPhase.ENDED && b.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.FAILED, b.ui.endReason)
        assertEquals(CallEnd.FAILED, a.ui.endReason)
    }

    @Test
    fun silenceMarksPoorConnectionThenDropsTheCall() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        b.manager.accept()
        until { a.ui.phase == CallPhase.ACTIVE }

        a.links.single().age = 300
        until(what = "poor flag") { a.ui.poorConnection }
        a.links.single().age = 0
        until(what = "poor flag clears") { !a.ui.poorConnection }
        a.links.single().age = 5_000
        until(what = "call dropped") { a.ui.phase == CallPhase.ENDED }
        assertEquals(CallEnd.LOST, a.ui.endReason)
        until(what = "peer told") { b.ui.phase == CallPhase.ENDED }
    }

    @Test
    fun muteAndSpeakerReachTheLinkAndSurviveInUi() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        a.manager.setSpeaker(true)                       // chosen while still ringing
        b.manager.accept()
        until { a.ui.phase == CallPhase.ACTIVE }
        assertTrue("choice made during ringing is applied once media starts", a.links.single().speakerOn)
        a.manager.setMuted(true)
        assertTrue(a.links.single().mutedState && a.ui.muted)
        a.manager.setMuted(false)
        assertFalse(a.links.single().mutedState)
    }

    @Test
    fun videoCallDefaultsAndCameraButtons() = runBlocking {
        val (a, b) = pair()
        assertEquals(StartResult.OK, a.manager.startCall("B", video = true))
        assertTrue(a.ui.video && a.ui.cameraOn && a.ui.speaker)           // video calls start with camera + loudspeaker on
        until { b.ui.phase == CallPhase.INCOMING }
        assertTrue("the callee learns it is a VIDEO call from the invite", b.ui.video)
        b.manager.accept()
        until(what = "both active") { a.ui.phase == CallPhase.ACTIVE && b.ui.phase == CallPhase.ACTIVE }
        assertTrue(a.links.single().video && b.links.single().video)
        assertEquals("camera state is pushed to the link when media starts", true, a.links.single().cameraOn)

        a.manager.setCamera(false)
        assertFalse(a.ui.cameraOn)
        assertEquals(false, a.links.single().cameraOn)
        a.manager.switchCamera(); a.manager.switchCamera()
        assertEquals(2, a.links.single().switches)

        assertTrue(b.ui.remoteCameraOn)
        a.links.single().linkListener!!.onRemoteCamera(false)       // what WebRTC's control channel would report
        until(what = "remote camera flag") { !a.ui.remoteCameraOn }
        a.links.single().linkListener!!.onRemoteCamera(true)
        until(what = "remote camera back") { a.ui.remoteCameraOn }
    }

    @Test
    fun cameraButtonsDoNothingInAnAudioCall() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        assertFalse(b.ui.video)
        b.manager.accept()
        until { a.ui.phase == CallPhase.ACTIVE }
        a.manager.setCamera(true)
        a.manager.switchCamera()
        assertNull(a.links.single().cameraOn)
        assertEquals(0, a.links.single().switches)
        assertFalse(a.ui.cameraOn)
    }

    @Test
    fun aNewCallCanStartRightAfterOneEnds() = runBlocking {
        val (a, b) = pair()
        a.manager.startCall("B")
        until { b.ui.phase == CallPhase.INCOMING }
        b.manager.reject()
        until { a.ui.phase == CallPhase.ENDED }
        until(what = "gate released") { a.gate.isFree() && b.gate.isFree() }
        assertEquals(StartResult.OK, a.manager.startCall("B"))     // do not wait for the "call ended" linger
        until(what = "second call rings") { b.ui.phase == CallPhase.INCOMING && b.ui.callIdHex == a.ui.callIdHex }
    }
}

/** Call signalling through the REAL engines: encryption, routing, relay, no queuing. */
class CallEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    private class TestNode(val id: Identity, val store: InMemoryStore, val engine: MeshEngine)

    private fun node(name: String): TestNode {
        val id = Identity.generate()
        val store = InMemoryStore()
        val engine = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), store, scope, config)
        engine.start()
        return TestNode(id, store, engine)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun until(timeoutMs: Long = 8000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(25) }
    }

    @Test
    fun signalCrossesTwoRelaysEncryptedAndIsNeverStored() = runBlocking {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.id.nodeId, b.id.nodeId)
        net.link(b.id.nodeId, c.id.nodeId)
        until { a.engine.routes.value.any { it.dest == c.id.nodeId && it.hops == 2 } && a.store.nodes[c.id.nodeId]?.publicKey != null }
        assertTrue(a.engine.isReachable(c.id.nodeId))

        val received = scope.async { c.engine.events.first { it is MeshEvent.CallSignalReceived } as MeshEvent.CallSignalReceived }
        delay(100)
        val callId = AudioPacketIds.newId()
        val link = LinkParams("DIRECT-zz-A", "passw0rd!!", "02:aa:bb:cc:dd:ee", 48555)
        assertTrue(a.engine.sendCallSignal(c.id.nodeId, CallSignal(CallSignalType.LINK, callId, SessionKind.VIDEO, link)))

        val ev = withTimeout(5000) { received.await() }
        assertEquals(a.id.nodeId, ev.peerId)
        assertEquals(CallSignalType.LINK, ev.signal.type)
        assertEquals(SessionKind.VIDEO, ev.signal.kind)
        assertEquals(link, ev.signal.link)
        assertArrayEquals(callId, ev.signal.callId)

        assertTrue("signals are real-time: nothing may be parked for later", a.store.pending.isEmpty() && b.store.pending.isEmpty())
        assertTrue(b.store.messages.isEmpty() && c.store.messages.isEmpty())      // no chat entry from signalling
        assertArrayEquals(a.engine.callKey(c.id.nodeId, callId), c.engine.callKey(a.id.nodeId, callId))
    }

    @Test
    fun noRouteMeansFalseAndNothingIsQueued() = runBlocking {
        val a = node("A"); val c = node("C")
        a.store.upsertNodeIdentity(c.id.nodeId, "C", c.id.publicBytes, 0)
        assertFalse(a.engine.isReachable(c.id.nodeId))
        assertFalse(a.engine.sendCallSignal(c.id.nodeId, CallSignal(CallSignalType.INVITE, AudioPacketIds.newId())))
        delay(300)
        assertTrue(a.store.pending.isEmpty())
    }

    @Test
    fun signalFromBlockedPeerIsDropped() = runBlocking {
        val a = node("A"); val b = node("B")
        net.link(a.id.nodeId, b.id.nodeId)
        until { a.store.nodes[b.id.nodeId]?.publicKey != null && b.store.nodes[a.id.nodeId]?.publicKey != null }
        b.engine.setBlocked(a.id.nodeId, true)
        val got = AtomicInteger()
        val job = scope.launch { b.engine.events.collect { if (it is MeshEvent.CallSignalReceived) got.incrementAndGet() } }
        delay(100)
        a.engine.sendCallSignal(b.id.nodeId, CallSignal(CallSignalType.INVITE, AudioPacketIds.newId()))
        delay(500)
        assertEquals(0, got.get())
        job.cancel()
    }
}
