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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------- helpers

/** Deterministic bytes so receivers can be verified without keeping the data. */
private fun pattern(seed: Int, size: Int): ByteArray = Random(seed).nextBytes(size)

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

class MemSource(override val meta: FileMeta, private val data: ByteArray) : FileSource {
    override fun open(): InputStream = ByteArrayInputStream(data)
}

/** Generates [meta].size bytes slowly (cancel / stall tests). */
class SlowSource(override val meta: FileMeta, private val chunkDelayMs: Long) : FileSource {
    override fun open(): InputStream = object : InputStream() {
        private var left = meta.size
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            Thread.sleep(chunkDelayMs)
            val n = minOf(len.toLong(), left, 8192L).toInt()
            java.util.Arrays.fill(b, off, off + n, 7)
            left -= n
            return n
        }
    }
}

class MemSink : FileSink {
    val committed = ConcurrentHashMap<String, ByteArray>()
    val aborted = CopyOnWriteArrayList<String>()
    val created = CopyOnWriteArrayList<String>()

    override fun create(meta: FileMeta): SinkFile {
        created += meta.name
        val bos = ByteArrayOutputStream()
        return object : SinkFile {
            override val out: OutputStream = bos
            override fun commit(): String { committed[meta.name] = bos.toByteArray(); return "mem://${meta.name}" }
            override fun abort() { aborted += meta.name }
        }
    }
}

private fun meta(i: Int, name: String, size: Int) = FileMeta(i, name, size.toLong(), "application/octet-stream")

/** A connected host/guest pair of secure streams over a real localhost TCP connection. */
private fun streamPair(guestKey: ByteArray? = null): Pair<SecureStream, SecureStream> {
    val key = ByteArray(32) { 5 }
    val id = ByteArray(8) { 3 }
    val server: ServerSocket = TcpSessions.listen(0)
    var host: SecureStream? = null
    val t = thread { host = TcpSessions.accept(server, StreamSession(id, key, true), 5000) }
    val guest = TcpSessions.connect(InetAddress.getLoopbackAddress(), server.localPort, StreamSession(id, guestKey ?: key, false), 5000)!!
    t.join()
    server.close()
    return host!! to guest
}

// ---------------------------------------------------------------------------------------------- secure stream

class SecureStreamTest {
    private val key = ByteArray(32) { it.toByte() }
    private val id = ByteArray(8) { 9 }

    private fun capture(isHost: Boolean, vararg msgs: Pair<Int, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        val s = SecureStream(ByteArrayInputStream(ByteArray(0)), bos, key, id, isHost)
        for ((t, p) in msgs) s.send(t, p)
        return bos.toByteArray()
    }

    private fun reader(bytes: ByteArray, isHost: Boolean, k: ByteArray = key) =
        SecureStream(ByteArrayInputStream(bytes), ByteArrayOutputStream(), k, id, isHost)

    @Test
    fun roundTripAndCleanEof() {
        val wire = capture(true, 1 to byteArrayOf(1, 2, 3), 2 to ByteArray(0), 3 to ByteArray(70_000) { 4 })
        val r = reader(wire, isHost = false)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.receive()!!.payload)
        assertEquals(2, r.receive()!!.type)
        assertEquals(70_000, r.receive()!!.payload.size)
        assertEquals(null, r.receive())
    }

    @Test
    fun tamperedReplayedReflectedOrWrongKeyFramesAreRejected() {
        val wire = capture(true, 1 to byteArrayOf(1, 2, 3), 1 to byteArrayOf(4, 5, 6))
        fun mustFail(r: SecureStream) { try { r.receive(); fail("accepted a bad frame") } catch (e: SecureStreamException) { } }

        mustFail(reader(wire.copyOf().also { it[10] = (it[10] + 1).toByte() }, false))          // modified
        mustFail(reader(wire, false, ByteArray(32) { 1 }))                                         // wrong key
        mustFail(reader(wire, true))                                                               // reflected back to its own side
        val oneFrame = capture(true, 1 to byteArrayOf(1, 2, 3))
        val replay = reader(oneFrame + oneFrame, false)
        replay.receive()
        mustFail(replay)                                                                           // same frame twice
        val first = capture(true, 1 to byteArrayOf(1)); val second = capture(true, 1 to byteArrayOf(1), 1 to byteArrayOf(2)).copyOfRange(first.size, first.size + first.size)
        mustFail(reader(second, false))                                                            // dropped first frame
        mustFail(reader(byteArrayOf(0, 0, 0, 3, 1, 2, 3), false))                                  // absurd length
    }
}

class FileNamesTest {
    @Test
    fun namesFromOtherPhonesNeverEscapeTheFolder() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FileNames.sanitize("C:\\Windows\\evil.exe"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("file", FileNames.sanitize(""))
        assertEquals("file", FileNames.sanitize("   ..  "))
        assertEquals("abc.txt", FileNames.sanitize("a\u0000b\nc.txt"))
        assertEquals("ছুটি.jpg", FileNames.sanitize("ছুটি.jpg"))
        assertEquals("ab.txt", FileNames.sanitize("a<b>.txt"))
        val long = FileNames.sanitize("x".repeat(500) + ".mp4")
        assertTrue(long.length <= 120 && long.endsWith(".mp4"))
        assertFalse(FileNames.sanitize("a|b?c*.txt").any { it in "|?*" })
    }
}

// ---------------------------------------------------------------------------------------------- protocol

class TransferProtocolTest {

    private fun run(
        files: List<FileSource>, sink: MemSink, expected: OfferInfo? = null,
        guestKey: ByteArray? = null,
    ): Pair<TransferResult, TransferResult> {
        val (host, guest) = streamPair(guestKey)
        var recv: TransferResult? = null
        val t = thread { recv = FileReceiver(sink, expected).run(guest); guest.close() }
        val sent = FileSender(files).run(host)
        t.join(20_000)
        host.close()
        return sent to recv!!
    }

    @Test
    fun manyFilesIncludingEmptyAndLargeArriveIntact() {
        val a = pattern(1, 3_000_000); val b = pattern(2, 17); val c = ByteArray(0)
        val files = listOf(MemSource(meta(0, "big.bin", a.size), a), MemSource(meta(1, "tiny.txt", b.size), b), MemSource(meta(2, "empty.dat", 0), c))
        val sink = MemSink()
        val progress = CopyOnWriteArrayList<Long>()
        val (host, guest) = streamPair()
        var recv: TransferResult? = null
        val t = thread {
            recv = FileReceiver(sink, OfferInfo(3, a.size + b.size + 0L, "big.bin"), object : TransferListener {
                override fun onProgress(fileIndex: Int, totalDone: Long) { progress += totalDone }
            }).run(guest)
        }
        val sent = FileSender(files).run(host)
        t.join(20_000)
        assertEquals(TransferStatus.OK, sent.status)
        assertEquals(TransferStatus.OK, recv!!.status)
        assertArrayEquals(a, sink.committed["big.bin"]); assertArrayEquals(b, sink.committed["tiny.txt"])
        assertArrayEquals(c, sink.committed["empty.dat"])
        assertTrue(sink.aborted.isEmpty())
        assertEquals(a.size + b.size.toLong(), progress.last())
        assertEquals("progress never goes backwards", progress.sorted(), progress.toList())
    }

    @Test
    fun hostileFileNamesAreSanitizedBeforeTheSinkSeesThem() {
        val d = pattern(3, 100)
        val sink = MemSink()
        val (s, r) = run(listOf(MemSource(meta(0, "../../evil.txt", 100), d)), sink)
        assertEquals(TransferStatus.OK, s.status)
        assertEquals(listOf("evil.txt"), sink.created.toList())
    }

    @Test
    fun manifestBiggerThanTheAcceptedOfferIsRefused() {
        val files = listOf(MemSource(meta(0, "a", 100), pattern(1, 100)), MemSource(meta(1, "b", 100), pattern(2, 100)))
        val sink = MemSink()
        val (s, r) = run(files, sink, expected = OfferInfo(1, 100, "a"))
        assertEquals(TransferStatus.ABORTED, s.status)
        assertEquals(TransferStatus.FAILED, r.status)
        assertTrue(sink.created.isEmpty())
    }

    @Test
    fun corruptedFileIsRejectedAndNeverPublished() {
        val (host, guest) = streamPair()
        val sink = MemSink()
        var recv: TransferResult? = null
        val t = thread { recv = FileReceiver(sink, null).run(guest) }
        val data = pattern(4, 5000)
        host.send(TransferWire.T_MANIFEST, TransferWire.encodeManifest(listOf(meta(0, "x.bin", 5000))))
        assertEquals(TransferWire.T_READY, host.receive()!!.type)
        host.send(TransferWire.T_FILE_START, byteArrayOf(0, 0))
        val bad = data.copyOf().also { it[100] = (it[100] + 1).toByte() }
        host.send(TransferWire.T_DATA, bad)
        host.send(TransferWire.T_FILE_END, byteArrayOf(0, 0) + sha(data))            // hash of the ORIGINAL data
        val done = host.receive()!!
        t.join(10_000)
        assertEquals(TransferWire.T_DONE, done.type)
        assertEquals(0, done.payload[0].toInt())
        assertEquals(TransferStatus.FAILED, recv!!.status)
        assertTrue(sink.committed.isEmpty())
        assertEquals(listOf("x.bin"), sink.aborted.toList())
    }

    @Test
    fun cuttingTheLinkMidTransferAbortsAndDeletesThePartialFile() {
        val src = SlowSource(meta(0, "huge.bin", 50_000_000), 2)
        val sink = MemSink()
        val (host, guest) = streamPair()
        var recv: TransferResult? = null
        val t = thread { recv = FileReceiver(sink, null).run(guest) }
        val sender = thread { FileSender(listOf(src)).run(host) }
        Thread.sleep(300)
        host.close()
        sender.join(10_000); t.join(10_000)
        assertEquals(TransferStatus.ABORTED, recv!!.status)
        assertTrue(sink.committed.isEmpty())
        assertEquals(listOf("huge.bin"), sink.aborted.toList())
    }

    @Test
    fun wrongSessionKeyTransfersNothing() {
        val d = pattern(5, 100)
        val sink = MemSink()
        val (s, r) = run(listOf(MemSource(meta(0, "a", 100), d)), sink, guestKey = ByteArray(32) { 99 })
        assertEquals(TransferStatus.ABORTED, r.status)
        assertTrue(sink.created.isEmpty() && sink.committed.isEmpty())
        assertNotNull(s)
    }

    @Test
    fun encryptedThroughputOnLocalhost() {
        val size = 64 * 1024 * 1024
        val src = object : FileSource {
            override val meta = meta(0, "speed.bin", size)
            override fun open(): InputStream = object : InputStream() {
                var left = size
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (left <= 0) return -1; val n = minOf(len, left); left -= n; return n }
            }
        }
        val counting = object : FileSink {
            var bytes = 0L
            override fun create(meta: FileMeta) = object : SinkFile {
                override val out = object : OutputStream() {
                    override fun write(b: Int) { bytes++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
                }
                override fun commit(): String? = "ok"
                override fun abort() {}
            }
        }
        val (host, guest) = streamPair()
        var recv: TransferResult? = null
        val t = thread { recv = FileReceiver(counting, null).run(guest) }
        val t0 = System.nanoTime()
        val sent = FileSender(listOf(src)).run(host)
        t.join(60_000)
        val secs = (System.nanoTime() - t0) / 1e9
        println("TRANSFER SPEED: %.0f MB/s (encrypted + SHA-256, localhost, this sandbox)".format(size / 1e6 / secs))
        assertEquals(TransferStatus.OK, sent.status)
        assertEquals(size.toLong(), counting.bytes)
        assertTrue("took ${secs}s", secs < 40)
    }
}

// ---------------------------------------------------------------------------------------------- manager

class TransferManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cfg = TransferConfig(
        offerTimeoutMs = 1500, connectTimeoutMs = 3000, retryMs = 40, stallMs = 700, endedLingerMs = 150, tickMs = 30, abortGraceMs = 500,
    )

    @After
    fun tearDown() = scope.cancel()

    /** Wi-Fi Direct replaced by a localhost TCP socket. */
    class LoopLink(private val hostOk: Boolean = true, private val joinOk: Boolean = true) : TransferLink {
        private var server: ServerSocket? = null
        private var port = 0
        override suspend fun host(): LinkParams? {
            delay(20)
            if (!hostOk) return null
            val s = TcpSessions.listen(0); server = s
            return LinkParams("DIRECT-t2-A", "password123", "", s.localPort)
        }
        override suspend fun join(p: LinkParams): Boolean { delay(20); port = p.port; return joinOk }
        override suspend fun openStream(session: StreamSession): SecureStream? =
            if (session.isHost) TcpSessions.accept(server!!, session, 5000)
            else TcpSessions.connect(InetAddress.getLoopbackAddress(), port, session, 5000)
        override fun close() { runCatching { server?.close() } }
    }

    inner class Phone(val id: String, val gate: LinkGate = LinkGate(), linkMaker: () -> LoopLink = { LoopLink() }) {
        val sink = MemSink()
        val records = ConcurrentLinkedQueue<TransferRecord>()
        @Volatile var reachable = true
        @Volatile var dropNext = 0
        @Volatile var peers: Map<String, Phone> = emptyMap()
        @Volatile var callManager: CallManager? = null
        val signaler: CallSignaler = object : CallSignaler {
            override suspend fun send(peerId: String, signal: CallSignal): Boolean {
                if (!reachable) return false
                if (dropNext > 0) { dropNext--; return true }
                val target = peers[peerId] ?: return false
                scope.launch {
                    target.manager.onSignal(id, signal)
                    target.callManager?.onSignal(id, signal)
                }
                return true
            }
            override suspend fun callKey(peerId: String, callId: ByteArray) = ByteArray(32) { 1 }
            override fun isReachable(peerId: String) = reachable
        }
        val manager: TransferManager = TransferManager(scope, signaler, gate, { linkMaker() }, { sink }, { records.add(it) }, cfg)
        val ui get() = manager.ui.value
    }

    private fun pair(): Pair<Phone, Phone> {
        val a = Phone("A"); val b = Phone("B")
        a.peers = mapOf("B" to b); b.peers = mapOf("A" to a)
        return a to b
    }

    private suspend fun until(timeoutMs: Long = 6000, what: String = "", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private fun files(vararg sizes: Int) = sizes.mapIndexed { i, n -> MemSource(meta(i, "f$i.bin", n), pattern(i + 10, n)) }

    @Test
    fun offerAcceptTransferComplete() = runBlocking {
        val (a, b) = pair()
        val src = files(2_000_000, 1234, 0)
        assertEquals(StartResult.OK, a.manager.send("B", src))
        until(what = "B is asked") { b.ui.phase == TransferPhase.ASKING }
        assertEquals(3, b.ui.offer!!.count)
        assertEquals(2_001_234L, b.ui.offer!!.totalBytes)
        assertEquals("f0.bin", b.ui.offer!!.title)
        assertFalse(b.ui.outgoing)

        b.manager.accept()
        until(what = "both finished") { a.ui.phase == TransferPhase.ENDED && b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.COMPLETED, a.ui.endReason)
        assertEquals(TransferEnd.COMPLETED, b.ui.endReason)
        for (i in 0..2) assertArrayEquals(pattern(i + 10, listOf(2_000_000, 1234, 0)[i]), b.sink.committed["f$i.bin"])
        until(what = "records") { a.records.isNotEmpty() && b.records.isNotEmpty() }
        assertEquals(3, a.records.single().okCount)
        assertEquals(3, b.records.single().locations.size)
        assertTrue(b.records.single().doneBytes == 2_001_234L && !b.records.single().outgoing)
        until(what = "gates free") { a.gate.isFree() && b.gate.isFree() }
        until(what = "idle") { a.ui.phase == TransferPhase.IDLE && b.ui.phase == TransferPhase.IDLE }
    }

    @Test
    fun speedAndEtaAreReportedWhileTransferring() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", listOf(SlowSource(meta(0, "slow.bin", 600_000), 5)))
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.accept()
        until(what = "speed shown") { a.ui.phase == TransferPhase.TRANSFERRING && a.ui.speedBps > 0 && a.ui.etaSec >= 0 && a.ui.doneBytes > 0 }
        assertTrue(a.ui.fraction in 0f..1f && a.ui.files[0].state == FileRow.ACTIVE)
        until(what = "done") { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.COMPLETED, a.ui.endReason)
        assertEquals(1f, a.ui.fraction, 0f)
    }

    @Test
    fun declinedOffer() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", files(100))
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.decline()
        until(what = "A sees declined") { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.DECLINED, a.ui.endReason)
        assertEquals(TransferEnd.DECLINED, b.ui.endReason)
        assertTrue(b.sink.created.isEmpty())
    }

    @Test
    fun senderWithdrawsTheOfferReceiverSeesMissed() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", files(100))
        until { b.ui.phase == TransferPhase.ASKING }
        a.manager.cancel()
        until(what = "B missed") { b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.CANCELLED_BY_ME, a.ui.endReason)
        assertEquals(TransferEnd.MISSED, b.ui.endReason)
    }

    @Test
    fun nobodyAnswersTheOffer() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", files(100))
        until(what = "B asked") { b.ui.phase == TransferPhase.ASKING }
        until(what = "sender gives up") { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.NO_ANSWER, a.ui.endReason)
        until(what = "receiver prompt disappears") { b.ui.phase == TransferPhase.ENDED }
    }

    @Test
    fun receiverCancelsMidwaySenderKnowsWhy() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", listOf(SlowSource(meta(0, "huge.bin", 80_000_000), 2)))
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.accept()
        until(what = "transferring") { b.ui.phase == TransferPhase.TRANSFERRING && b.ui.doneBytes > 0 }
        b.manager.cancel()
        until(what = "both ended") { a.ui.phase == TransferPhase.ENDED && b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.CANCELLED_BY_ME, b.ui.endReason)
        assertEquals(TransferEnd.CANCELLED_BY_PEER, a.ui.endReason)
        assertTrue(b.sink.committed.isEmpty())
        until(what = "gates free") { a.gate.isFree() && b.gate.isFree() }
    }

    @Test
    fun senderCancelsMidway() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", listOf(SlowSource(meta(0, "huge.bin", 80_000_000), 2)))
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.accept()
        until { a.ui.phase == TransferPhase.TRANSFERRING && a.ui.doneBytes > 0 }
        a.manager.cancel()
        until(what = "both ended") { a.ui.phase == TransferPhase.ENDED && b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.CANCELLED_BY_PEER, b.ui.endReason)
        assertTrue(b.sink.committed.isEmpty())
    }

    @Test
    fun aStalledLinkIsGivenUpOn() = runBlocking {
        val (a, b) = pair()
        a.manager.send("B", listOf(SlowSource(meta(0, "stuck.bin", 1_000_000), 3_000)))   // one chunk every 3 s
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.accept()
        until(what = "stall detected", timeoutMs = 8000) { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.LOST, a.ui.endReason)
        until(what = "receiver ends") { b.ui.phase == TransferPhase.ENDED }
    }

    @Test
    fun wifiGroupCannotBeCreated() = runBlocking {
        val a = Phone("A", linkMaker = { LoopLink(hostOk = false) }); val b = Phone("B")
        a.peers = mapOf("B" to b); b.peers = mapOf("A" to a)
        a.manager.send("B", files(100))
        until { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.FAILED, a.ui.endReason)
        until(what = "offer withdrawn") { b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.MISSED, b.ui.endReason)
    }

    @Test
    fun receiverCannotJoinWifiGroup() = runBlocking {
        val a = Phone("A"); val b = Phone("B", linkMaker = { LoopLink(joinOk = false) })
        a.peers = mapOf("B" to b); b.peers = mapOf("A" to a)
        a.manager.send("B", files(100))
        until { b.ui.phase == TransferPhase.ASKING }
        b.manager.accept()
        until(what = "both ended") { a.ui.phase == TransferPhase.ENDED && b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.FAILED, b.ui.endReason)
    }

    @Test
    fun lostSignalsAreRetried() = runBlocking {
        val (a, b) = pair()
        a.dropNext = 3
        a.manager.send("B", files(5000))
        until(what = "B asked despite loss") { b.ui.phase == TransferPhase.ASKING }
        b.dropNext = 2
        b.manager.accept()
        until(what = "complete") { a.ui.phase == TransferPhase.ENDED && b.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.COMPLETED, a.ui.endReason)
    }

    @Test
    fun unreachableEmptyAndBusyAreRefusedUpFront() = runBlocking {
        val (a, b) = pair()
        assertEquals(StartResult.EMPTY, a.manager.send("B", emptyList()))
        a.reachable = false
        assertEquals(StartResult.UNREACHABLE, a.manager.send("B", files(10)))
        a.reachable = true
        assertEquals(StartResult.OK, a.manager.send("B", files(10)))
        assertEquals(StartResult.BUSY, a.manager.send("B", files(10)))
        assertTrue(a.records.isEmpty())
    }

    @Test
    fun aSecondSenderGetsBusyWhileOneTransferIsRunning() = runBlocking {
        val a = Phone("A"); val b = Phone("B"); val c = Phone("C")
        a.peers = mapOf("B" to b); c.peers = mapOf("B" to b); b.peers = mapOf("A" to a, "C" to c)
        a.manager.send("B", files(100))
        until { b.ui.phase == TransferPhase.ASKING }
        c.manager.send("B", files(100))
        until(what = "C told busy") { c.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.BUSY, c.ui.endReason)
        assertEquals(TransferPhase.ASKING, b.ui.phase)
    }

    @Test
    fun aCallAndATransferNeverShareTheWifiRadio() = runBlocking {
        // B has one gate shared by a call manager and a transfer manager.
        val gate = LinkGate()
        val a = Phone("A"); val b = Phone("B", gate)
        a.peers = mapOf("B" to b); b.peers = mapOf("A" to a)
        val callMgr = CallManager(scope, b.signaler, gate, { error("no media in this test") }, { }, CallConfig(retryMs = 40, ringTimeoutMs = 3000))
        b.callManager = callMgr

        // 1) an incoming voice call rings -> a file offer is answered BUSY
        callMgr.onSignal("A", CallSignal(CallSignalType.INVITE, AudioPacketIds.newId(), SessionKind.AUDIO))
        until { callMgr.ui.value.phase == CallPhase.INCOMING }
        a.manager.send("B", files(100))
        until(what = "offer refused") { a.ui.phase == TransferPhase.ENDED }
        assertEquals(TransferEnd.BUSY, a.ui.endReason)
        callMgr.reject()
        until(what = "gate free again") { gate.isFree() }

        // 2) an offer waiting for an answer -> a call invite is answered BUSY
        a.manager.send("B", files(100))
        until { b.ui.phase == TransferPhase.ASKING }
        assertEquals(StartResult.BUSY, callMgr.startCall("A"))
        b.manager.decline()
        until(what = "released") { gate.isFree() }
    }
}
