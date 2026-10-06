package com.meshchat.data

import androidx.room.withTransaction
import com.meshchat.core.Content
import com.meshchat.core.MeshStore
import com.meshchat.core.MessageStatus
import com.meshchat.core.NodeRecord
import com.meshchat.core.OutTransferInfo
import com.meshchat.core.PacketRef
import com.meshchat.core.PendingRecord
import com.meshchat.core.PostRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Room-backed implementation of the engine's persistence interface. Images/voice are saved as files in [mediaDir]. */
class RoomMeshStore(private val db: MeshDatabase, private val mediaDir: File) : MeshStore {
    private val nodes = db.nodeDao()
    private val chats = db.chatDao()
    private val messages = db.messageDao()
    private val posts = db.postDao()
    private val pending = db.pendingDao()
    private val mediaOut = db.mediaOutDao()

    override suspend fun getNode(nodeId: String): NodeRecord? =
        nodes.get(nodeId)?.let { NodeRecord(it.nodeId, it.name, it.publicKey, it.lastSeen, it.blocked, it.muted) }

    override suspend fun upsertNodeName(nodeId: String, name: String, seen: Long) {
        val old = nodes.get(nodeId)
        nodes.upsert(NodeEntity(nodeId, name, old?.publicKey, seen, old?.blocked ?: false, old?.muted ?: false))
    }

    override suspend fun upsertNodeIdentity(nodeId: String, name: String, publicKey: ByteArray, seen: Long) {
        val old = nodes.get(nodeId)
        nodes.upsert(NodeEntity(nodeId, name, publicKey, seen, old?.blocked ?: false, old?.muted ?: false))
    }

    override suspend fun setNodeFlags(nodeId: String, blocked: Boolean, muted: Boolean) {
        val old = nodes.get(nodeId) ?: NodeEntity(nodeId, "", null, 0L, false, false)
        nodes.upsert(old.copy(blocked = blocked, muted = muted))
    }

    override suspend fun blockedIds(): Set<String> = nodes.blockedIds().toSet()
    override suspend fun mutedIds(): Set<String> = nodes.mutedIds().toSet()

    override suspend fun saveMessage(
        peerId: String, msgIdHex: String, outgoing: Boolean, content: Content, timestamp: Long, status: MessageStatus,
    ) {
        var path: String? = null
        val bytes = content.data
        if (bytes != null) {
            path = withContext(Dispatchers.IO) {
                mediaDir.mkdirs()
                val f = File(mediaDir, "$msgIdHex.${content.extension()}")
                f.writeBytes(bytes)
                f.absolutePath
            }
        }
        db.withTransaction {
            val inserted = messages.insert(
                MessageEntity(
                    msgId = msgIdHex, peerId = peerId, outgoing = outgoing, text = content.text, timestamp = timestamp,
                    status = status.name, kind = content.kind.name, mediaPath = path, durationMs = content.durationMs,
                    lat = content.lat, lon = content.lon, accuracyM = content.accuracyM,
                    waveform = content.waveform, codec = content.codec,
                ),
            )
            if (inserted != -1L) {
                val old = chats.get(peerId)
                val unread = (old?.unread ?: 0) + if (outgoing) 0 else 1
                chats.upsert(ChatEntity(peerId, content.preview(), timestamp, unread))
            }
        }
    }

    override suspend fun updateMessageStatus(msgIdHex: String, status: MessageStatus) {
        messages.setStatus(msgIdHex, status.name)
    }

    override suspend fun markSent(msgIdHex: String) = messages.markSent(msgIdHex)

    override suspend fun savePost(post: PostRecord): Boolean =
        posts.insert(
            PublicPostEntity(
                post.key, post.authorId, post.authorName, post.content,
                post.timestamp, post.receivedAt, post.verified, post.raw,
            ),
        ) != -1L

    override suspend fun hasPost(key: String): Boolean = posts.count(key) > 0
    override suspend fun getPostRaw(key: String): ByteArray? = posts.raw(key)

    override suspend fun recentPostRefs(limit: Int): List<PacketRef> =
        posts.recentRefs(limit).map { PacketRef(it.authorId, it.postKey.substringAfter(':')) }

    override suspend fun addPending(rec: PendingRecord) {
        pending.upsert(
            PendingEntity(rec.key, rec.dst, rec.raw, rec.originated, rec.createdAt, rec.expiresAt, rec.attempts, rec.lastAttempt),
        )
        val overflow = pending.count() - MAX_PENDING
        if (overflow > 0) pending.deleteOldest(overflow)       // maximum storage: oldest packets are evicted first
    }

    override suspend fun allPending(): List<PendingRecord> =
        pending.all().map {
            PendingRecord(it.pendingKey, it.dst, it.raw, it.originated, it.createdAt, it.expiresAt, it.attempts, it.lastAttempt)
        }

    override suspend fun updatePending(key: String, attempts: Int, lastAttempt: Long) = pending.update(key, attempts, lastAttempt)
    override suspend fun deletePending(key: String) = pending.delete(key)

    override suspend fun addOutTransfer(info: OutTransferInfo, blob: ByteArray) {
        mediaOut.upsert(
            MediaOutEntity(info.idHex, info.dst, blob, info.total, info.createdAt, info.expiresAt, info.attempts, info.lastAttempt),
        )
    }

    override suspend fun allOutTransfers(): List<OutTransferInfo> =
        mediaOut.infos().map { OutTransferInfo(it.transferId, it.dst, it.totalChunks, it.createdAt, it.expiresAt, it.attempts, it.lastAttempt) }

    override suspend fun getOutBlob(idHex: String): ByteArray? = mediaOut.blob(idHex)
    override suspend fun updateOutTransfer(idHex: String, attempts: Int, lastAttempt: Long) = mediaOut.update(idHex, attempts, lastAttempt)
    override suspend fun deleteOutTransfer(idHex: String) = mediaOut.delete(idHex)

    override suspend fun purgeExpired(now: Long) {
        pending.deleteExpired(now)
        mediaOut.deleteExpired(now)
        posts.deleteOlderThan(now - POST_RETENTION_MS)
        posts.trimToLatest(MAX_POSTS)
    }

    companion object {
        const val MAX_PENDING = 200
        const val MAX_POSTS = 500
        const val POST_RETENTION_MS = 7L * 24 * 3_600_000L
    }
}
