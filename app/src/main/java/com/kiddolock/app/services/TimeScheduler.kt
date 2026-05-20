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
     * Honors the "bonus time" feature - if parent granted extra time, all restrictions are
     * temporarily lifted until the bonus expires.
     */
    fun isCurrentlyRestricted(): Boolean {
        if (isBonusTimeActive()) return false  // bonus active = no restrictions
        return isBedtimeActive() || isDailyLimitReached() || isInstantLocked()
    }

    /** Total bonus minutes the parent granted TODAY (resets each day, like usage). */
    fun getBonusMinutesToday(): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("bonus_minutes_${getTodayKey()}", 0)
    }

    /** Daily limit + today's bonus = the effective allowance. */
    fun getEffectiveDailyLimitMinutes(): Int {
        return getConfig().dailyTimeLimitMinutes + getBonusMinutesToday()
    }

    /**
     * Parent grants bonus time. v1.5.61 FIX: bonus now ADDS ON TOP of the limits instead of
     * replacing them.
     *  - If a daily time limit is enabled: bonus minutes are ADDED to today's effective limit
     *    (e.g. 60-min limit + 10 bonus = 70 effective). The original limit is preserved.
     *  - If no daily limit (only bedtime): bonus extends a rolling time window from now, so the
     *    kid gets that many real minutes even during bedtime.
     * Either way the bonus lifts BOTH the daily limit and bedtime while it is active.
     */
    fun grantBonusTime(minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val config = getConfig()
        if (config.dailyTimeLimitEnabled) {
            val key = "bonus_minutes_${getTodayKey()}"
            val current = prefs.getInt(key, 0)
            prefs.edit().putInt(key, current + minutes).apply()
            Log.i(TAG, "Bonus +$minutes min added to daily limit. Total bonus today: ${current + minutes}, effective limit: ${getEffectiveDailyLimitMinutes()}")
        } else {
            // No daily limit — use a rolling window so the bonus also covers bedtime.
            val now = System.currentTimeMillis()
            val currentUntil = prefs.getLong("bonus_time_until", 0L)
            val base = maxOf(now, currentUntil)
            prefs.edit().putLong("bonus_time_until", base + (minutes.toLong() * 60 * 1000L)).apply()
            Log.i(TAG, "Bonus +$minutes min window (no daily limit). Active until ${base + minutes * 60000L}")
        }
        try { com.kiddolock.app.management.AppBlockManager.invalidateCache() } catch (_: Throwable) {}
        try { com.kiddolock.app.utils.NotificationUtils.updateNotification(context, true) } catch (_: Throwable) {}
    }

    /**
     * True while parent-granted bonus is still in effect. When active it lifts BOTH the daily
     * limit and bedtime (bonus is the strongest rule — it wins over every restriction).
     */
    fun isBonusTimeActive(): Boolean {
        val config = getConfig()
        if (config.dailyTimeLimitEnabled) {
            // Additive bonus: active as long as usage hasn't consumed the effective (limit+bonus) allowance.
            val bonus = getBonusMinutesToday()
            return bonus > 0 && getTodayUsageMinutes() < getEffectiveDailyLimitMinutes()
        } else {
            // Window bonus (no daily limit case).
            val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong("bonus_time_until", 0L)
            return System.currentTimeMillis() < until
        }
    }

    /** How many seconds of bonus time remain (0 if none). */
    fun getBonusTimeRemainingSec(): Long {
        if (!isBonusTimeActive()) return 0L
        val config = getConfig()
        return if (config.dailyTimeLimitEnabled) {
            val remainMin = getEffectiveDailyLimitMinutes() - getTodayUsageMinutes()
            maxOf(0L, remainMin.toLong() * 60)
        } else {
            val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong("bonus_time_until", 0L)
            maxOf(0L, (until - System.currentTimeMillis()) / 1000)
        }
    }

    /** Parent revokes bonus early — clears both the additive bonus and the window. */
    fun cancelBonusTime() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("bonus_time_until")
            .remove("bonus_minutes_${getTodayKey()}")
            .apply()
        Log.i(TAG, "Bonus cancelled by parent")
        try { com.kiddolock.app.management.AppBlockManager.invalidateCache() } catch (_: Throwable) {}
    }

    /**
     * Reset today's usage counter to zero. Useful when parent wants to "give back" all time
     * accumulated today without dealing with limits.
     */
    fun resetTodayUsage() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Use SAME key format that recordUsageMinute uses
        val key = "usage_${getTodayKey()}"
        prefs.edit().putInt(key, 0).apply()
        Log.i(TAG, "Today's usage reset to 0 by parent (key=$key)")
        try { com.kiddolock.app.management.AppBlockManager.invalidateCache() } catch (_: Throwable) {}
    }

    /**
     * Snooze bedtime for tonight - skip the bedtime restriction once. Sets a flag that
     * isBedtimeActive() will honor until the next bedtime window starts.
     */
    fun snoozeBedtimeTonight() {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("snooze_bedtime_until", now + (12L * 60 * 60 * 1000))  // 12 hours - covers a full night
            .apply()
        Log.i(TAG, "Bedtime snoozed for next 12 hours by parent")
        try { com.kiddolock.app.management.AppBlockManager.invalidateCache() } catch (_: Throwable) {}
    }

    fun isBedtimeSnoozed(): Boolean {
        val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("snooze_bedtime_until", 0L)
        return System.currentTimeMillis() < until
    }

    /**
     * Check if bedtime (quiet hours) is currently active.
     */
    fun isBedtimeActive(): Boolean {
        val config = getConfig()
        if (isBedtimeSnoozed()) return false  // parent dismissed bedtime tonight
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
        // v1.5.61: count remaining against the EFFECTIVE limit (daily + bonus), so a +10 bonus
        // really shows 10 extra minutes left instead of replacing the limit.
        return maxOf(0, getEffectiveDailyLimitMinutes() - getTodayUsageMinutes())
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
        // v1.5.61: the limit is exceeded only when usage passes the EFFECTIVE allowance
        // (configured limit + today's bonus). Bonus adds on top, never replaces.
        return getTodayUsageMinutes() >= (limitMinutes + getBonusMinutesToday())
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
