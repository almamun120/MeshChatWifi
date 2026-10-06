package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class TransferPhase { IDLE, OFFERING, ASKING, CONNECTING, TRANSFERRING, ENDED }

enum class TransferEnd {
    NONE, COMPLETED, PARTIAL, CANCELLED_BY_ME, CANCELLED_BY_PEER, DECLINED, MISSED, NO_ANSWER, BUSY, UNREACHABLE, FAILED, LOST;

    fun label(): String = when (this) {
        NONE -> ""
        COMPLETED -> "Transfer complete"
        PARTIAL -> "Some files failed"
        CANCELLED_BY_ME -> "Transfer cancelled"
        CANCELLED_BY_PEER -> "The other phone cancelled"
        DECLINED -> "Declined"
        MISSED -> "Missed offer"
        NO_ANSWER -> "No answer"
        BUSY -> "The other phone is busy"
        UNREACHABLE -> "Not in range"
        FAILED -> "Could not connect. Move closer to each other"
        LOST -> "Connection lost"
    }
}

/** Per-file row on the progress screen. */
data class FileRow(val name: String, val size: Long, val state: Int) {
    companion object {
        const val WAITING = 0
        const val ACTIVE = 1
        const val DONE = 2
        const val FAILED = 3
    }
}

data class TransferUi(
    val phase: TransferPhase = TransferPhase.IDLE,
    val peerId: String = "",
    val idHex: String = "",
    val outgoing: Boolean = false,
    val offer: OfferInfo? = null,
    val files: List<FileRow> = emptyList(),
    val fileIndex: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val endReason: TransferEnd = TransferEnd.NONE,
) {
    val active: Boolean get() = phase != TransferPhase.IDLE && phase != TransferPhase.ENDED
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

class TransferRecord(
    val peerId: String,
    val idHex: String,
    val outgoing: Boolean,
    val title: String,
    val fileCount: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val okCount: Int,
    val outcome: TransferEnd,
    val startedAt: Long,
    val durationMs: Long,
    /** Receiver only: where each saved file ended up (for "Open"). */
    val locations: List<String>,
)

/** The radio side of a transfer (Wi-Fi Direct + TCP on Android; localhost sockets in unit tests). */
interface TransferLink {
    suspend fun host(): LinkParams?
    suspend fun join(p: LinkParams): Boolean
    /** Opens the authenticated TCP channel (host accepts, guest connects). Null = failed. */
    suspend fun openStream(session: StreamSession): SecureStream?
    fun close()
}

data class TransferConfig(
    val offerTimeoutMs: Long = 60_000,
    val connectTimeoutMs: Long = 35_000,
    val retryMs: Long = 3_000,
    val stallMs: Long = 20_000,
    val endedLingerMs: Long = 2_500,
    val tickMs: Long = 250,
    /** After the stream broke, wait this long for the other phone's "cancelled" signal so we can say WHY it ended. */
    val abortGraceMs: Long = 1_500,
)

/**
 * File-transfer state machine, pure Kotlin like [CallManager] and unit-tested the same way.
 *
 *   sender:   OFFERING -> CONNECTING -> TRANSFERRING -> ENDED
 *   receiver: ASKING   -> CONNECTING -> TRANSFERRING -> ENDED
 *
 * The offer, accept and Wi-Fi credentials travel over the BLE mesh (same signals as a call, kind = FILES); the files
 * travel over a TCP connection on the Wi-Fi Direct group, encrypted with the per-session key.
 */
class TransferManager(
    private val scope: CoroutineScope,
    private val signaler: CallSignaler,
    private val gate: LinkGate,
    private val linkFactory: () -> TransferLink,
    private val sinkFactory: () -> FileSink,
    private val onFinished: (TransferRecord) -> Unit,
    private val config: TransferConfig = TransferConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Session(
        val id: ByteArray, val peerId: String, val outgoing: Boolean, val offer: OfferInfo, val startedAt: Long,
    ) {
        val idHex = Hex.encode(id)
        var phase = TransferPhase.IDLE
        var params: LinkParams? = null
        var link: TransferLink? = null
        var sources: List<FileSource> = emptyList()
        var rows: List<FileRow> = emptyList()
        var connectedAt = 0L
        val jobs = mutableListOf<Job>()
        val done = AtomicLong(0)
        @Volatile var fileIndex = 0
        @Volatile var lastProgressAt = 0L
        val locations = java.util.Collections.synchronizedList(ArrayList<String>())
        val fileOk = java.util.Collections.synchronizedList(ArrayList<Boolean?>())
        @Volatile var stream: SecureStream? = null
        /** Receiver: the sender's manifest arrived, so the sender definitely knows we accepted. */
        @Volatile var gotManifest = false
    }

    private val lock = Any()
    private var cur: Session? = null
    private val _ui = MutableStateFlow(TransferUi())
    val ui: StateFlow<TransferUi> = _ui.asStateFlow()

    // ------------------------------------------------------------------ user actions

    fun send(peerId: String, files: List<FileSource>): StartResult {
        if (files.isEmpty()) return StartResult.EMPTY
        if (!signaler.isReachable(peerId)) return StartResult.UNREACHABLE
        val s: Session
        synchronized(lock) {
            if (cur != null) return StartResult.BUSY
            val id = AudioPacketIds.newId()
            if (!gate.tryAcquire(Hex.encode(id))) return StartResult.BUSY
            val total = files.sumOf { it.meta.size }
            val title = files.first().meta.name
            s = Session(id, peerId, true, OfferInfo(files.size, total, title), clock())
            s.sources = files
            s.rows = files.map { FileRow(it.meta.name, it.meta.size, FileRow.WAITING) }
            s.fileOk.addAll(List(files.size) { null })
            s.link = linkFactory()
            s.phase = TransferPhase.OFFERING
            cur = s
            publish(s)
        }
        s.jobs += scope.launch { offerLoop(s) }
        s.jobs += scope.launch {
            val p = s.link!!.host()
            if (p == null) {
                finish(s, TransferEnd.FAILED, CallSignalType.CANCEL)
                return@launch
            }
            synchronized(lock) { if (cur === s) s.params = p }
            signaler.send(s.peerId, CallSignal(CallSignalType.LINK, s.id, SessionKind.FILES, p))
        }
        return StartResult.OK
    }

    /** Receiver taps Accept. */
    fun accept() {
        val s: Session
        synchronized(lock) {
            s = cur ?: return
            if (s.outgoing || s.phase != TransferPhase.ASKING) return
            s.phase = TransferPhase.CONNECTING
            s.link = linkFactory()
            publish(s)
        }
        s.jobs += scope.launch {
            val until = clock() + config.connectTimeoutMs
            // Keep saying "accepted" until the sender's manifest arrives: our TCP connect can succeed before the sender
            // has even seen ACCEPT, so the phase alone does not prove it knows.
            while (isActive && clock() < until) {
                val ph = phaseOf(s)
                if (!(ph == TransferPhase.CONNECTING || (ph == TransferPhase.TRANSFERRING && !s.gotManifest))) break
                signaler.send(s.peerId, CallSignal(CallSignalType.ACCEPT, s.id, SessionKind.FILES))
                delay(config.retryMs)
            }
        }
        s.jobs += scope.launch {
            val p = awaitParams(s)
            if (p == null || !s.link!!.join(p)) {
                finish(s, TransferEnd.FAILED, CallSignalType.END)
                return@launch
            }
            runTransfer(s)
        }
        s.jobs += scope.launch { connectWatchdog(s) }
    }

    /** Receiver taps Decline. */
    fun decline() {
        val s = synchronized(lock) { cur } ?: return
        if (!s.outgoing && phaseOf(s) == TransferPhase.ASKING) finish(s, TransferEnd.DECLINED, CallSignalType.REJECT)
    }

    /** Cancel in every state. */
    fun cancel() {
        val s = synchronized(lock) { cur } ?: return
        when (phaseOf(s)) {
            TransferPhase.OFFERING -> finish(s, TransferEnd.CANCELLED_BY_ME, CallSignalType.CANCEL)
            TransferPhase.ASKING -> finish(s, TransferEnd.DECLINED, CallSignalType.REJECT)
            TransferPhase.CONNECTING, TransferPhase.TRANSFERRING -> finish(s, TransferEnd.CANCELLED_BY_ME, CallSignalType.END)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ signals from the mesh

    fun onSignal(peerId: String, sig: CallSignal) {
        if (sig.kind != SessionKind.FILES) return
        var reply: CallSignal? = null
        var toStart: Session? = null
        var connectHost: Session? = null
        var ended: Triple<Session, TransferEnd, CallSignalType?>? = null

        synchronized(lock) {
            val s = cur
            val mine = s != null && s.idHex == sig.callIdHex && s.peerId == peerId
            when (sig.type) {
                CallSignalType.INVITE -> {
                    if (s == null) {
                        val offer = sig.offer
                        if (offer != null && gate.tryAcquire(sig.callIdHex)) {
                            val n = Session(sig.callId, peerId, false, offer, clock())
                            n.phase = TransferPhase.ASKING
                            cur = n
                            publish(n)
                            toStart = n
                        } else if (offer != null) {
                            reply = CallSignal(CallSignalType.BUSY, sig.callId, SessionKind.FILES)
                        }
                    } else if (!mine) {
                        reply = CallSignal(CallSignalType.BUSY, sig.callId, SessionKind.FILES)
                    }
                }
                CallSignalType.RINGING -> Unit        // the offer reached the other phone; the screen already says "Waiting…"
                CallSignalType.LINK -> if (mine && !s!!.outgoing && sig.link != null && s.params == null) s.params = sig.link
                CallSignalType.ACCEPT -> if (mine && s!!.outgoing && s.phase == TransferPhase.OFFERING) {
                    s.phase = TransferPhase.CONNECTING
                    publish(s)
                    connectHost = s
                }
                CallSignalType.REJECT -> if (mine && s!!.outgoing && s.phase == TransferPhase.OFFERING) ended = Triple(s, TransferEnd.DECLINED, null)
                CallSignalType.BUSY -> if (mine && s!!.outgoing && s.phase == TransferPhase.OFFERING) ended = Triple(s, TransferEnd.BUSY, null)
                CallSignalType.CANCEL -> if (mine && !s!!.outgoing && s.phase == TransferPhase.ASKING) ended = Triple(s, TransferEnd.MISSED, null)
                CallSignalType.END -> if (mine) ended = Triple(s!!, TransferEnd.CANCELLED_BY_PEER, null)
            }
        }
        reply?.let { r -> scope.launch { signaler.send(peerId, r) } }
        toStart?.let { s -> s.jobs += scope.launch { askingLoop(s) } }
        connectHost?.let { s ->
            s.jobs += scope.launch {
                if (awaitParams(s) == null) {
                    finish(s, TransferEnd.FAILED, CallSignalType.END)
                    return@launch
                }
                runTransfer(s)
            }
            s.jobs += scope.launch { connectWatchdog(s) }
        }
        ended?.let { (s, why, notify) -> finish(s, why, notify) }
    }

    // ------------------------------------------------------------------ loops

    private suspend fun offerLoop(s: Session) {
        val until = clock() + config.offerTimeoutMs
        var failures = 0
        while (scope.isActive) {
            val ph = phaseOf(s)
            if (ph != TransferPhase.OFFERING && ph != TransferPhase.CONNECTING) return
            if (ph == TransferPhase.OFFERING) {
                if (clock() > until) {
                    finish(s, TransferEnd.NO_ANSWER, CallSignalType.CANCEL)
                    return
                }
                val ok = signaler.send(s.peerId, CallSignal(CallSignalType.INVITE, s.id, SessionKind.FILES, offer = s.offer))
                failures = if (ok) 0 else failures + 1
                if (failures >= 3 && s.params == null) {
                    finish(s, TransferEnd.UNREACHABLE, null)
                    return
                }
            }
            s.params?.let { signaler.send(s.peerId, CallSignal(CallSignalType.LINK, s.id, SessionKind.FILES, it)) }
            delay(config.retryMs)
        }
    }

    private suspend fun askingLoop(s: Session) {
        val until = clock() + config.offerTimeoutMs + config.retryMs * 2
        while (scope.isActive && phaseOf(s) == TransferPhase.ASKING) {
            if (clock() > until) {
                finish(s, TransferEnd.MISSED, null)
                return
            }
            signaler.send(s.peerId, CallSignal(CallSignalType.RINGING, s.id, SessionKind.FILES))
            delay(config.retryMs)
        }
    }

    private suspend fun connectWatchdog(s: Session) {
        delay(config.connectTimeoutMs)
        if (phaseOf(s) == TransferPhase.CONNECTING) finish(s, TransferEnd.FAILED, CallSignalType.END)
    }

    private suspend fun awaitParams(s: Session): LinkParams? {
        val until = clock() + config.connectTimeoutMs
        while (scope.isActive && clock() < until) {
            if (phaseOf(s) != TransferPhase.CONNECTING) return null
            s.params?.let { return it }
            delay(50)
        }
        return null
    }

    // ------------------------------------------------------------------ the transfer itself

    private suspend fun runTransfer(s: Session) {
        val key = signaler.callKey(s.peerId, s.id)
        if (key == null) {
            finish(s, TransferEnd.FAILED, CallSignalType.END)
            return
        }
        val stream = s.link!!.openStream(StreamSession(s.id, key, isHost = s.outgoing))
        if (stream == null) {
            finish(s, TransferEnd.FAILED, CallSignalType.END)
            return
        }
        s.stream = stream
        val go = synchronized(lock) {
            if (cur === s && s.phase == TransferPhase.CONNECTING) {
                s.phase = TransferPhase.TRANSFERRING
                s.connectedAt = clock()
                s.lastProgressAt = s.connectedAt
                publish(s)
                true
            } else false
        }
        if (!go) {
            stream.close()
            return
        }
        s.jobs += scope.launch { ticker(s) }

        val listener = object : TransferListener {
            override fun onManifest(files: List<FileMeta>) {
                s.gotManifest = true
                synchronized(lock) {
                    if (cur === s) {
                        s.rows = files.map { FileRow(it.name, it.size, FileRow.WAITING) }
                        s.fileOk.clear()
                        s.fileOk.addAll(List(files.size) { null })
                    }
                }
            }

            override fun onProgress(fileIndex: Int, totalDone: Long) {
                s.fileIndex = fileIndex
                s.done.set(totalDone)
                s.lastProgressAt = clock()
            }

            override fun onFileDone(fileIndex: Int, ok: Boolean, location: String?) {
                if (fileIndex < s.fileOk.size) s.fileOk[fileIndex] = ok
                if (location != null) s.locations += location
                s.lastProgressAt = clock()
            }
        }

        val result = withContext(Dispatchers.IO) {
            try {
                if (s.outgoing) FileSender(s.sources, listener).run(stream)
                else FileReceiver(sinkFactory(), s.offer, listener).run(stream)
            } catch (e: Exception) {
                TransferResult(TransferStatus.ABORTED, emptyList())
            }
        }
        // The link may have been closed by cancel()/stall: finish() ignores us then.
        if (result.status == TransferStatus.ABORTED) delay(config.abortGraceMs)
        val end = when (result.status) {
            TransferStatus.OK -> TransferEnd.COMPLETED
            TransferStatus.PARTIAL -> TransferEnd.PARTIAL
            TransferStatus.FAILED -> TransferEnd.FAILED
            TransferStatus.ABORTED -> TransferEnd.LOST
        }
        finish(s, end, null)
    }

    /** Publishes progress/speed ~4x a second and aborts a transfer that has stalled. */
    private suspend fun ticker(s: Session) {
        var lastBytes = 0L
        var lastAt = clock()
        var speed = 0.0
        while (scope.isActive && phaseOf(s) == TransferPhase.TRANSFERRING) {
            delay(config.tickMs)
            val now = clock()
            val done = s.done.get()
            val dt = (now - lastAt).coerceAtLeast(1)
            val inst = (done - lastBytes) * 1000.0 / dt
            speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
            lastBytes = done
            lastAt = now
            if (now - s.lastProgressAt > config.stallMs) {
                finish(s, TransferEnd.LOST, CallSignalType.END)
                return
            }
            synchronized(lock) {
                if (cur !== s) return
                val total = s.offer.totalBytes
                val remaining = (total - done).coerceAtLeast(0)
                _ui.value = _ui.value.copy(
                    fileIndex = s.fileIndex, doneBytes = done, speedBps = speed.toLong(),
                    etaSec = if (speed > 1) (remaining / speed).toLong() else -1,
                    files = rowsFor(s),
                )
            }
        }
    }

    // ------------------------------------------------------------------ ending

    private fun finish(s: Session, reason: TransferEnd, notify: CallSignalType?) {
        val rec: TransferRecord
        val last: TransferUi
        synchronized(lock) {
            if (cur !== s) return
            val now = clock()
            val okCount = s.fileOk.count { it == true }
            val done = s.done.get()
            rec = TransferRecord(
                s.peerId, s.idHex, s.outgoing, s.offer.title, s.offer.count, s.offer.totalBytes,
                if (reason == TransferEnd.COMPLETED) s.offer.totalBytes else done, okCount, reason, s.startedAt,
                if (s.connectedAt > 0) now - s.connectedAt else 0L, s.locations.toList(),
            )
            cur = null
            s.phase = TransferPhase.ENDED
            last = TransferUi(
                phase = TransferPhase.ENDED, peerId = s.peerId, idHex = s.idHex, outgoing = s.outgoing, offer = s.offer,
                files = rowsFor(s, finalReason = reason), fileIndex = s.fileIndex,
                doneBytes = if (reason == TransferEnd.COMPLETED) s.offer.totalBytes else done,
                totalBytes = s.offer.totalBytes, endReason = reason,
            )
            _ui.value = last
        }
        s.jobs.forEach { it.cancel() }
        val link = s.link
        val stream = s.stream
        scope.launch {
            // Tell the other phone first (BLE is slower than the TCP reset it is about to see), then tear the link down.
            if (notify != null) signaler.send(s.peerId, CallSignal(notify, s.id, SessionKind.FILES))
            runCatching { stream?.close() }
            link?.close()
            gate.release(s.idHex)
            if (notify != null) {
                delay(300)
                signaler.send(s.peerId, CallSignal(notify, s.id, SessionKind.FILES))
            }
        }
        onFinished(rec)
        scope.launch {
            delay(config.endedLingerMs)
            synchronized(lock) {
                if (_ui.value.phase == TransferPhase.ENDED && _ui.value.idHex == s.idHex) _ui.value = TransferUi()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun phaseOf(s: Session): TransferPhase = synchronized(lock) { if (cur === s) s.phase else TransferPhase.ENDED }

    private fun rowsFor(s: Session, finalReason: TransferEnd? = null): List<FileRow> =
        s.rows.mapIndexed { i, r ->
            val ok = s.fileOk.getOrNull(i)
            val state = when {
                ok == true -> FileRow.DONE
                ok == false -> FileRow.FAILED
                finalReason == TransferEnd.COMPLETED -> FileRow.DONE
                s.phase == TransferPhase.TRANSFERRING && i == s.fileIndex -> FileRow.ACTIVE
                else -> FileRow.WAITING
            }
            FileRow(r.name, r.size, state)
        }

    private fun publish(s: Session) {
        _ui.value = TransferUi(
            phase = s.phase, peerId = s.peerId, idHex = s.idHex, outgoing = s.outgoing, offer = s.offer,
            files = rowsFor(s), fileIndex = s.fileIndex, doneBytes = s.done.get(), totalBytes = s.offer.totalBytes,
        )
    }
}
