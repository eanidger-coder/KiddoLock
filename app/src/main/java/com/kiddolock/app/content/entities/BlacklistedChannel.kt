package com.kiddolock.app.content.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklisted_channels")
data class BlacklistedChannel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelName: String,
    val reason: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
