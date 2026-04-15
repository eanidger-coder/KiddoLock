package com.kiddolock.app.content.dao

import androidx.room.*
import com.kiddolock.app.content.entities.BlacklistedChannel
import com.kiddolock.app.content.entities.BlacklistedKeyword
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistDao {

    @Query("SELECT * FROM blacklisted_channels ORDER BY addedAt DESC")
    fun getAllChannels(): Flow<List<BlacklistedChannel>>

    @Query("SELECT channelName FROM blacklisted_channels")
    suspend fun getAllChannelNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: BlacklistedChannel)

    @Delete
    suspend fun deleteChannel(channel: BlacklistedChannel)

    @Query("DELETE FROM blacklisted_channels WHERE id = :id")
    suspend fun deleteChannelById(id: Long)

    @Query("SELECT * FROM blacklisted_keywords ORDER BY addedAt DESC")
    fun getAllKeywords(): Flow<List<BlacklistedKeyword>>

    @Query("SELECT keyword FROM blacklisted_keywords")
    suspend fun getAllKeywordStrings(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeyword(keyword: BlacklistedKeyword)

    @Delete
    suspend fun deleteKeyword(keyword: BlacklistedKeyword)

    @Query("DELETE FROM blacklisted_keywords WHERE id = :id")
    suspend fun deleteKeywordById(id: Long)
}
