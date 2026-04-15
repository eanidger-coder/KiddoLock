package com.kiddolock.app.content.core

import com.kiddolock.app.content.dao.BlacklistDao

/**
 * ChannelAnalyzer — Layer 3 of SafeLock content detection engine.
 *
 * Checks if a channel is on the parent's blacklist.
 * Tracks channels that repeatedly produce flagged content.
 */
class ChannelAnalyzer(private val blacklistDao: BlacklistDao) {

    private var cachedBlacklist: Set<String> = emptySet()
    private val channelViolationCount = mutableMapOf<String, Int>()

    fun isBlacklisted(channelName: String): Boolean {
        val normalized = channelName.lowercase().trim()
        return cachedBlacklist.any { blacklisted ->
            normalized.contains(blacklisted.lowercase())
        }
    }

    fun recordViolation(channelName: String) {
        val normalized = channelName.lowercase().trim()
        val count = (channelViolationCount[normalized] ?: 0) + 1
        channelViolationCount[normalized] = count
    }

    fun getRepeatOffenders(minViolations: Int = 3): Map<String, Int> {
        return channelViolationCount.filter { it.value >= minViolations }
    }

    suspend fun refreshBlacklist() {
        cachedBlacklist = blacklistDao.getAllChannelNames().map { it.lowercase() }.toSet()
    }

    suspend fun refreshCustomKeywords(): List<String> {
        return blacklistDao.getAllKeywordStrings()
    }
}
