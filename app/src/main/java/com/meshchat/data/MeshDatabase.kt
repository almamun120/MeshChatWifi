package com.meshchat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserEntity::class,
        NodeEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        PublicPostEntity::class,
        PendingEntity::class,
        MediaOutEntity::class,
        ChatPrefEntity::class,
        GroupEntity::class,
        SosEntity::class,
    ],
    version = 3,
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
    abstract fun chatPrefDao(): ChatPrefDao
    abstract fun groupDao(): GroupDao
    abstract fun sosDao(): SosDao

    companion object {
        /** v1 -> v2: reply / disappearing / group columns and tables. Existing chats are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `senderId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `replyToId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `replyQuote` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `expiresAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `ptt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_pref` (`peerId` TEXT NOT NULL, `pinned` INTEGER NOT NULL, `muted` INTEGER NOT NULL, `disappearSec` INTEGER NOT NULL, PRIMARY KEY(`peerId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_group` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `creator` TEXT NOT NULL, `members` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sos_event` (`nodeId` TEXT NOT NULL, `name` TEXT NOT NULL, `active` INTEGER NOT NULL, `battery` INTEGER NOT NULL, `hasLocation` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `hops` INTEGER NOT NULL, `verified` INTEGER NOT NULL, `seen` INTEGER NOT NULL, PRIMARY KEY(`nodeId`))")
            }
        }

        /** v2 -> v3: optional e-mail of the user and of every known node. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `node` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context.applicationContext, MeshDatabase::class.java, "meshchat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()   // pre-release: schema may change between builds
                .build()
    }
}
