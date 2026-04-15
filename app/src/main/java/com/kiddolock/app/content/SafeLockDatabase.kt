package com.kiddolock.app.content

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kiddolock.app.content.dao.BlacklistDao
import com.kiddolock.app.content.dao.BlockedEventDao
import com.kiddolock.app.content.dao.SessionDao
import com.kiddolock.app.content.entities.BlacklistedChannel
import com.kiddolock.app.content.entities.BlacklistedKeyword
import com.kiddolock.app.content.entities.BlockedEvent
import com.kiddolock.app.content.entities.ViewingSession

@Database(
    entities = [
        BlacklistedChannel::class,
        BlacklistedKeyword::class,
        ViewingSession::class,
        BlockedEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SafeLockDatabase : RoomDatabase() {

    abstract fun blacklistDao(): BlacklistDao
    abstract fun sessionDao(): SessionDao
    abstract fun blockedEventDao(): BlockedEventDao

    companion object {
        @Volatile
        private var INSTANCE: SafeLockDatabase? = null

        fun getInstance(context: Context): SafeLockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SafeLockDatabase::class.java,
                    "safelock_content_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
