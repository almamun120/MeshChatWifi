package com.meshchat.core

enum class MessageStatus { QUEUED, SENT, DELIVERED, FAILED, RECEIVED }

class NodeRecord(
    val nodeId: String,
    val name: String,
    val publicKey: ByteArray?,
    val lastSeen: Long,
    val blocked: Boolean = false,
    val muted: Boolean = false,
)

class PostRecord(
    val key: String,            // "src:msgId"
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: Long,
    val receivedAt: Long,
    val verified: Boolean,
    val raw: ByteArray,         // original signed packet, kept for sync
)

class PendingRecord(
    val key: String,            // "src:msgId"
    val dst: String,
    val raw: ByteArray,         // encoded packet, ready to send
    val originated: Boolean,    // true = our own message (needs ACK), false = relayed for someone else
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int,
    val lastAttempt: Long,
)

/** Metadata of an outgoing image/voice transfer waiting for its recipient (the encrypted blob is stored separately). */
data class OutTransferInfo(
    val idHex: String,
    val dst: String,
    val total: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int,
    val lastAttempt: Long,
)

/**
 * Persistence used by MeshEngine. Room implements it on Android; tests use an in-memory version.
 */
interface MeshStore {
    suspend fun getNode(nodeId: String): NodeRecord?
    suspend fun upsertNodeName(nodeId: String, name: String, seen: Long)
    suspend fun upsertNodeIdentity(nodeId: String, name: String, publicKey: ByteArray, seen: Long)
    suspend fun setNodeFlags(nodeId: String, blocked: Boolean, muted: Boolean)
    suspend fun blockedIds(): Set<String>
    suspend fun mutedIds(): Set<String>

    /** Saves a private message (text, location, image or voice) in the local chat history. */
    suspend fun saveMessage(peerId: String, msgIdHex: String, outgoing: Boolean, content: Content, timestamp: Long, status: MessageStatus)
    suspend fun updateMessageStatus(msgIdHex: String, status: MessageStatus)

    /** QUEUED -> SENT only; never downgrades DELIVERED/FAILED (an ACK can overtake this call). */
    suspend fun markSent(msgIdHex: String)

    /** @return false when the post already existed. */
    suspend fun savePost(post: PostRecord): Boolean
    suspend fun hasPost(key: String): Boolean
    suspend fun getPostRaw(key: String): ByteArray?
    suspend fun recentPostRefs(limit: Int): List<PacketRef>

    // store-and-forward for text/location (one packet each)
    suspend fun addPending(rec: PendingRecord)
    suspend fun allPending(): List<PendingRecord>
    suspend fun updatePending(key: String, attempts: Int, lastAttempt: Long)
    suspend fun deletePending(key: String)

    // store-and-forward for image/voice (many chunks; kept at the sender until delivery is acknowledged)
    suspend fun addOutTransfer(info: OutTransferInfo, blob: ByteArray)
    suspend fun allOutTransfers(): List<OutTransferInfo>
    suspend fun getOutBlob(idHex: String): ByteArray?
    suspend fun updateOutTransfer(idHex: String, attempts: Int, lastAttempt: Long)
    suspend fun deleteOutTransfer(idHex: String)

    /** Removes expired pending packets/transfers and old posts; enforces storage limits. */
    suspend fun purgeExpired(now: Long)
}
