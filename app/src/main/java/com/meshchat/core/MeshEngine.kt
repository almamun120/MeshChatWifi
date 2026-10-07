package com.meshchat.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class MeshConfig(
    val neighborTimeoutMs: Long = 20_000,
    val routeTtlMs: Long = 75_000,
    val pendingTtlMs: Long = 24 * 3_600_000L,
    val retryBackoffMs: List<Long> = listOf(8_000L, 16_000L, 32_000L, 64_000L, 120_000L),
    val maxAttempts: Int = 5,
    val identityIntervalMs: Long = 60_000,
    val inventoryLimit: Int = 60,
    val wantLimit: Int = 30,
    val wantServeLimit: Int = 20,
    val postRateCapacity: Int = 5,
    val postRefillMs: Long = 12_000,
    val dupCapacity: Int = 4000,
    val dupTtlMs: Long = 10 * 60_000L,
    val tickMs: Long = 1_000,
    val pendingTickMs: Long = 3_000,
    val purgeIntervalMs: Long = 60_000,
    // ---- media (image / voice)
    /** Full-transfer retry schedule (a transfer takes seconds, so waits are longer than for text). */
    val mediaRetryBackoffMs: List<Long> = listOf(30_000L, 60_000L, 120_000L, 240_000L),
    val maxMediaAttempts: Int = 5,
    /** Receiver: no new chunk for this long while incomplete -> ask for the missing ones. */
    val nackIdleMs: Long = 8_000,
    val incomingTimeoutMs: Long = 10 * 60_000L,
    val maxIncoming: Int = 4,
    val maxOutTransfers: Int = 20,
    /** Tests only: when > 0 replaces the per-mode HELLO / sync intervals. */
    val intervalOverrideMs: Long = 0,
    /** Tests only: when > 0 replaces the periodic sync interval. */
    val syncOverrideMs: Long = 0,
) {
    fun helloIntervalMs(mode: DiscoveryMode): Long = when {
        intervalOverrideMs > 0 -> intervalOverrideMs
        mode == DiscoveryMode.HIGH -> 5_000L
        mode == DiscoveryMode.NORMAL -> 10_000L
        else -> 20_000L
    }

    fun syncIntervalMs(mode: DiscoveryMode): Long = when {
        syncOverrideMs > 0 -> syncOverrideMs
        intervalOverrideMs > 0 -> intervalOverrideMs * 2
        mode == DiscoveryMode.HIGH -> 60_000L
        mode == DiscoveryMode.NORMAL -> 120_000L
        else -> 180_000L
    }
}

data class MeshStats(
    val sent: Int = 0,
    val received: Int = 0,
    val relayed: Int = 0,
    val dropped: Int = 0,
    val delivered: Int = 0,
    val synced: Int = 0,
    val lastDrop: String = "",
)

sealed interface MeshEvent {
    /** [preview] is a short description ("Hello", "📷 Photo", "🎤 Voice message (0:12)") for notifications. */
    data class PrivateReceived(val peerId: String, val preview: String, val msgIdHex: String = "", val ptt: Boolean = false) : MeshEvent
    data class PostReceived(val authorName: String, val content: String) : MeshEvent
    data class Delivered(val msgIdHex: String) : MeshEvent
    /** A decrypted, authenticated session signal (call or file-transfer offer, accept, hang up, ...) from [peerId]. */
    class CallSignalReceived(val peerId: String, val signal: CallSignal) : MeshEvent
    /** An SOS (or an "I'm safe" for an earlier one) from [alert].nodeId. Relaying happens regardless; the app decides whether to alert the user. */
    class SosReceived(val alert: SosAlert) : MeshEvent
    /** A group was deleted by its creator, or we were removed from it: the UI must drop the chat. */
    class GroupGone(val groupId: String, val name: String, val deleted: Boolean) : MeshEvent
}

enum class SendResult { OK, EMPTY, TOO_LONG, NO_KEY, SELF, NOT_RUNNING, QUEUE_FULL, BAD_MEDIA, NO_GROUP }

/**
 * The mesh brain. Pure Kotlin + coroutines (no Android classes), so it is unit-testable on the JVM.
 *
 * Responsibilities: neighbor table, distance-vector routing, duplicate suppression, TTL-bounded relay,
 * end-to-end ACK + retry, store-and-forward (pending table + media outbox), inventory sync,
 * signing and encryption, chunked image/voice transfer with selective retransmission.
 */
class MeshEngine(
    private val identity: Identity,
    private val nameProvider: () -> String,
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val scope: CoroutineScope,
    private val config: MeshConfig = MeshConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Receiver switch for push-to-talk clips: false = they are acknowledged but dropped (nothing stored, no sound). */
    private val pttAllowed: () -> Boolean = { true },
    /** Optional e-mail shown to others next to the name (signed with the identity). */
    private val emailProvider: () -> String = { "" },
) {
    val myId: String = identity.nodeId

    private val rng = SecureRandom()
    private val dup = DuplicateCache(config.dupCapacity, config.dupTtlMs, clock)
    private val postLimiter = RateLimiter(config.postRateCapacity, config.postRefillMs, clock = clock)
    private val neighborTable = NeighborTable(config.neighborTimeoutMs)
    private val routingTable = RoutingTable(config.routeTtlMs)
    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val muted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingMutex = Mutex()
    private val jobs = mutableListOf<Job>()

    // ---- media state
    private class InTransfer(val total: Int, val startedAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var count = 0
        var lastChunkAt = startedAt
        var lastNackAt = 0L
    }

    private val incoming = HashMap<String, InTransfer>()                  // guarded by itself
    private val completedIn = object : LinkedHashMap<String, Long>(64, 0.75f, false) {   // guarded by itself
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 500
    }
    private val lastReAck = HashMap<String, Long>()                        // guarded by itself
    private val loggedPeers = HashSet<String>()                            // log "first seen" once per peer (event loop only)
    private val mediaMutex = Mutex()                                       // one media send at a time: BLE bandwidth is tiny
    private val mediaInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var mode = DiscoveryMode.NORMAL

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    val neighbors: StateFlow<List<Neighbor>> = _neighbors.asStateFlow()

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    /** Outgoing media upload progress by message id (0..1). Absent = not transmitting right now. */
    private val _mediaProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val mediaProgress: StateFlow<Map<String, Float>> = _mediaProgress.asStateFlow()

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val subscribed = CompletableDeferred<Unit>()
        synchronized(jobs) {
            jobs += scope.launch {
                blocked.addAll(store.blockedIds())
                muted.addAll(store.mutedIds())
                transport.events.onSubscription { subscribed.complete(Unit) }.collect { handleEvent(it) }
            }
            jobs += scope.launch {
                subscribed.await()
                transport.start()
            }
            jobs += scope.launch { housekeepingLoop() }
            jobs += scope.launch { pendingLoop() }
        }
    }

    fun stop() {
        synchronized(jobs) {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
        transport.stop()
    }

    fun setDiscoveryMode(m: DiscoveryMode) {
        mode = m
        transport.setDiscoveryMode(m)
    }

    fun currentMode(): DiscoveryMode = mode

    // ------------------------------------------------------------------ public API

    suspend fun sendAnnounce(text: String): SendResult {
        val t = text.trim()
        if (t.isEmpty()) return SendResult.EMPTY
        if (t.length > Protocol.MAX_POST_CHARS) return SendResult.TOO_LONG
        val name = nameProvider()
        val pkt = newPacket(
            PacketType.ANNOUNCE, NodeIds.BROADCAST,
            Payloads.encodeAnnounce(Payloads.Announce(name, t)),
            Protocol.DEFAULT_TTL, sign = true,
        )
        dup.checkAndAdd(pkt.key)
        val now = clock()
        store.savePost(PostRecord(pkt.key, myId, name, t, now, now, true, pkt.encode()))
        val bytes = pkt.encode()
        for (p in transport.linkedPeers()) {
            scope.launch { transport.send(p, pkt.type.channel, bytes) }
        }
        _stats.update { it.copy(sent = it.sent + 1) }
        return SendResult.OK
    }

    /** Floods one signed SOS ([active] = false means "I'm safe"). Not stored: the caller repeats it (see [SosSession]). */
    suspend fun sendSos(active: Boolean, battery: Int, location: SosLocation?): SendResult {
        val pkt = newPacket(
            PacketType.SOS, NodeIds.BROADCAST,
            SosCodec.encode(SosPayload(active, battery, location, nameProvider())),
            Protocol.MAX_HOPS, sign = true,
        )
        dup.checkAndAdd(pkt.key)
        val bytes = pkt.encode()
        val peers = transport.linkedPeers()
        for (p in peers) scope.launch { transport.send(p, pkt.type.channel, bytes) }
        _stats.update { it.copy(sent = it.sent + 1) }
        MeshLog.log("SOS ${if (active) "sent" else "safe sent"} to ${peers.size} neighbour(s)")
        return if (peers.isEmpty()) SendResult.NO_KEY else SendResult.OK      // NO_KEY here = nobody in range yet (the caller keeps repeating)
    }

    private val lastSos = HashMap<String, Pair<Long, Boolean>>()          // sender -> (time, active); event loop only

    private suspend fun onSos(from: String, pkt: MeshPacket) {
        val p = SosCodec.decode(pkt.payload)
        val sig = pkt.signature
        if (p == null || sig == null || !pkt.isBroadcast) {
            drop("bad sos")
            return
        }
        var verified = false
        val key = store.getNode(pkt.src)?.publicKey
        if (key != null) {
            if (!CryptoService.verify(key, pkt.signedBytes(), sig)) {
                drop("sos: bad signature")
                return
            }
            verified = true
        }
        val now = clock()
        val last = lastSos[pkt.src]
        // One alert per sender per 10 s, but a change of state ("I'm safe") always goes through.
        if (last != null && last.second == p.active && now - last.first < 10_000) {
            drop("sos: rate limit")
            return
        }
        lastSos[pkt.src] = now to p.active
        val alert = SosAlert(pkt.src, p.name, p.active, p.battery, p.location, minOf(pkt.timestamp, now), pkt.hops + 1, verified)
        _events.tryEmit(MeshEvent.SosReceived(alert))
        _stats.update { it.copy(received = it.received + 1) }
        forwardBroadcast(pkt, from)
    }

    suspend fun sendPrivate(peerId: String, text: String): SendResult = sendContent(peerId, Content.ofText(text))

    /**
     * Sends text, a location, an image or a voice message to one person, end-to-end encrypted.
     * The message is saved locally first. If the recipient is not reachable it waits (up to 24 h) and is delivered
     * automatically when a route to them appears — text/location through the pending table, image/voice through the
     * media outbox (the sender keeps the encrypted blob).
     */
    suspend fun sendContent(peerId: String, content: Content): SendResult =
        if (GroupIds.isGroup(peerId)) sendGroupContent(peerId, content)
        else sendInternal(peerId, content, save = !content.kind.isControl)

    private fun normalized(content: Content): Content? =
        if (content.kind == ContentKind.TEXT) {
            val t = content.text.trim()
            if (t.isEmpty() || t.length > Protocol.MAX_PRIVATE_CHARS) null else content.copyEnvelope().let { Content(ContentKind.TEXT, text = t, ttlSec = it.ttlSec, replyToId = it.replyToId, replyQuote = it.replyQuote, groupId = it.groupId, logicalId = it.logicalId) }
        } else content

    private suspend fun sendInternal(peerId: String, content: Content, save: Boolean): SendResult {
        if (peerId == myId) return SendResult.SELF
        if (content.kind == ContentKind.TEXT) {
            val t = content.text.trim()
            if (t.isEmpty()) return SendResult.EMPTY
            if (t.length > Protocol.MAX_PRIVATE_CHARS) return SendResult.TOO_LONG
        }
        val c = normalized(content)!!
        val peerKey = store.getNode(peerId)?.publicKey ?: return SendResult.NO_KEY
        val plain = ContentCodec.encode(c) ?: return SendResult.BAD_MEDIA
        val id = newMsgId()
        val idHex = Hex.encode(id)
        val now = clock()
        val blob = CryptoService.encrypt(identity, peerKey, peerId, CryptoService.aad(myId, peerId, id), plain)

        if (c.isMedia) {
            if (blob.size > MediaLimits.MAX_BLOB) return SendResult.TOO_LONG
            if (store.allOutTransfers().size >= config.maxOutTransfers) return SendResult.QUEUE_FULL
            val total = (blob.size + MediaLimits.CHUNK - 1) / MediaLimits.CHUNK
            if (save) store.saveMessage(peerId, idHex, true, c, now, MessageStatus.QUEUED, myId)
            store.addOutTransfer(OutTransferInfo(idHex, peerId, total, now, now + config.pendingTtlMs, 0, 0), blob)
        } else {
            val pkt = MeshPacket(PacketType.PRIVATE, 0, Protocol.MAX_HOPS, 0, id, myId, peerId, now, blob)
            dup.checkAndAdd(pkt.key)
            if (save) store.saveMessage(peerId, idHex, true, c, now, MessageStatus.QUEUED, myId)
            store.addPending(PendingRecord(pkt.key, peerId, pkt.encode(), true, now, now + config.pendingTtlMs, 0, 0))
        }
        scope.launch { flushQueues() }
        return SendResult.OK
    }

    /** Sends to every other member of the group (one encrypted private packet each). */
    private suspend fun sendGroupContent(gid: String, content: Content): SendResult {
        val g = store.getGroup(gid) ?: return SendResult.NO_GROUP
        if (myId !in g.members) return SendResult.NO_GROUP
        if (content.kind == ContentKind.TEXT) {
            val t = content.text.trim()
            if (t.isEmpty()) return SendResult.EMPTY
            if (t.length > Protocol.MAX_PRIVATE_CHARS) return SendResult.TOO_LONG
        }
        val logical = if (content.kind == ContentKind.DELETE) "" else Hex.encode(newMsgId())
        val c0 = normalized(content)!!
        val c = if (content.kind == ContentKind.DELETE) c0.copyEnvelope(groupId = gid, logicalId = Hex.encode(newMsgId()))
        else c0.copyEnvelope(groupId = gid, logicalId = logical)
        if (ContentCodec.encode(c) == null) return SendResult.BAD_MEDIA
        var any = false
        var firstError = SendResult.NO_KEY
        for (m in g.members) {
            if (m == myId) continue
            val r = sendInternal(m, c, save = false)
            if (r == SendResult.OK) any = true else firstError = r
        }
        if (!any) return firstError
        if (!content.kind.isControl) {
            store.saveMessage(gid, logical, true, c, clock(), MessageStatus.SENT, myId)
        }
        return SendResult.OK
    }

    /**
     * Creates a private group and invites every member. Returns the new group id, or null when a member's key is
     * unknown, the list is too long, or the name is empty.
     */
    suspend fun createGroup(name: String, others: List<String>): String? {
        val n = name.trim().take(GroupLimits.MAX_NAME)
        val members = (listOf(myId) + others.filter { it != myId }).distinct()
        if (n.isEmpty() || members.size !in 2..GroupLimits.MAX_MEMBERS) return null
        if (others.any { store.getNode(it)?.publicKey == null }) return null
        val gid = Hex.encode(newMsgId())
        store.saveGroup(GroupRecord(gid, n, myId, members, clock()))
        val invite = Content.ofGroupInvite(gid, n, members)
        for (m in members) if (m != myId) sendInternal(m, invite, save = false)
        return gid
    }

    /**
     * Creator only: renames the group and/or replaces its member list (add / remove people). Everyone who is or was a
     * member gets the new state; a person missing from the new list learns that they were removed.
     * [newOthers] = every member except me, or null to keep the members. Returns false if refused.
     */
    suspend fun updateGroup(gid: String, newName: String?, newOthers: List<String>?): Boolean {
        val g = store.getGroup(gid) ?: return false
        if (g.creator != myId || myId !in g.members) return false
        val name = (newName ?: g.name).trim().take(GroupLimits.MAX_NAME)
        val members = if (newOthers == null) g.members else (listOf(myId) + newOthers.filter { it != myId }).distinct()
        if (name.isEmpty() || members.size !in 2..GroupLimits.MAX_MEMBERS) return false
        val added = members.filter { it !in g.members }
        if (added.any { store.getNode(it)?.publicKey == null }) return false
        store.saveGroup(g.copy(name = name, members = members))
        val update = Content.ofGroupInvite(gid, name, members)
        for (m in (members + g.members).distinct()) if (m != myId) sendInternal(m, update, save = false)
        return true
    }

    /** Leaves a group: tells the others, then forgets it. The creator leaving deletes the group for everybody. */
    suspend fun leaveGroup(gid: String) {
        val g = store.getGroup(gid) ?: return
        val bye = Content.ofGroupLeave(gid)
        for (m in g.members) if (m != myId) sendInternal(m, bye, save = false)
        store.deleteGroup(gid)
    }

    /** Deletes a message on this phone; with [forEveryone] (own messages only) the other side(s) are told to delete it too. */
    suspend fun deleteMessage(chatKey: String, msgIdHex: String, forEveryone: Boolean) {
        store.deleteMessageLocal(msgIdHex)
        if (forEveryone) sendContent(chatKey, Content.ofDelete(msgIdHex))
    }

    /**
     * Applies a decrypted private content. Returns false when it cannot be accepted yet (a group message that arrives
     * before its invitation), so the sender keeps retrying instead of getting a delivery ACK.
     */
    private suspend fun acceptContent(src: String, pktIdHex: String, c: Content, ts: Long): Boolean {
        when (c.kind) {
            ContentKind.DELETE -> {
                store.deleteMessageFrom(c.targetId, src)
                return true
            }
            ContentKind.GROUP_INVITE -> {
                val old = store.getGroup(c.groupId)
                if (src !in c.members) return true
                if (old != null && old.creator != src) return true          // only the creator may change a group
                if (myId !in c.members) {
                    if (old != null) {                                       // the creator removed me
                        store.deleteGroup(c.groupId)
                        _events.tryEmit(MeshEvent.GroupGone(c.groupId, old.name, deleted = false))
                    }
                    return true
                }
                store.saveGroup(GroupRecord(c.groupId, c.text, old?.creator ?: src, c.members, old?.createdAt ?: ts))
                if (old == null) _events.tryEmit(MeshEvent.PrivateReceived(c.groupId, "You were added to group ${c.text}"))
                return true
            }
            ContentKind.GROUP_LEAVE -> {
                val g = store.getGroup(c.groupId) ?: return true
                if (src !in g.members) return true
                if (src == g.creator) {                                       // the creator left = group deleted
                    store.deleteGroup(g.id)
                    _events.tryEmit(MeshEvent.GroupGone(g.id, g.name, deleted = true))
                } else store.saveGroup(g.copy(members = g.members - src))
                return true
            }
            else -> Unit
        }
        if (c.ptt && !pttAllowed()) return true            // push-to-talk switched off on this phone
        var chat = src
        var sender = src
        var id = pktIdHex
        var preview = c.preview()
        if (c.groupId.isNotEmpty()) {
            val g = store.getGroup(c.groupId) ?: return false
            if (myId !in g.members || src !in g.members) return true
            chat = c.groupId
            id = c.logicalId.ifEmpty { pktIdHex }
            preview = (store.getNode(src)?.name?.ifBlank { null } ?: NodeIds.display(src)) + ": " + preview
        }
        store.saveMessage(chat, id, false, c, ts, MessageStatus.RECEIVED, sender)
        _stats.update { it.copy(received = it.received + 1) }
        _events.tryEmit(MeshEvent.PrivateReceived(chat, preview, id, c.ptt))
        return true
    }

    /** True when a route to [peerId] exists right now (a call or transfer needs a live path for its signalling). */
    fun isReachable(peerId: String): Boolean =
        routingTable.lookup(peerId, clock())?.nextHop?.let { it in transport.linkedPeers() } == true

    /**
     * Sends one encrypted session signal. These are real-time: they are NEVER parked in the pending table (a ring
     * delivered ten minutes late is worse than none). Returns false when there was no route or the write failed.
     */
    suspend fun sendCallSignal(peerId: String, signal: CallSignal): Boolean {
        if (peerId == myId) return false
        val peerKey = store.getNode(peerId)?.publicKey ?: return false
        val plain = CallSignalCodec.encode(signal) ?: return false
        val id = newMsgId()
        val blob = CryptoService.encrypt(identity, peerKey, peerId, CryptoService.aad(myId, peerId, id), plain)
        val pkt = MeshPacket(PacketType.CALL, 0, Protocol.MAX_HOPS, 0, id, myId, peerId, clock(), blob)
        dup.checkAndAdd(pkt.key)
        val r = routingTable.lookup(peerId, clock()) ?: return false
        if (r.nextHop !in transport.linkedPeers()) return false
        val ok = transport.send(r.nextHop, pkt.type.channel, pkt.encode())
        if (ok) _stats.update { it.copy(sent = it.sent + 1) }
        return ok
    }

    /** The AES key for this Wi-Fi session (see [CryptoService.callKey]). Null when we do not know the peer's key. */
    suspend fun callKey(peerId: String, callId: ByteArray): ByteArray? {
        val peerKey = store.getNode(peerId)?.publicKey ?: return null
        return CryptoService.callKey(identity, peerKey, peerId, callId)
    }

    suspend fun setBlocked(nodeId: String, value: Boolean) {
        if (value) blocked.add(nodeId) else blocked.remove(nodeId)
        store.setNodeFlags(nodeId, value, nodeId in muted)
    }

    suspend fun setMuted(nodeId: String, value: Boolean) {
        if (value) muted.add(nodeId) else muted.remove(nodeId)
        store.setNodeFlags(nodeId, nodeId in blocked, value)
    }

    /** Immediately re-announce identity and exchange inventories (e.g. user pulled to refresh). */
    fun syncNow() {
        scope.launch {
            broadcastIdentity()
            for (p in transport.linkedPeers()) sendInventory(p)
        }
    }

    // ------------------------------------------------------------------ loops

    private suspend fun housekeepingLoop() {
        var lastHello = 0L
        var lastIdentity = 0L
        var lastSync = 0L
        var lastPurge = clock()
        while (scope.isActive) {
            delay(config.tickMs)
            val now = clock()
            neighborTable.expire(now)
            routingTable.expire(now)
            publish(now)
            try {
                if (now - lastHello >= config.helloIntervalMs(mode)) {
                    lastHello = now
                    for (p in transport.linkedPeers()) scope.launch { sendHello(p) }
                }
                if (now - lastIdentity >= config.identityIntervalMs) {
                    lastIdentity = now
                    broadcastIdentity()
                }
                if (now - lastSync >= config.syncIntervalMs(mode)) {
                    lastSync = now
                    for (p in transport.linkedPeers()) scope.launch { sendInventory(p) }
                }
                checkIncoming()
                if (now - lastPurge >= config.purgeIntervalMs) {
                    lastPurge = now
                    store.purgeExpired(now)
                    synchronized(lastReAck) { lastReAck.entries.removeIf { now - it.value > 60_000 } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // housekeeping must never die
            }
        }
    }

    private suspend fun pendingLoop() {
        while (scope.isActive) {
            delay(config.pendingTickMs)
            try {
                flushQueues()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep looping
            }
        }
    }

    private fun publish(now: Long = clock()) {
        _neighbors.value = neighborTable.snapshot()
        _routes.value = routingTable.snapshot(now)
    }

    // ------------------------------------------------------------------ transport events

    private suspend fun handleEvent(ev: TransportEvent) {
        try {
            when (ev) {
                is TransportEvent.PeerSeen -> {
                    if (ev.peerId == myId || ev.peerId in blocked) return
                    if (loggedPeers.add(ev.peerId)) MeshLog.log("peer ${ev.peerId.take(8)} first seen rssi=${ev.rssi}")
                    neighborTable.seen(ev.peerId, ev.rssi, ev.caps, clock())
                    if (ev.peerId !in transport.linkedPeers()) transport.connectTo(ev.peerId)
                }
                is TransportEvent.LinkUp -> onLinkUp(ev.peerId)
                is TransportEvent.LinkDown -> {
                    MeshLog.log("engine: link DOWN ${ev.peerId.take(8)}")
                    neighborTable.setLinked(ev.peerId, false, clock())
                    routingTable.removeVia(ev.peerId)
                    publish()
                }
                is TransportEvent.Frame -> onFrame(ev.peerId, ev.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            drop("exception: ${e.javaClass.simpleName}")
        }
    }

    private fun onLinkUp(peer: String) {
        MeshLog.log("engine: link UP ${peer.take(8)}")
        val now = clock()
        neighborTable.setLinked(peer, true, now)
        routingTable.update(peer, peer, 1, now)
        publish(now)
        scope.launch {
            sendHello(peer)
            sendIdentityTo(peer)
            sendInventory(peer)
            flushQueues()
        }
    }

    private suspend fun onFrame(from: String, data: ByteArray) {
        val pkt = MeshPacket.decode(data)
        if (pkt == null) {
            drop("malformed packet")
            return
        }
        if (pkt.src == myId) return
        if (pkt.src in blocked) {
            drop("blocked source")
            return
        }
        neighborTable.touch(from, clock())
        when (pkt.type) {
            PacketType.HELLO -> onHello(from, pkt)
            PacketType.SYNC_INV -> onInventory(from, pkt)
            PacketType.SYNC_WANT -> onWant(from, pkt)
            else -> onRoutable(from, pkt)
        }
    }

    // ------------------------------------------------------------------ link-local protocol

    private suspend fun onHello(from: String, pkt: MeshPacket) {
        if (pkt.src != from) {
            drop("hello src mismatch")
            return
        }
        val h = Payloads.decodeHello(pkt.payload)
        if (h == null) {
            drop("bad hello")
            return
        }
        val now = clock()
        store.upsertNodeName(pkt.src, h.name, now)
        var changed = routingTable.update(pkt.src, pkt.src, 1, now)
        for (ad in h.routes) {
            if (ad.nodeId == myId || ad.nodeId == pkt.src || !NodeIds.isValid(ad.nodeId)) continue
            val hops = ad.hops + 1
            if (hops > Protocol.MAX_HOPS) continue
            if (routingTable.update(ad.nodeId, pkt.src, hops, now)) changed = true
        }
        publish(now)
        if (changed) scope.launch { flushQueues() }
    }

    private suspend fun sendHello(peer: String) {
        val ads = routingTable.advertisementsFor(peer, clock(), Protocol.MAX_ROUTE_ADS)
        val payload = Payloads.encodeHello(Payloads.Hello(nameProvider(), Cap.ALL, ads))
        val pkt = newPacket(PacketType.HELLO, peer, payload, 1, sign = false)
        transport.send(peer, pkt.type.channel, pkt.encode())
    }

    private suspend fun sendInventory(peer: String) {
        val refs = store.recentPostRefs(config.inventoryLimit)
        if (refs.isEmpty()) return
        val pkt = newPacket(PacketType.SYNC_INV, peer, Payloads.encodeRefs(refs), 1, sign = false)
        transport.send(peer, pkt.type.channel, pkt.encode())
    }

    private suspend fun onInventory(from: String, pkt: MeshPacket) {
        if (pkt.src != from) return
        val refs = Payloads.decodeRefs(pkt.payload)
        if (refs == null) {
            drop("bad inventory")
            return
        }
        val wanted = ArrayList<PacketRef>()
        for (r in refs) {
            if (wanted.size >= config.wantLimit) break
            if (r.src == myId || r.src in blocked || r.src in muted) continue
            if (!store.hasPost(r.key)) wanted.add(r)
        }
        if (wanted.isEmpty()) return
        val want = newPacket(PacketType.SYNC_WANT, from, Payloads.encodeRefs(wanted), 1, sign = false)
        transport.send(from, want.type.channel, want.encode())
    }

    private suspend fun onWant(from: String, pkt: MeshPacket) {
        if (pkt.src != from) return
        val refs = Payloads.decodeRefs(pkt.payload)
        if (refs == null) {
            drop("bad want")
            return
        }
        for (r in refs.take(config.wantServeLimit)) {
            val raw = store.getPostRaw(r.key) ?: continue
            val original = MeshPacket.decode(raw) ?: continue
            // ttl=1: the receiver stores it but does not flood it again; it will sync onward with its own neighbors.
            val out = original.withTtl(1)
            if (transport.send(from, out.type.channel, out.encode())) {
                _stats.update { it.copy(synced = it.synced + 1) }
            }
        }
    }

    // ------------------------------------------------------------------ flooded / routed protocol

    private suspend fun onRoutable(from: String, pkt: MeshPacket) {
        if (dup.checkAndAdd(pkt.key)) {
            if (pkt.type == PacketType.PRIVATE && pkt.dst == myId) {
                // Our ACK probably got lost. Re-ACK so the sender stops retrying; never re-deliver to the UI.
                sendAck(pkt.src, pkt.msgId)
            } else {
                drop("duplicate")
            }
            return
        }
        when (pkt.type) {
            PacketType.IDENTITY -> onIdentity(from, pkt)
            PacketType.ANNOUNCE -> onAnnounce(from, pkt)
            PacketType.PRIVATE -> onPrivate(from, pkt)
            PacketType.ACK -> onAck(from, pkt)
            PacketType.MEDIA -> onMedia(from, pkt)
            PacketType.MEDIA_NACK -> onNack(from, pkt)
            PacketType.CALL -> onCall(from, pkt)
            PacketType.SOS -> onSos(from, pkt)
            else -> drop("unexpected type")
        }
    }

    private suspend fun onIdentity(from: String, pkt: MeshPacket) {
        val id = Payloads.decodeIdentity(pkt.payload)
        val sig = pkt.signature
        if (id == null || sig == null) {
            drop("bad identity")
            return
        }
        if (NodeIds.fromPublicKey(id.publicKey) != pkt.src) {
            drop("identity: id/key mismatch")
            return
        }
        if (!CryptoService.verify(id.publicKey, pkt.signedBytes(), sig)) {
            drop("identity: bad signature")
            return
        }
        val existingKey = store.getNode(pkt.src)?.publicKey
        if (existingKey != null && !existingKey.contentEquals(id.publicKey)) {
            drop("identity: key changed (pinned)")
            return
        }
        store.upsertNodeIdentity(pkt.src, id.name, id.publicKey, clock(), id.email)
        forwardBroadcast(pkt, from)
    }

    private suspend fun onAnnounce(from: String, pkt: MeshPacket) {
        val a = Payloads.decodeAnnounce(pkt.payload)
        if (a == null || a.content.isBlank() || a.content.length > Protocol.MAX_POST_CHARS || !pkt.isBroadcast) {
            drop("bad announce")
            return
        }
        val sig = pkt.signature
        if (sig == null) {
            drop("unsigned announce")
            return
        }
        if (!postLimiter.allow(pkt.src)) {
            drop("spam: rate limit")
            return
        }
        val key = store.getNode(pkt.src)?.publicKey
        var verified = false
        if (key != null) {
            if (!CryptoService.verify(key, pkt.signedBytes(), sig)) {
                drop("announce: bad signature")
                return
            }
            verified = true
        }
        if (pkt.src !in muted) {
            val now = clock()
            val saved = store.savePost(
                PostRecord(pkt.key, pkt.src, a.authorName, a.content, minOf(pkt.timestamp, now), now, verified, pkt.encode()),
            )
            if (saved) {
                _stats.update { it.copy(received = it.received + 1) }
                _events.tryEmit(MeshEvent.PostReceived(a.authorName, a.content))
            }
        }
        forwardBroadcast(pkt, from)
    }

    private suspend fun onPrivate(from: String, pkt: MeshPacket) {
        if (pkt.dst != myId) {
            forwardUnicast(pkt, from)
            return
        }
        val senderKey = store.getNode(pkt.src)?.publicKey
        if (senderKey == null) {
            // Allow a later retry to be processed once the sender's IDENTITY has arrived.
            dup.forget(pkt.key)
            drop("private: sender key unknown yet")
            return
        }
        val plain = CryptoService.decrypt(identity, senderKey, pkt.src, CryptoService.aad(pkt.src, pkt.dst, pkt.msgId), pkt.payload)
        if (plain == null) {
            drop("private: decrypt failed")
            return
        }
        val content = ContentCodec.decode(plain)
        if (content == null || content.isMedia) {          // image/voice must arrive through the chunked path
            drop("private: bad content")
            return
        }
        val now = clock()
        if (!acceptContent(pkt.src, pkt.msgIdHex, content, minOf(pkt.timestamp, now))) {
            dup.forget(pkt.key)                 // e.g. group invitation not here yet: let the retry through later
            drop("private: group unknown yet")
            return
        }
        sendAck(pkt.src, pkt.msgId)
    }

    private suspend fun onCall(from: String, pkt: MeshPacket) {
        if (pkt.dst != myId) {
            forwardNoQueue(pkt, from)          // relays pass session signals on only while a route exists; never stored
            return
        }
        val senderKey = store.getNode(pkt.src)?.publicKey
        if (senderKey == null) {
            dup.forget(pkt.key)                // the next retry may arrive after the sender's IDENTITY
            drop("call: sender key unknown yet")
            return
        }
        val plain = CryptoService.decrypt(identity, senderKey, pkt.src, CryptoService.aad(pkt.src, pkt.dst, pkt.msgId), pkt.payload)
        val signal = plain?.let { CallSignalCodec.decode(it) }
        if (signal == null) {
            drop("call: bad signal")
            return
        }
        _events.tryEmit(MeshEvent.CallSignalReceived(pkt.src, signal))
    }

    private suspend fun sendAck(to: String, ackedId: ByteArray) {
        val ack = newPacket(PacketType.ACK, to, ackedId, Protocol.MAX_HOPS, sign = false)
        dup.checkAndAdd(ack.key)
        routeOrQueue(ack, from = null)
    }

    private suspend fun onAck(from: String, pkt: MeshPacket) {
        if (pkt.dst != myId) {
            forwardUnicast(pkt, from)
            return
        }
        if (pkt.payload.size != 8) {
            drop("bad ack")
            return
        }
        val acked = Hex.encode(pkt.payload)
        store.deletePending("$myId:$acked")
        store.deleteOutTransfer(acked)
        store.updateMessageStatus(acked, MessageStatus.DELIVERED)
        setProgress(acked, null)
        _stats.update { it.copy(delivered = it.delivered + 1) }
        _events.tryEmit(MeshEvent.Delivered(acked))
    }

    // ------------------------------------------------------------------ media: receiving

    private suspend fun onMedia(from: String, pkt: MeshPacket) {
        if (pkt.dst != myId) {
            forwardNoQueue(pkt, from)          // relays never store media for someone else: the sender keeps the blob
            return
        }
        val ch = MediaPayloads.decodeChunk(pkt.payload)
        if (ch == null || ch.total <= 0 || ch.total > MediaLimits.MAX_CHUNKS || ch.index >= ch.total) {
            drop("bad media chunk")
            return
        }
        val key = "${pkt.src}:${ch.idHex}"
        val now = clock()
        if (synchronized(completedIn) { completedIn.containsKey(key) }) {
            reAck(pkt.src, ch.idHex, now)      // sender never saw our ACK
            return
        }
        val senderKey = store.getNode(pkt.src)?.publicKey
        if (senderKey == null) {
            drop("media: sender key unknown yet")
            return
        }
        var reject: String? = null
        var finished: InTransfer? = null
        synchronized(incoming) {
            val t = incoming[key]
                ?: if (incoming.size >= config.maxIncoming) null else InTransfer(ch.total, now).also { incoming[key] = it }
            if (t == null) {
                reject = "media: too many transfers"
            } else if (t.total != ch.total) {
                reject = "media: inconsistent chunk"
            } else {
                if (t.chunks[ch.index] == null) {
                    t.chunks[ch.index] = ch.data
                    t.count++
                }
                t.lastChunkAt = now
                if (t.count == t.total) {
                    incoming.remove(key)
                    finished = t
                }
            }
        }
        reject?.let {
            drop(it)
            return
        }
        finished?.let { finishIncoming(pkt.src, ch.idHex, key, it, senderKey) }
    }

    private suspend fun finishIncoming(src: String, idHex: String, key: String, t: InTransfer, senderKey: ByteArray) {
        val out = ByteArrayOutputStream()
        for (c in t.chunks) out.write(c!!)
        val idBytes = Hex.decode(idHex)
        MeshLog.log("media ${idHex.take(6)} all ${t.total} chunks received from ${src.take(8)}")
        val plain = CryptoService.decrypt(identity, senderKey, src, CryptoService.aad(src, myId, idBytes), out.toByteArray())
        if (plain == null) {
            drop("media: decrypt failed")      // corrupt or forged: no ACK, the sender will retry the whole transfer
            return
        }
        val content = ContentCodec.decode(plain)
        if (content == null || !content.isMedia) {
            drop("media: bad content")
            return
        }
        val now = clock()
        if (!acceptContent(src, idHex, content, now)) {
            drop("media: group unknown yet")
            return
        }
        synchronized(completedIn) { completedIn[key] = now }
        sendAck(src, idBytes)
    }

    private suspend fun reAck(src: String, idHex: String, now: Long) {
        val key = "$src:$idHex"
        val go = synchronized(lastReAck) {
            val last = lastReAck[key] ?: 0L
            if (now - last >= 3_000) {
                lastReAck[key] = now
                true
            } else false
        }
        if (go) sendAck(src, Hex.decode(idHex))
    }

    /** Receiver side: transfers that stalled ask the sender for exactly the chunks that are missing. */
    private suspend fun checkIncoming() {
        val now = clock()
        class Nack(val src: String, val idHex: String, val missing: List<Int>)

        val list = ArrayList<Nack>()
        synchronized(incoming) {
            val it = incoming.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val t = e.value
                if (now - t.startedAt > config.incomingTimeoutMs) {
                    it.remove()
                    continue
                }
                if (now - t.lastChunkAt >= config.nackIdleMs && now - t.lastNackAt >= config.nackIdleMs) {
                    t.lastNackAt = now
                    val missing = (0 until t.total).filter { i -> t.chunks[i] == null }
                    list.add(Nack(e.key.substringBefore(':'), e.key.substringAfter(':'), missing))
                }
            }
        }
        for (n in list) {
            MeshLog.log("media ${n.idHex.take(6)} stalled, NACK ${n.missing.size} missing chunks to ${n.src.take(8)}")
            val pkt = newPacket(PacketType.MEDIA_NACK, n.src, MediaPayloads.encodeNack(Hex.decode(n.idHex), n.missing), Protocol.MAX_HOPS, sign = false)
            dup.checkAndAdd(pkt.key)
            routeOrQueue(pkt, from = null, queue = false)
        }
    }

    // ------------------------------------------------------------------ media: sending

    private suspend fun onNack(from: String, pkt: MeshPacket) {
        if (pkt.dst != myId) {
            forwardNoQueue(pkt, from)
            return
        }
        val n = MediaPayloads.decodeNack(pkt.payload)
        if (n == null) {
            drop("bad nack")
            return
        }
        val info = store.allOutTransfers().firstOrNull { it.idHex == n.idHex && it.dst == pkt.src } ?: return
        if (!mediaInFlight.add(info.idHex)) return      // still sending: the NACK is stale
        scope.launch {
            try {
                mediaMutex.withLock { sendTransfer(info, n.missing) }
            } finally {
                mediaInFlight.remove(info.idHex)
            }
        }
    }

    /** Starts every outgoing transfer whose recipient is currently reachable and whose retry timer allows it. */
    private suspend fun processTransfers() {
        val now = clock()
        for (t in store.allOutTransfers()) {
            if (t.expiresAt <= now || t.attempts >= config.maxMediaAttempts) {
                if (t.idHex !in mediaInFlight) {
                    store.deleteOutTransfer(t.idHex)
                    store.updateMessageStatus(t.idHex, MessageStatus.FAILED)
                    setProgress(t.idHex, null)
                }
                continue
            }
            if (t.idHex in mediaInFlight) continue
            val route = routingTable.lookup(t.dst, now) ?: continue
            if (route.nextHop !in transport.linkedPeers()) continue
            val wait = if (t.attempts == 0) {
                if (t.lastAttempt == 0L) 0L else 5_000L
            } else config.mediaRetryBackoffMs[minOf(t.attempts - 1, config.mediaRetryBackoffMs.size - 1)]
            if (now - t.lastAttempt < wait) continue
            if (!mediaInFlight.add(t.idHex)) continue
            scope.launch {
                try {
                    mediaMutex.withLock { sendTransfer(t, null) }
                } finally {
                    mediaInFlight.remove(t.idHex)
                }
            }
        }
    }

    /** Sends all chunks (or only [only], after a NACK) toward the next hop. Back-pressure comes from transport.send. */
    private suspend fun sendTransfer(info: OutTransferInfo, only: List<Int>?) {
        val blob = store.getOutBlob(info.idHex) ?: return
        MeshLog.log("media ${info.idHex.take(6)} send start to ${info.dst.take(8)} chunks=${only?.size ?: info.total}/${info.total} attempt=${info.attempts}")
        val idBytes = Hex.decode(info.idHex)
        val indices = only?.filter { it in 0 until info.total }?.distinct() ?: (0 until info.total).toList()
        var complete = true
        for ((n, i) in indices.withIndex()) {
            val now = clock()
            val route = routingTable.lookup(info.dst, now)
            if (route == null || route.nextHop !in transport.linkedPeers()) {
                complete = false
                break
            }
            val from = i * MediaLimits.CHUNK
            val to = minOf(blob.size, from + MediaLimits.CHUNK)
            val payload = MediaPayloads.encodeChunk(idBytes, i, info.total, blob.copyOfRange(from, to))
            // A fresh packet id for every (re)transmission, otherwise relays would drop a retransmitted chunk as duplicate.
            val pkt = MeshPacket(PacketType.MEDIA, 0, Protocol.MAX_HOPS, 0, newMsgId(), myId, info.dst, now, payload)
            dup.checkAndAdd(pkt.key)
            if (!transport.send(route.nextHop, pkt.type.channel, pkt.encode())) {
                MeshLog.log("media ${info.idHex.take(6)} chunk $i/${info.total} write FAILED via ${route.nextHop.take(8)}")
                complete = false
                break
            }
            if (n % 4 == 0) setProgress(info.idHex, (n + 1).toFloat() / indices.size)
        }
        setProgress(info.idHex, null)
        MeshLog.log("media ${info.idHex.take(6)} send ${if (complete) "done (waiting for ACK)" else "INTERRUPTED, will retry"}")
        val now = clock()
        if (only == null && complete) {
            store.updateOutTransfer(info.idHex, info.attempts + 1, now)
            if (info.attempts == 0) {
                store.markSent(info.idHex)
                _stats.update { it.copy(sent = it.sent + 1) }
            }
        } else {
            store.updateOutTransfer(info.idHex, info.attempts, now)
        }
    }

    private fun setProgress(id: String, fraction: Float?) {
        _mediaProgress.update { m -> if (fraction == null) m - id else m + (id to fraction) }
    }

    // ------------------------------------------------------------------ forwarding

    private fun forwardBroadcast(pkt: MeshPacket, from: String) {
        if (pkt.ttl <= 1) return
        val f = pkt.forwarded()
        val bytes = f.encode()
        var count = 0
        for (p in transport.linkedPeers()) {
            if (p == from || p == pkt.src) continue
            count++
            scope.launch { transport.send(p, f.type.channel, bytes) }
        }
        if (count > 0) _stats.update { it.copy(relayed = it.relayed + 1) }
    }

    private suspend fun forwardUnicast(pkt: MeshPacket, from: String) {
        if (pkt.ttl <= 1) {
            drop("ttl expired")
            return
        }
        routeOrQueue(pkt.forwarded(), from, relayed = true)
    }

    /**
     * Media chunks and NACKs are relayed only when a route exists right now. They are never parked in a relay's
     * pending table: a relay cannot hold hundreds of KB for strangers, and the sender keeps the blob and retries.
     * Each hop is sent from its own coroutine; per-link mutexes keep chunk order.
     */
    private fun forwardNoQueue(pkt: MeshPacket, from: String) {
        if (pkt.ttl <= 1) {
            drop("ttl expired")
            return
        }
        val f = pkt.forwarded()
        val r = routingTable.lookup(f.dst, clock())
        if (r == null || r.nextHop == from || r.nextHop !in transport.linkedPeers()) {
            drop("media: no route")
            return
        }
        val bytes = f.encode()
        scope.launch {
            if (transport.send(r.nextHop, f.type.channel, bytes)) _stats.update { it.copy(relayed = it.relayed + 1) }
        }
    }

    /**
     * Sends [pkt] (already prepared: TTL/hops final) toward its destination, or — when [queue] is true — parks it in
     * the pending table (store-and-forward) when there is no usable route yet.
     */
    private suspend fun routeOrQueue(pkt: MeshPacket, from: String?, relayed: Boolean = false, queue: Boolean = true) {
        val now = clock()
        val r = routingTable.lookup(pkt.dst, now)
        if (r != null && r.nextHop != from && r.nextHop in transport.linkedPeers()) {
            if (transport.send(r.nextHop, pkt.type.channel, pkt.encode())) {
                if (relayed) _stats.update { it.copy(relayed = it.relayed + 1) }
                return
            }
        }
        if (queue) store.addPending(PendingRecord(pkt.key, pkt.dst, pkt.encode(), false, now, now + config.pendingTtlMs, 0, 0))
        else drop("no route")
    }

    private suspend fun flushQueues() {
        processPending()
        processTransfers()
    }

    /** Retry/flush loop for the pending table (text, location, ACKs, relayed small packets). Serialized by [pendingMutex]. */
    private suspend fun processPending() {
        pendingMutex.withLock {
            val now = clock()
            for (rec in store.allPending()) {
                if (rec.expiresAt <= now) {
                    store.deletePending(rec.key)
                    if (rec.originated) store.updateMessageStatus(rec.key.substringAfter(':'), MessageStatus.FAILED)
                    continue
                }
                val route = routingTable.lookup(rec.dst, now) ?: continue
                if (route.nextHop !in transport.linkedPeers()) continue
                if (rec.attempts > 0) {
                    val wait = config.retryBackoffMs[minOf(rec.attempts - 1, config.retryBackoffMs.size - 1)]
                    if (now - rec.lastAttempt < wait) continue
                    if (rec.attempts >= config.maxAttempts) {
                        store.deletePending(rec.key)
                        if (rec.originated) store.updateMessageStatus(rec.key.substringAfter(':'), MessageStatus.FAILED)
                        continue
                    }
                }
                val pkt = MeshPacket.decode(rec.raw)
                if (pkt == null) {
                    store.deletePending(rec.key)
                    continue
                }
                val ok = transport.send(route.nextHop, pkt.type.channel, rec.raw)
                if (!ok) continue
                if (rec.originated) {
                    store.updatePending(rec.key, rec.attempts + 1, now)
                    if (rec.attempts == 0) {
                        store.markSent(rec.key.substringAfter(':'))
                        _stats.update { it.copy(sent = it.sent + 1) }
                    }
                } else {
                    store.deletePending(rec.key)
                    _stats.update { it.copy(relayed = it.relayed + 1) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ identity

    /** Sends our identity (name + e-mail) again, e.g. after the profile changed. */
    suspend fun refreshIdentity() = broadcastIdentity()

    private suspend fun broadcastIdentity() {
        val pkt = identityPacket(Protocol.DEFAULT_TTL)
        dup.checkAndAdd(pkt.key)
        val bytes = pkt.encode()
        for (p in transport.linkedPeers()) scope.launch { transport.send(p, pkt.type.channel, bytes) }
    }

    private suspend fun sendIdentityTo(peer: String) {
        val pkt = identityPacket(1)
        transport.send(peer, pkt.type.channel, pkt.encode())
    }

    private fun identityPacket(ttl: Int): MeshPacket =
        newPacket(
            PacketType.IDENTITY, NodeIds.BROADCAST,
            Payloads.encodeIdentity(Payloads.Identity(Cap.ALL, nameProvider(), identity.publicBytes, emailProvider())),
            ttl, sign = true,
        )

    // ------------------------------------------------------------------ helpers

    private fun newMsgId(): ByteArray = ByteArray(8).also { rng.nextBytes(it) }

    private fun newPacket(type: PacketType, dst: String, payload: ByteArray, ttl: Int, sign: Boolean): MeshPacket {
        val p = MeshPacket(type, 0, ttl, 0, newMsgId(), myId, dst, clock(), payload, null)
        return if (sign) p.withSignature(CryptoService.sign(identity, p.signedBytes())) else p
    }

    private fun drop(reason: String) {
        MeshLog.log("drop: $reason")
        _stats.update { it.copy(dropped = it.dropped + 1, lastDrop = reason) }
    }
}
