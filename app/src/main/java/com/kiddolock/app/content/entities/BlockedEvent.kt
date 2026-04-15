package com.kiddolock.app.content.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_events")
data class BlockedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoTitle: String,
    val channelName: String = "",
    val reason: String, // "keyword", "escalation", "blacklist"
    val matchedTerms: String = "",
    val violenceScore: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)
