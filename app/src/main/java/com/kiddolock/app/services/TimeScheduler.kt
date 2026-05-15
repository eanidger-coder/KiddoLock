package com.kiddolock.app.services
import com.kiddolock.app.R

import android.content.Context
import android.util.Log
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.management.SettingsSyncManager
import java.util.Calendar

/**
 * Manages time-based restrictions for KiddoLock.
 * Supports quiet hours and daily time limits.
 */
class TimeScheduler(private val context: Context) {

    companion object {
        private const val TAG = "TimeScheduler"
        private const val PREFS_NAME = "kiddolock_schedule_prefs"
    }

    data class ScheduleConfig(
        val quietHoursEnabled: Boolean = false,
        val quietHoursStart: Int = 22,
        val quietHoursStartMin: Int = 0,
        val quietHoursEnd: Int = 6,
        val quietHoursEndMin: Int = 0,
        
        val dailyTimeLimitEnabled: Boolean = false,
        val dailyTimeLimitMinutes: Int = 120, // 2 hours default
        
        val isInstantLocked: Boolean = false
    )

    /**
     * Load the current schedule configuration.
     */
    fun getConfig(): ScheduleConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ScheduleConfig(
            quietHoursEnabled = prefs.getBoolean("quiet_hours_enabled", false),
            quietHoursStart = prefs.getInt("quiet_hours_start", 22),
            quietHoursStartMin = prefs.getInt("quiet_hours_start_min", 0),
            quietHoursEnd = prefs.getInt("quiet_hours_end", 6),
            quietHoursEndMin = prefs.getInt("quiet_hours_end_min", 0),
            dailyTimeLimitEnabled = prefs.getBoolean("daily_time_limit_enabled", false),
            dailyTimeLimitMinutes = prefs.getInt("daily_time_limit_minutes", 120),
            isInstantLocked = prefs.getBoolean("is_instant_locked", false)
        )
    }

    /**
     * Save schedule configuration.
     */
    fun saveConfig(config: ScheduleConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("quiet_hours_enabled", config.quietHoursEnabled)
            .putInt("quiet_hours_start", config.quietHoursStart)
            .putInt("quiet_hours_start_min", config.quietHoursStartMin)
            .putInt("quiet_hours_end", config.quietHoursEnd)
            .putInt("quiet_hours_end_min", config.quietHoursEndMin)
            .putBoolean("daily_time_limit_enabled", config.dailyTimeLimitEnabled)
            .putInt("daily_time_limit_minutes", config.dailyTimeLimitMinutes)
            .putBoolean("is_instant_locked", config.isInstantLocked)
            .apply()
        
        // Trigger cloud sync
        SettingsSyncManager(context).pushSettings()
    }

    /**
     * Check if the current time falls within a restricted period.
     */
    fun isCurrentlyRestricted(): Boolean {
        return isBedtimeActive() || isDailyLimitReached() || isInstantLocked()
    }

    /**
     * Check if bedtime (quiet hours) is currently active.
     */
    fun isBedtimeActive(): Boolean {
        val config = getConfig()
        val now = Calendar.getInstance()
        return config.quietHoursEnabled && isTimeInRange(now, config.quietHoursStart, config.quietHoursStartMin, config.quietHoursEnd, config.quietHoursEndMin)
    }

    /**
     * Check if the daily time limit has been reached.
     */
    fun isDailyLimitReached(): Boolean {
        val config = getConfig()
        return config.dailyTimeLimitEnabled && isDailyLimitExceeded(config.dailyTimeLimitMinutes)
    }

    /**
     * Check if instant lock is active.
     */
    fun isInstantLocked(): Boolean {
        return getConfig().isInstantLocked
    }

    /**
     * Get a human-readable reason for the current restriction.
     */
    fun getRestrictionReason(): String? {
        val config = getConfig()
        val now = Calendar.getInstance()

        if (config.isInstantLocked) return context.getString(R.string.reason_remote_lock)

        if (config.quietHoursEnabled && isTimeInRange(now, config.quietHoursStart, config.quietHoursStartMin, config.quietHoursEnd, config.quietHoursEndMin)) {
            return context.getString(R.string.reason_quiet_hours, config.quietHoursStart, config.quietHoursEnd)
        }
        
        if (config.dailyTimeLimitEnabled && isDailyLimitExceeded(config.dailyTimeLimitMinutes)) {
            return context.getString(R.string.reason_daily_limit, config.dailyTimeLimitMinutes)
        }
        
        return null
    }

    // --- Quiet Hours ---

    private fun isTimeInRange(now: Calendar, startHour: Int, startMin: Int, endHour: Int, endMin: Int): Boolean {
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMin = now.get(Calendar.MINUTE)
        
        val currentTotalMin = currentHour * 60 + currentMin
        val startTotalMin = startHour * 60 + startMin
        val endTotalMin = endHour * 60 + endMin
        
        return if (startTotalMin > endTotalMin) {
            // Overnight: e.g., 22:00 to 06:00
            currentTotalMin >= startTotalMin || currentTotalMin < endTotalMin
        } else {
            // Same day: e.g., 13:00 to 15:00
            currentTotalMin in startTotalMin until endTotalMin
        }
    }

    // --- Daily Time Limit ---

    fun recordUsageMinute() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getTodayKey()
        val currentUsage = prefs.getInt("usage_$today", 0)
        prefs.edit().putInt("usage_$today", currentUsage + 1).apply()
    }

    fun getTodayUsageMinutes(): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("usage_${getTodayKey()}", 0)
    }

    fun getRemainingMinutes(): Int {
        val config = getConfig()
        if (!config.dailyTimeLimitEnabled) return Int.MAX_VALUE
        return maxOf(0, config.dailyTimeLimitMinutes - getTodayUsageMinutes())
    }

    /**
     * Returns true if the child is within the 'Warning Zone' (e.g., 5 minutes remaining).
     */
    fun isInWarningZone(): Boolean {
        val config = getConfig()
        if (!config.dailyTimeLimitEnabled) return false
        val remaining = getRemainingMinutes()
        return remaining in 1..5
    }

    private fun isDailyLimitExceeded(limitMinutes: Int): Boolean {
        return getTodayUsageMinutes() >= limitMinutes
    }

    private fun getTodayKey(): String {
        val now = Calendar.getInstance()
        return "${now.get(Calendar.YEAR)}_${now.get(Calendar.DAY_OF_YEAR)}"
    }

    // --- Quick Configuration ---

    fun enableQuietHours(startHour: Int = 22, endHour: Int = 6) {
        val config = getConfig().copy(
            quietHoursEnabled = true,
            quietHoursStart = startHour,
            quietHoursEnd = endHour
        )
        saveConfig(config)
        Log.i(TAG, "Quiet hours enabled: $startHour:00 -> $endHour:00")
    }

    fun disableQuietHours() {
        val config = getConfig().copy(quietHoursEnabled = false)
        saveConfig(config)
    }

    fun setDailyTimeLimit(minutes: Int) {
        val config = getConfig().copy(
            dailyTimeLimitEnabled = true,
            dailyTimeLimitMinutes = minutes
        )
        saveConfig(config)
        Log.i(TAG, "Daily time limit set: $minutes minutes")
    }

    fun disableDailyTimeLimit() {
        val config = getConfig().copy(dailyTimeLimitEnabled = false)
        saveConfig(config)
    }

    fun setInstantLock(locked: Boolean) {
        val config = getConfig().copy(isInstantLocked = locked)
        saveConfig(config)
        Log.w(TAG, "Instant Lock ${if (locked) "ENABLED" else "DISABLED"}")
    }

    // --- UI Helpers ---

    fun getBedtimeRangeString(): String? {
        val config = getConfig()
        if (!config.quietHoursEnabled) return null
        return String.format("%02d:%02d - %02d:%02d", 
            config.quietHoursStart, config.quietHoursStartMin,
            config.quietHoursEnd, config.quietHoursEndMin)
    }

    fun getDailyLimitString(): String? {
        val config = getConfig()
        if (!config.dailyTimeLimitEnabled) return null
        val hours = config.dailyTimeLimitMinutes / 60
        val mins = config.dailyTimeLimitMinutes % 60
        return when {
            hours > 0 && mins > 0 -> "$hours שעות ו-$mins דקות"
            hours > 0 -> "$hours שעות"
            else -> "$mins דקות"
        }
    }
}
