package com.meshchat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The local user (single row). DOB lives ONLY here — it is never put into any mesh packet. */
@Entity(tableName = "user")
data class UserEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val dobEpochDay: Long,
    val showDob: Boolean,
    val photoPath: String?,
    val nodeId: String,
    val createdAt: Long,
)

/** Every node we have learned about (name + public key come from signed IDENTITY packets). */
@Suppress("ArrayInDataClass")
@Entity(tableName = "node")
data class NodeEntity(
    @PrimaryKey val nodeId: String,
    val name: String,
    val publicKey: ByteArray?,
    val lastSeen: Long,
    val blocked: Boolean,
    val muted: Boolean,
)

/** Private conversation summary (one row per peer). */
@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey val peerId: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unread: Int,
)

/**
 * Private message in the local history. [kind] is TEXT, LOCATION, IMAGE or VOICE.
 * Images and voice notes live as files (mediaPath); the database stores only the path.
 * On the wire everything is ciphertext; plaintext exists only on the two phones.
 */
@Suppress("ArrayInDataClass")
@Entity(tableName = "message", indices = [Index("peerId")])
data class MessageEntity(
    @PrimaryKey val msgId: String,
    val peerId: String,
    val outgoing: Boolean,
    val text: String,
    val timestamp: Long,
    val status: String,
    val kind: String,
    val mediaPath: String?,
    val durationMs: Int,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val waveform: ByteArray,
    val codec: Int,
)

/** Public Announce post. `raw` is the original signed packet so we can serve it during sync. */
@Suppress("ArrayInDataClass")
@Entity(tableName = "public_post", indices = [Index("timestamp")])
data class PublicPostEntity(
    @PrimaryKey val postKey: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: Long,
    val receivedAt: Long,
    val verified: Boolean,
    val raw: ByteArray,
)

/** Outbox + store-and-forward queue for small packets (text, location, ACKs, relayed packets). */
@Suppress("ArrayInDataClass")
@Entity(tableName = "pending_message")
data class PendingEntity(
    @PrimaryKey val pendingKey: String,
    val dst: String,
    val raw: ByteArray,
    val originated: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int,
    val lastAttempt: Long,
)

/** Outbox for image/voice: the encrypted blob waits here until the recipient is reachable and acknowledges it. */
@Suppress("ArrayInDataClass")
@Entity(tableName = "media_out")
data class MediaOutEntity(
    @PrimaryKey val transferId: String,
    val dst: String,
    val blob: ByteArray,
    val totalChunks: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int,
    val lastAttempt: Long,
)

data class PostRefRow(val authorId: String, val postKey: String)

/** Metadata-only view of [MediaOutEntity] (so polling the outbox never loads the blobs). */
data class MediaOutInfoRow(
    val transferId: String,
    val dst: String,
    val totalChunks: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int,
    val lastAttempt: Long,
)
