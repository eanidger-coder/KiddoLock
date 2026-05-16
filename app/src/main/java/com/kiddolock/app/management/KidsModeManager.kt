package com.kiddolock.app.management

import android.content.Context
import android.util.Log

/**
 * Manages the "Kids Mode" state for the application.
 * When enabled, the app blocking and time limits are enforced.
 */
class KidsModeManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("kids_mode_prefs", Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean("kids_mode_enabled", false)
        set(value) {
            prefs.edit().putBoolean("kids_mode_enabled", value).apply()
            Log.i("KidsModeManager", "Kids Mode ${if (value) "ENABLED" else "DISABLED"}")

            // Refresh notification IMMEDIATELY - dormant gray vs active green
            try {
                com.kiddolock.app.utils.NotificationUtils.updateNotification(context, value)
            } catch (e: Throwable) {
                Log.w("KidsModeManager", "Could not refresh notification: " + e.message)
            }

            // Push settings to cloud whenever status changes (best-effort, may fail silently)
            try {
                SettingsSyncManager(context).pushSettings()
            } catch (e: Throwable) {
                Log.w("KidsModeManager", "Cloud sync skipped: " + e.message)
            }
        }


    // This is now redundant but kept for potential future use or to avoid build breaks in usage-points
    fun isProtectedInKidsMode(packageName: String): Boolean {
        // We no longer use a hardcoded list. Everything is managed via AppManager's blacklist.
        return false
    }

}
