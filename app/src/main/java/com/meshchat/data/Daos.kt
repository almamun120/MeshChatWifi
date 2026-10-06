package com.meshchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM user WHERE id = 1")
    fun observe(): Flow<UserEntity?>

    @Query("SELECT * FROM user WHERE id = 1")
    suspend fun get(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("UPDATE user SET showDob = :value WHERE id = 1")
    suspend fun setShowDob(value: Boolean)

    @Query("UPDATE user SET photoPath = :path WHERE id = 1")
    suspend fun setPhoto(path: String?)

    @Query("UPDATE user SET nodeId = :nodeId WHERE id = 1")
    suspend fun setNodeId(nodeId: String)
}

@Dao
interface NodeDao {
    @Query("SELECT * FROM node WHERE nodeId = :id")
    suspend fun get(id: String): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeEntity)

    @Query("SELECT * FROM node")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT nodeId FROM node WHERE blocked = 1")
    suspend fun blockedIds(): List<String>

    @Query("SELECT nodeId FROM node WHERE muted = 1")
    suspend fun mutedIds(): List<String>
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY lastTimestamp DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chat WHERE peerId = :peerId")
    suspend fun get(peerId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("UPDATE chat SET unread = 0 WHERE peerId = :peerId")
    suspend fun markRead(peerId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun observe(peerId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE message SET status = :status WHERE msgId = :msgId")
    suspend fun setStatus(msgId: String, status: String)

    /** QUEUED -> SENT only, so a fast ACK (DELIVERED) is never overwritten. */
    @Query("UPDATE message SET status = 'SENT' WHERE msgId = :msgId AND status = 'QUEUED'")
    suspend fun markSent(msgId: String)
}

@Dao
interface PostDao {
    @Query("SELECT * FROM public_post ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PublicPostEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(post: PublicPostEntity): Long

    @Query("SELECT COUNT(*) FROM public_post WHERE postKey = :key")
    suspend fun count(key: String): Int

    @Query("SELECT raw FROM public_post WHERE postKey = :key")
    suspend fun raw(key: String): ByteArray?

    @Query("SELECT authorId, postKey FROM public_post ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentRefs(limit: Int): List<PostRefRow>

    @Query("DELETE FROM public_post WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM public_post WHERE postKey NOT IN (SELECT postKey FROM public_post ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimToLatest(keep: Int)
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_message ORDER BY createdAt ASC")
    suspend fun all(): List<PendingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rec: PendingEntity)

    @Query("UPDATE pending_message SET attempts = :attempts, lastAttempt = :lastAttempt WHERE pendingKey = :key")
    suspend fun update(key: String, attempts: Int, lastAttempt: Long)

    @Query("DELETE FROM pending_message WHERE pendingKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM pending_message WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM pending_message")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_message")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_message WHERE pendingKey IN (SELECT pendingKey FROM pending_message ORDER BY createdAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)
}

@Dao
interface MediaOutDao {
    @Query("SELECT transferId, dst, totalChunks, createdAt, expiresAt, attempts, lastAttempt FROM media_out ORDER BY createdAt ASC")
    suspend fun infos(): List<MediaOutInfoRow>

    @Query("SELECT blob FROM media_out WHERE transferId = :id")
    suspend fun blob(id: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: MediaOutEntity)

    @Query("UPDATE media_out SET attempts = :attempts, lastAttempt = :lastAttempt WHERE transferId = :id")
    suspend fun update(id: String, attempts: Int, lastAttempt: Long)

    @Query("DELETE FROM media_out WHERE transferId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM media_out WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)
}
