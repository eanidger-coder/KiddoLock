package com.kiddolock.app.content.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklisted_keywords")
data class BlacklistedKeyword(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val language: String = "he", // he, en, ar
    val addedAt: Long = System.currentTimeMillis()
)
