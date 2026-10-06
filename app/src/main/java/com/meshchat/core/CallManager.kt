package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CallPhase { IDLE, CALLING, RINGING, INCOMING, CONNECTING, ACTIVE, ENDED }

enum class CallEnd {
    NONE, LOCAL_HANGUP, REMOTE_HANGUP, CANCELLED, DECLINED, MISSED, NO_ANSWER, BUSY, UNREACHABLE, FAILED, LOST;

    /** Short text for the "Call ended" screen. */
    fun label(): String = when (this) {
        LOCAL_HANGUP, REMOTE_HANGUP, NONE -> "Call ended"
        CANCELLED -> "Call cancelled"
        DECLINED -> "Call declined"
        MISSED -> "Missed call"
        NO_ANSWER -> "No answer"
        BUSY -> "Busy"
        UNREACHABLE -> "Not in range"
        FAILED -> "Could not connect. Move closer to each other"
        LOST -> "Connection lost"
    }
}

/** Everything the call screen, notifications and ringtone logic need. Immutable; replaced on every change. */
data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val peerId: String = "",
    val callIdHex: String = "",
    val outgoing: Boolean = false,
    val video: Boolean = false,
    /** Wall-clock time (this phone's clock) at which media started flowing; the UI derives mm:ss from it. */
    val connectedAtMs: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Our camera is sending video (video calls only). */
    val cameraOn: Boolean = false,
    /** The other person's camera is on (they tell us over the media channel). */
    val remoteCameraOn: Boolean = true,
    val poorConnection: Boolean = false,
    val endReason: CallEnd = CallEnd.NONE,
) {
    val inCall: Boolean get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
}

/** One finished (or never-connected) call, for the chat history line and the missed-call notification. */
data class CallRecord(
    val peerId: String,
    val callIdHex: String,
    val outgoing: Boolean,
    val video: Boolean,
    val outcome: CallEnd,
    val startedAt: Long,
    val durationMs: Long,
)

enum class StartResult { OK, BUSY, UNREACHABLE, NO_KEY, EMPTY }

data class CallConfig(
    val ringTimeoutMs: Long = 45_000,
    val connectTimeoutMs: Long = 35_000,
    val retryMs: Long = 3_000,
    val poorAfterMs: Long = 2_500,
    val lostAfterMs: Long = 10_000,
    val endedLingerMs: Long = 1_800,
    val monitorMs: Long = 500,
)

/** How the managers talk to the mesh. MeshRepository adapts MeshEngine to this. */
interface CallSignaler {
    suspend fun send(peerId: String, signal: CallSignal): Boolean
    suspend fun callKey(peerId: String, callId: ByteArray): ByteArray?
    fun isReachable(peerId: String): Boolean
}

class MediaSession(val callId: ByteArray, val key: ByteArray, val isHost: Boolean, val video: Boolean)

interface CallLinkListener {
    fun onRemoteCamera(on: Boolean)
}

/**
 * The radio + media side of a call (Wi-Fi Direct + WebRTC on Android; a fake in unit tests).
 * One instance per call; [close] must be safe to call at any time, any number of times.
 */
interface CallLink {
    /** Caller: create the Wi-Fi Direct group and listen for the callee. Null = failed. */
    suspend fun host(): LinkParams?

    /** Callee: join the caller's group. */
    suspend fun join(p: LinkParams): Boolean

    /** Negotiate and start audio (+ video); returns true once media is flowing in both directions. */
    suspend fun startMedia(session: MediaSession): Boolean

    /** Milliseconds since the last media packet arrived from the other side. */
    fun lastPacketAgeMs(): Long

    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
    fun setCamera(on: Boolean)
    fun switchCamera()
    fun setListener(l: CallLinkListener?)
    fun close()
}

/**
 * Call state machine, pure Kotlin (no Android classes) so the whole ring / answer / hang-up protocol is unit-tested.
 *
 *   caller:  CALLING -> RINGING -> CONNECTING -> ACTIVE -> ENDED
 *   callee:  INCOMING            -> CONNECTING -> ACTIVE -> ENDED
 *
 * Signalling is unreliable (BLE packets are never queued), so every waiting state re-sends its last signal every
 * [CallConfig.retryMs] and the receiver ignores duplicates. Only one Wi-Fi session at a time ([LinkGate]); a second
 * INVITE gets BUSY.
 */
class CallManager(
    private val scope: CoroutineScope,
    private val signaler: CallSignaler,
    private val gate: LinkGate,
    private val linkFactory: () -> CallLink,
    private val onFinished: (CallRecord) -> Unit,
    private val config: CallConfig = CallConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Session(val callId: ByteArray, val peerId: String, val outgoing: Boolean, val video: Boolean, val startedAt: Long) {
        val idHex = Hex.encode(callId)
        var phase = CallPhase.IDLE
        var params: LinkParams? = null
        var connectedAt = 0L
        var linkImpl: CallLink? = null
        val jobs = mutableListOf<Job>()
    }

    private val lock = Any()
    private var cur: Session? = null
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    // ------------------------------------------------------------------ user actions

    fun startCall(peerId: String, video: Boolean = false): StartResult {
        if (!signaler.isReachable(peerId)) return StartResult.UNREACHABLE
        val s: Session
        synchronized(lock) {
            if (cur != null) return StartResult.BUSY
            val id = AudioPacketIds.newId()
            if (!gate.tryAcquire(Hex.encode(id))) return StartResult.BUSY
            s = Session(id, peerId, true, video, clock())
            s.linkImpl = newLink(s)
            s.phase = CallPhase.CALLING
            cur = s
            publish(s)
        }
        s.jobs += scope.launch { callerLoop(s) }
        s.jobs += scope.launch {
            val p = s.linkImpl!!.host()
            if (p == null) {
                finish(s, CallEnd.FAILED, CallSignalType.CANCEL)
                return@launch
            }
            synchronized(lock) { if (cur === s) s.params = p }
            signaler.send(s.peerId, CallSignal(CallSignalType.LINK, s.callId, kindOf(s), p))   // do not wait for the next retry tick
        }
        return StartResult.OK
    }

    /** Callee taps Answer. */
    fun accept() {
        val s: Session
        synchronized(lock) {
            s = cur ?: return
            if (s.outgoing || s.phase != CallPhase.INCOMING) return
            s.phase = CallPhase.CONNECTING
            s.linkImpl = newLink(s)
            publish(s)
        }
        s.jobs += scope.launch {                         // keep telling the caller until we are connected
            val until = clock() + config.connectTimeoutMs
            while (isActive && phaseOf(s) == CallPhase.CONNECTING && clock() < until) {
                signaler.send(s.peerId, CallSignal(CallSignalType.ACCEPT, s.callId, kindOf(s)))
                delay(config.retryMs)
            }
        }
        s.jobs += scope.launch {
            val p = awaitParams(s)
            if (p == null) {
                finish(s, CallEnd.FAILED, CallSignalType.END)
                return@launch
            }
            if (!s.linkImpl!!.join(p)) {
                finish(s, CallEnd.FAILED, CallSignalType.END)
                return@launch
            }
            startMedia(s)
        }
        s.jobs += scope.launch { connectWatchdog(s) }
    }

    /** Callee taps Decline. */
    fun reject() {
        val s = synchronized(lock) { cur } ?: return
        if (!s.outgoing && phaseOf(s) == CallPhase.INCOMING) finish(s, CallEnd.DECLINED, CallSignalType.REJECT)
    }

    /** The red button, in every state. */
    fun hangUp() {
        val s = synchronized(lock) { cur } ?: return
        when (phaseOf(s)) {
            CallPhase.CALLING, CallPhase.RINGING -> finish(s, CallEnd.CANCELLED, CallSignalType.CANCEL)
            CallPhase.INCOMING -> finish(s, CallEnd.DECLINED, CallSignalType.REJECT)
            CallPhase.CONNECTING, CallPhase.ACTIVE -> finish(s, CallEnd.LOCAL_HANGUP, CallSignalType.END)
            else -> Unit
        }
    }

    fun setMuted(muted: Boolean) {
        val s: Session
        synchronized(lock) {
            s = cur ?: return
            _ui.value = _ui.value.copy(muted = muted)
        }
        s.linkImpl?.setMuted(muted)
    }

    fun setSpeaker(on: Boolean) {
        val s: Session
        synchronized(lock) {
            s = cur ?: return
            _ui.value = _ui.value.copy(speaker = on)
        }
        s.linkImpl?.setSpeaker(on)
    }

    /** Camera on/off (video calls). Off also releases the camera so its indicator light goes out. */
    fun setCamera(on: Boolean) {
        val s: Session
        synchronized(lock) {
            s = cur ?: return
            if (!s.video) return
            _ui.value = _ui.value.copy(cameraOn = on)
        }
        s.linkImpl?.setCamera(on)
    }

    fun switchCamera() {
        val s = synchronized(lock) { cur } ?: return
        if (s.video) s.linkImpl?.switchCamera()
    }

    // ------------------------------------------------------------------ signals from the mesh

    fun onSignal(peerId: String, sig: CallSignal) {
        if (sig.kind == SessionKind.FILES) return
        var reply: CallSignal? = null
        var toStart: Session? = null
        var connectHost: Session? = null
        var ended: Triple<Session, CallEnd, CallSignalType?>? = null

        synchronized(lock) {
            val s = cur
            val mine = s != null && s.idHex == sig.callIdHex && s.peerId == peerId
            when (sig.type) {
                CallSignalType.INVITE -> {
                    if (s == null) {
                        if (gate.tryAcquire(sig.callIdHex)) {
                            val n = Session(sig.callId, peerId, false, sig.video, clock())
                            n.phase = CallPhase.INCOMING
                            cur = n
                            publish(n)
                            toStart = n
                        } else {
                            reply = CallSignal(CallSignalType.BUSY, sig.callId, sig.kind)
                        }
                    } else if (!mine) {
                        reply = CallSignal(CallSignalType.BUSY, sig.callId, sig.kind)
                    }
                    // mine == true: duplicate invite, the incoming loop keeps re-sending RINGING
                }
                CallSignalType.RINGING -> if (mine && s!!.outgoing && s.phase == CallPhase.CALLING) {
                    s.phase = CallPhase.RINGING
                    publish(s)
                }
                CallSignalType.LINK -> if (mine && !s!!.outgoing && sig.link != null && s.params == null) {
                    s.params = sig.link
                }
                CallSignalType.ACCEPT -> if (mine && s!!.outgoing && (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)) {
                    s.phase = CallPhase.CONNECTING
                    publish(s)
                    connectHost = s
                }
                CallSignalType.REJECT -> if (mine && s!!.outgoing && (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)) {
                    ended = Triple(s, CallEnd.DECLINED, null)
                }
                CallSignalType.BUSY -> if (mine && s!!.outgoing && (s.phase == CallPhase.CALLING || s.phase == CallPhase.RINGING)) {
                    ended = Triple(s, CallEnd.BUSY, null)
                }
                CallSignalType.CANCEL -> if (mine && !s!!.outgoing && s.phase == CallPhase.INCOMING) {
                    ended = Triple(s, CallEnd.MISSED, null)
                }
                CallSignalType.END -> if (mine) {
                    ended = Triple(s!!, if (s.phase == CallPhase.ACTIVE) CallEnd.REMOTE_HANGUP else CallEnd.FAILED, null)
                }
            }
        }

        reply?.let { r -> scope.launch { signaler.send(peerId, r) } }
        toStart?.let { s -> s.jobs += scope.launch { incomingLoop(s) } }
        connectHost?.let { s ->
            s.jobs += scope.launch {
                if (awaitParams(s) == null) {
                    finish(s, CallEnd.FAILED, CallSignalType.END)
                    return@launch
                }
                startMedia(s)
            }
            s.jobs += scope.launch { connectWatchdog(s) }
        }
        ended?.let { (s, why, notify) -> finish(s, why, notify) }
    }

    // ------------------------------------------------------------------ loops

    /**
     * Caller: re-send INVITE (+ LINK once the group exists) until someone answers or we give up. After ACCEPT keep
     * re-sending LINK until the call is active, because the callee cannot join without it.
     */
    private suspend fun callerLoop(s: Session) {
        val until = clock() + config.ringTimeoutMs
        var failures = 0
        while (scope.isActive) {
            val ph = phaseOf(s)
            val ringing = ph == CallPhase.CALLING || ph == CallPhase.RINGING
            if (!ringing && ph != CallPhase.CONNECTING) return
            if (ringing && clock() > until) {
                finish(s, CallEnd.NO_ANSWER, CallSignalType.CANCEL)
                return
            }
            if (ringing) {
                val ok = signaler.send(s.peerId, CallSignal(CallSignalType.INVITE, s.callId, kindOf(s)))
                failures = if (ok) 0 else failures + 1
                if (failures >= 3 && ph == CallPhase.CALLING) {
                    finish(s, CallEnd.UNREACHABLE, null)
                    return
                }
            }
            s.params?.let { signaler.send(s.peerId, CallSignal(CallSignalType.LINK, s.callId, kindOf(s), it)) }
            delay(config.retryMs)
        }
    }

    /** Callee: confirm ringing (repeatedly, the first RINGING may be lost) and stop ringing when the caller gives up. */
    private suspend fun incomingLoop(s: Session) {
        val until = clock() + config.ringTimeoutMs + config.retryMs * 2
        while (scope.isActive && phaseOf(s) == CallPhase.INCOMING) {
            if (clock() > until) {
                finish(s, CallEnd.MISSED, null)
                return
            }
            signaler.send(s.peerId, CallSignal(CallSignalType.RINGING, s.callId, kindOf(s)))
            delay(config.retryMs)
        }
    }

    private suspend fun connectWatchdog(s: Session) {
        delay(config.connectTimeoutMs)
        if (phaseOf(s) == CallPhase.CONNECTING) finish(s, CallEnd.FAILED, CallSignalType.END)
    }

    private suspend fun awaitParams(s: Session): LinkParams? {
        val until = clock() + config.connectTimeoutMs
        while (scope.isActive && clock() < until) {
            if (phaseOf(s) != CallPhase.CONNECTING) return null
            s.params?.let { return it }
            delay(50)
        }
        return null
    }

    private suspend fun startMedia(s: Session) {
        val key = signaler.callKey(s.peerId, s.callId)
        if (key == null) {
            finish(s, CallEnd.FAILED, CallSignalType.END)
            return
        }
        val ok = s.linkImpl!!.startMedia(MediaSession(s.callId, key, isHost = s.outgoing, video = s.video))
        val activated = synchronized(lock) {
            if (cur === s && s.phase == CallPhase.CONNECTING && ok) {
                s.phase = CallPhase.ACTIVE
                s.connectedAt = clock()
                publish(s)
                true
            } else false
        }
        if (!ok) {
            finish(s, CallEnd.FAILED, CallSignalType.END)
            return
        }
        if (activated) {
            val st = _ui.value
            s.linkImpl?.setMuted(st.muted)
            s.linkImpl?.setSpeaker(st.speaker)
            if (s.video) s.linkImpl?.setCamera(st.cameraOn)
            s.jobs += scope.launch { monitor(s) }
        }
    }

    /** While ACTIVE: flag a weak link after a short silence, hang up after a long one. */
    private suspend fun monitor(s: Session) {
        while (scope.isActive && phaseOf(s) == CallPhase.ACTIVE) {
            val age = s.linkImpl?.lastPacketAgeMs() ?: 0L
            if (age > config.lostAfterMs) {
                finish(s, CallEnd.LOST, CallSignalType.END)
                return
            }
            val poor = age > config.poorAfterMs
            synchronized(lock) {
                if (cur === s && _ui.value.poorConnection != poor) _ui.value = _ui.value.copy(poorConnection = poor)
            }
            delay(config.monitorMs)
        }
    }

    // ------------------------------------------------------------------ ending

    /** Non-suspending on purpose: it may run inside one of the session's own jobs, which it cancels. */
    private fun finish(s: Session, reason: CallEnd, notify: CallSignalType?) {
        val rec: CallRecord
        synchronized(lock) {
            if (cur !== s) return
            val wasActive = s.phase == CallPhase.ACTIVE
            val now = clock()
            rec = CallRecord(s.peerId, s.idHex, s.outgoing, s.video, reason, s.startedAt, if (wasActive) now - s.connectedAt else 0L)
            cur = null
            s.phase = CallPhase.ENDED
            _ui.value = CallUi(
                phase = CallPhase.ENDED, peerId = s.peerId, callIdHex = s.idHex, outgoing = s.outgoing, video = s.video,
                connectedAtMs = s.connectedAt, endReason = reason,
            )
        }
        s.jobs.forEach { it.cancel() }
        val link = s.linkImpl
        scope.launch {
            link?.setListener(null)
            link?.close()
            gate.release(s.idHex)
            if (notify != null) repeat(2) {
                signaler.send(s.peerId, CallSignal(notify, s.callId, kindOf(s)))
                delay(300)
            }
        }
        onFinished(rec)
        scope.launch {
            delay(config.endedLingerMs)
            synchronized(lock) {
                if (_ui.value.phase == CallPhase.ENDED && _ui.value.callIdHex == s.idHex) _ui.value = CallUi()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun kindOf(s: Session) = if (s.video) SessionKind.VIDEO else SessionKind.AUDIO

    private fun newLink(s: Session): CallLink = linkFactory().also { l ->
        l.setListener(object : CallLinkListener {
            override fun onRemoteCamera(on: Boolean) {
                synchronized(lock) { if (cur === s) _ui.value = _ui.value.copy(remoteCameraOn = on) }
            }
        })
    }

    private fun phaseOf(s: Session): CallPhase = synchronized(lock) { if (cur === s) s.phase else CallPhase.ENDED }

    private fun publish(s: Session) {
        val old = _ui.value
        val sameCall = old.callIdHex == s.idHex
        _ui.value = CallUi(
            phase = s.phase, peerId = s.peerId, callIdHex = s.idHex, outgoing = s.outgoing, video = s.video,
            connectedAtMs = s.connectedAt,
            muted = if (sameCall) old.muted else false,
            speaker = if (sameCall) old.speaker else s.video,        // video calls start on the loudspeaker
            cameraOn = if (sameCall) old.cameraOn else s.video,
            remoteCameraOn = if (sameCall) old.remoteCameraOn else true,
            poorConnection = if (sameCall) old.poorConnection else false,
        )
    }
}

object AudioPacketIds {
    private val rng = java.security.SecureRandom()
    fun newId(): ByteArray = ByteArray(CallSignalCodec.ID_LEN).also { rng.nextBytes(it) }
}
