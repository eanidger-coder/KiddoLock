package com.kiddolock.app.content.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "viewing_sessions")
data class ViewingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoTitle: String,
    val channelName: String = "",
    val violenceScore: Float = 0f,
    val categories: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = ""
)
