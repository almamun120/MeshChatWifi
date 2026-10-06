package com.meshchat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        NodeEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        PublicPostEntity::class,
        PendingEntity::class,
        MediaOutEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun nodeDao(): NodeDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun postDao(): PostDao
    abstract fun pendingDao(): PendingDao
    abstract fun mediaOutDao(): MediaOutDao

    companion object {
        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context.applicationContext, MeshDatabase::class.java, "meshchat.db")
                .fallbackToDestructiveMigration()   // pre-release: schema may change between builds
                .build()
    }
}
