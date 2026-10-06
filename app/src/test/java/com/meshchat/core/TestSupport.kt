package com.meshchat.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** In-memory MeshStore for JVM tests. */
class InMemoryStore : MeshStore {
    val nodes = ConcurrentHashMap<String, NodeRecord>()
    val posts = ConcurrentHashMap<String, PostRecord>()
    val pending = ConcurrentHashMap<String, PendingRecord>()
    val outTransfers = ConcurrentHashMap<String, Pair<OutTransferInfo, ByteArray>>()

    class Msg(val peerId: String, val msgId: String, val outgoing: Boolean, val content: Content, var status: MessageStatus) {
        val text: String get() = content.text
    }

    val messages = ConcurrentHashMap<String, Msg>()

    override suspend fun getNode(nodeId: String) = nodes[nodeId]

    override suspend fun upsertNodeName(nodeId: String, name: String, seen: Long) {
        val o = nodes[nodeId]
        nodes[nodeId] = NodeRecord(nodeId, name, o?.publicKey, seen, o?.blocked ?: false, o?.muted ?: false)
    }

    override suspend fun upsertNodeIdentity(nodeId: String, name: String, publicKey: ByteArray, seen: Long) {
        val o = nodes[nodeId]
        nodes[nodeId] = NodeRecord(nodeId, name, publicKey, seen, o?.blocked ?: false, o?.muted ?: false)
    }

    override suspend fun setNodeFlags(nodeId: String, blocked: Boolean, muted: Boolean) {
        val o = nodes[nodeId] ?: NodeRecord(nodeId, "", null, 0)
        nodes[nodeId] = NodeRecord(nodeId, o.name, o.publicKey, o.lastSeen, blocked, muted)
    }

    override suspend fun blockedIds() = nodes.values.filter { it.blocked }.map { it.nodeId }.toSet()
    override suspend fun mutedIds() = nodes.values.filter { it.muted }.map { it.nodeId }.toSet()

    override suspend fun saveMessage(peerId: String, msgIdHex: String, outgoing: Boolean, content: Content, timestamp: Long, status: MessageStatus) {
        messages[msgIdHex] = Msg(peerId, msgIdHex, outgoing, content, status)
    }

    override suspend fun updateMessageStatus(msgIdHex: String, status: MessageStatus) {
        messages[msgIdHex]?.status = status
    }

    override suspend fun markSent(msgIdHex: String) {
        messages[msgIdHex]?.let { if (it.status == MessageStatus.QUEUED) it.status = MessageStatus.SENT }
    }

    override suspend fun savePost(post: PostRecord): Boolean = posts.putIfAbsent(post.key, post) == null
    override suspend fun hasPost(key: String) = posts.containsKey(key)
    override suspend fun getPostRaw(key: String) = posts[key]?.raw
    override suspend fun recentPostRefs(limit: Int) =
        posts.values.sortedByDescending { it.timestamp }.take(limit).map { PacketRef(it.authorId, it.key.substringAfter(':')) }

    override suspend fun addPending(rec: PendingRecord) {
        pending[rec.key] = rec
    }

    override suspend fun allPending() = pending.values.sortedBy { it.createdAt }

    override suspend fun updatePending(key: String, attempts: Int, lastAttempt: Long) {
        val o = pending[key] ?: return
        pending[key] = PendingRecord(o.key, o.dst, o.raw, o.originated, o.createdAt, o.expiresAt, attempts, lastAttempt)
    }

    override suspend fun deletePending(key: String) {
        pending.remove(key)
    }

    override suspend fun addOutTransfer(info: OutTransferInfo, blob: ByteArray) {
        outTransfers[info.idHex] = info to blob
    }

    override suspend fun allOutTransfers() = outTransfers.values.map { it.first }.sortedBy { it.createdAt }
    override suspend fun getOutBlob(idHex: String) = outTransfers[idHex]?.second

    override suspend fun updateOutTransfer(idHex: String, attempts: Int, lastAttempt: Long) {
        val o = outTransfers[idHex] ?: return
        outTransfers[idHex] = o.first.copy(attempts = attempts, lastAttempt = lastAttempt) to o.second
    }

    override suspend fun deleteOutTransfer(idHex: String) {
        outTransfers.remove(idHex)
    }

    override suspend fun purgeExpired(now: Long) {
        pending.values.filter { it.expiresAt <= now }.forEach { pending.remove(it.key) }
    }
}

/** A tiny in-memory "radio": links between FakeTransports deliver frames instantly. */
class FakeNet {
    val nodes = ConcurrentHashMap<String, FakeTransport>()

    fun link(a: String, b: String) {
        nodes[a]!!.up(b)
        nodes[b]!!.up(a)
    }

    fun unlink(a: String, b: String) {
        nodes[a]!!.down(b)
        nodes[b]!!.down(a)
    }
}

class FakeTransport(val id: String, private val net: FakeNet) : MeshTransport {
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 4096)
    override val events: SharedFlow<TransportEvent> = _events
    override val status: StateFlow<TransportStatus> = MutableStateFlow(TransportStatus(true, true, true))
    private val links = ConcurrentHashMap.newKeySet<String>()

    /** Tests: return true to silently lose a packet that this node sends. */
    @Volatile
    var dropHook: ((peerId: String, data: ByteArray) -> Boolean)? = null

    init {
        net.nodes[id] = this
    }

    override fun start() {}
    override fun stop() {}
    override fun linkedPeers(): Set<String> = links.toSet()
    override fun connectTo(peerId: String) {}
    override fun setDiscoveryMode(mode: DiscoveryMode) {}

    override suspend fun send(peerId: String, channel: Channel, data: ByteArray): Boolean {
        if (peerId !in links) return false
        val target = net.nodes[peerId] ?: return false
        if (id !in target.links) return false
        if (dropHook?.invoke(peerId, data) == true) return true
        // Exercise the real fragmentation path on every hop.
        val frames = Fragmenter().split(data, 20)
        val re = Reassembler()
        var out: ByteArray? = null
        for (f in frames) out = re.accept(f) ?: out
        target._events.emit(TransportEvent.Frame(id, channel, out!!))
        return true
    }

    fun up(peer: String) {
        links.add(peer)
        _events.tryEmit(TransportEvent.LinkUp(peer, true))
    }

    fun down(peer: String) {
        links.remove(peer)
        _events.tryEmit(TransportEvent.LinkDown(peer))
    }
}
