package com.kiddolock.app.content.dao

import androidx.room.*
import com.kiddolock.app.content.entities.ViewingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM viewing_sessions WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionVideos(sessionId: String): List<ViewingSession>

    @Query("SELECT * FROM viewing_sessions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentVideos(limit: Int = 10): List<ViewingSession>

    @Query("SELECT * FROM viewing_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ViewingSession>>

    @Insert
    suspend fun insert(session: ViewingSession)

    @Query("DELETE FROM viewing_sessions WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
