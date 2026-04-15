package com.kiddolock.app.content.dao

import androidx.room.*
import com.kiddolock.app.content.entities.BlockedEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedEventDao {

    @Query("SELECT * FROM blocked_events ORDER BY timestamp DESC")
    fun getAll(): Flow<List<BlockedEvent>>

    @Query("SELECT * FROM blocked_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<BlockedEvent>>

    @Query("SELECT COUNT(*) FROM blocked_events WHERE timestamp > :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM blocked_events WHERE timestamp > :since AND reason = 'escalation'")
    suspend fun countEscalationsSince(since: Long): Int

    @Insert
    suspend fun insert(event: BlockedEvent)

    @Query("DELETE FROM blocked_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
