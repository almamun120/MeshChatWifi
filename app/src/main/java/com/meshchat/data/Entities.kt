package com.meshchat.data

import androidx.room.ColumnInfo
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
    /** Optional; broadcast together with the name. */
    val email: String = "",
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
    val email: String = "",
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
    /** Who wrote it: matters in groups (peerId is the group id there). "" in old rows = the peer. */
    @ColumnInfo(defaultValue = "''") val senderId: String = "",
    @ColumnInfo(defaultValue = "''") val replyToId: String = "",
    @ColumnInfo(defaultValue = "''") val replyQuote: String = "",
    /** Disappearing messages: epoch ms when this phone deletes it; 0 = never. */
    @ColumnInfo(defaultValue = "0") val expiresAt: Long = 0,
    /** Voice clip sent with push-to-talk. */
    @ColumnInfo(defaultValue = "0") val ptt: Boolean = false,
) {
    fun preview(): String = when (kind) {
        "LOCATION" -> "📍 Location"
        "IMAGE" -> "📷 Photo"
        "VOICE" -> (if (ptt) "📻 Push-to-talk" else "🎤 Voice message") + " (${com.meshchat.core.Content.formatDuration(durationMs)})"
        else -> text
    }
}

/** Per-chat settings kept on this phone only: pin to top, mute notifications, disappearing-message timer for what I send. */
@Entity(tableName = "chat_pref")
data class ChatPrefEntity(
    @PrimaryKey val peerId: String,
    val pinned: Boolean,
    val muted: Boolean,
    val disappearSec: Int,
)

/** Private group (members = comma separated Node IDs). */
@Entity(tableName = "chat_group")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creator: String,
    val members: String,
    val createdAt: Long,
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

/** Latest SOS from each person we heard (active or "safe"). Kept on this phone only. */
@Entity(tableName = "sos_event")
data class SosEntity(
    @PrimaryKey val nodeId: String,
    val name: String,
    val active: Boolean,
    val battery: Int,
    val hasLocation: Boolean,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val timestamp: Long,
    val hops: Int,
    val verified: Boolean,
    val seen: Boolean,
)
