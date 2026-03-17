package com.kiddolock.app.management
import com.kiddolock.app.R

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * Tracks per-app screen time and enforces individual time limits.
 *
 * Usage is stored per day per package, automatically resets at midnight.
 * Works alongside TimeScheduler (global limit) but at app granularity.
 */
class PerAppTimeTracker(private val context: Context) {

    companion object {
        private const val TAG = "PerAppTimeTracker"
        private const val PREFS_NAME = "kiddolock_perapp_prefs"
        private const val PREFIX_LIMIT = "limit_"       // limit_{pkg} = minutes
        private const val PREFIX_USAGE = "usage_"       // usage_{pkg}_{dayKey} = minutes

        /** Default limits per well-known app categories (minutes/day). */
        val SUGGESTED_LIMITS = mapOf(
            "com.google.android.youtube"  to 60,
            "com.zhiliaoapp.musically"    to 30,  // TikTok
            "com.instagram.android"       to 45,
            "com.twitter.android"         to 30,
            "com.facebook.katana"         to 45,
            "com.snapchat.android"        to 30,
            "com.reddit.frontpage"        to 60,
            "tv.twitch.android.app"       to 60,
            "com.android.chrome"          to 120,
            "org.mozilla.firefox"         to 120,
        )
    }

    data class AppUsageSummary(
        val packageName: String,
        val appName: String,
        val usedMinutes: Int,
        val limitMinutes: Int?,   // null = no limit
        val remainingMinutes: Int?
    ) {
        val isOverLimit: Boolean get() = limitMinutes != null && usedMinutes >= limitMinutes
        val percentUsed: Float get() = if (limitMinutes != null && limitMinutes > 0)
            (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f) else 0f
    }

    /**
     * Set a daily time limit for a specific app.
     * Pass null to remove the limit. 0 means instantly blocked.
     */
    fun setLimit(packageName: String, limitMinutes: Int?) {
        val editor = prefs().edit()
        if (limitMinutes == null) {
            editor.remove("$PREFIX_LIMIT$packageName")
        } else {
            editor.putInt("$PREFIX_LIMIT$packageName", maxOf(0, limitMinutes))
        }
        editor.apply()
        Log.i(TAG, "Limit set: $packageName = $limitMinutes min/day")
        
        // Trigger cloud sync
        SettingsSyncManager(context).pushSettings()
    }

    /** Returns the daily limit for an app, or null if none. */
    fun getLimit(packageName: String): Int? {
        val p = prefs()
        return if (p.contains("$PREFIX_LIMIT$packageName")) {
            p.getInt("$PREFIX_LIMIT$packageName", 0)
        } else null
    }

    /**
     * Record one minute of usage for a package.
     * Called from the accessibility service scan loop.
     */
    fun recordMinute(packageName: String) {
        val key = usageKey(packageName)
        val p = prefs()
        val current = p.getInt(key, 0)
        p.edit().putInt(key, current + 1).apply()
    }

    /** Returns minutes used today for a package. */
    fun getUsedMinutes(packageName: String): Int {
        return prefs().getInt(usageKey(packageName), 0)
    }

    /** Returns remaining minutes today, or null if no limit set. */
    fun getRemainingMinutes(packageName: String): Int? {
        val limit = getLimit(packageName) ?: return null
        val used = getUsedMinutes(packageName)
        return maxOf(0, limit - used)
    }

    /**
     * Check if the given package has exceeded its daily time limit.
     * Returns a human-readable reason string, or null if allowed.
     */
    fun checkLimit(packageName: String): String? {
        val limit = getLimit(packageName) ?: return null
        val used = getUsedMinutes(packageName)
        return if (used >= limit) {
            val pm = context.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }
            context.getString(R.string.app_limit_exceeded_msg, appName, limit)
        } else null
    }

    /** Returns all apps that have a limit configured, with their usage. */
    fun getAllLimitedApps(): List<AppUsageSummary> {
        val p = prefs()
        val pm = context.packageManager
        val result = mutableListOf<AppUsageSummary>()

        for ((key, _) in p.all) {
            if (!key.startsWith(PREFIX_LIMIT)) continue
            val pkg = key.removePrefix(PREFIX_LIMIT)
            val limit = p.getInt(key, -1)
            if (limit < 0) continue
            val used = getUsedMinutes(pkg)
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
            result.add(
                AppUsageSummary(
                    packageName = pkg,
                    appName = appName,
                    usedMinutes = used,
                    limitMinutes = limit,
                    remainingMinutes = maxOf(0, limit - used)
                )
            )
        }
        return result.sortedByDescending { it.percentUsed }
    }

    fun isLimitReached(packageName: String): Boolean {
        val limit = getLimit(packageName) ?: return false
        return getUsedMinutes(packageName) >= limit
    }

    fun recordUsage(packageName: String) {
        recordMinute(packageName)
    }

    /** Returns all apps used today (even without limits). */
    fun getAllUsageToday(): Map<String, Int> {
        val p = prefs()
        val today = todayKey()
        val result = mutableMapOf<String, Int>()
        for ((key, value) in p.all) {
            if (key.startsWith(PREFIX_USAGE) && key.endsWith("_$today")) {
                val pkg = key
                    .removePrefix(PREFIX_USAGE)
                    .removeSuffix("_$today")
                if (pkg.isNotEmpty()) {
                    result[pkg] = (value as? Int) ?: 0
                }
            }
        }
        return result
    }

    /** Convenience: get total minutes used today across all apps. */
    fun getTotalUsageToday(): Int = getAllUsageToday().values.sum()

    private fun usageKey(packageName: String) = "$PREFIX_USAGE${packageName}_${todayKey()}"

    private fun todayKey(): String {
        val now = Calendar.getInstance()
        return "${now.get(Calendar.YEAR)}_${now.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
