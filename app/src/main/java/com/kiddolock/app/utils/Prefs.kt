package com.kiddolock.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper class for managing persistent preferences.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "kiddolock_prefs"
        private const val KEY_CERTIFIED_UNINSTALL = "certified_uninstall_in_progress"
        private const val KEY_TEMP_UNLOCK_TIME = "temp_unlock_end_time"
        private const val KEY_LOCK_PIN = "admin_pin"
        private const val KEY_EMERGENCY_BYPASS_UNTIL = "emergency_bypass_until"
        private const val KEY_DISABLE_ALL_FILTERS = "disable_all_filters"
        private const val KEY_BYPASS_GUARD_ENABLED = "bypass_guard_enabled"
        private const val KEY_UNINSTALL_PROTECTION = "uninstall_protection_enabled"
        private const val KEY_SETUP_IN_PROGRESS = "setup_in_progress"
        private const val KEY_LOCKED_SYSTEM_APPS = "locked_system_apps"
        private const val KEY_LAST_ADMIN_ACTIVE = "last_admin_active_time"
    }

    var certified_uninstall_in_progress: Boolean
        get() = prefs.getBoolean(KEY_CERTIFIED_UNINSTALL, false)
        set(value) = prefs.edit().putBoolean(KEY_CERTIFIED_UNINSTALL, value).apply()

    var temporaryUnlocks: Set<String>
        get() = prefs.getStringSet("temporary_unlocks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("temporary_unlocks", value).apply()
        
    var adminPin: String?
        get() = prefs.getString(KEY_LOCK_PIN, null)
        set(value) = prefs.edit().putString(KEY_LOCK_PIN, value).apply()

    var emergency_bypass_until: Long
        get() = prefs.getLong(KEY_EMERGENCY_BYPASS_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_EMERGENCY_BYPASS_UNTIL, value).apply()

    var disable_all_filters: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_ALL_FILTERS, false)
        set(value) = prefs.edit().putBoolean(KEY_DISABLE_ALL_FILTERS, value).apply()

    var bypass_guard_enabled: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_GUARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_GUARD_ENABLED, value).apply()

    var uninstall_protection_enabled: Boolean
        get() = prefs.getBoolean(KEY_UNINSTALL_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_UNINSTALL_PROTECTION, value).apply()

    var setup_in_progress: Boolean
        get() = prefs.getBoolean(KEY_SETUP_IN_PROGRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_IN_PROGRESS, value).apply()

    var locked_system_apps: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED_SYSTEM_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_LOCKED_SYSTEM_APPS, value).apply()

    var lastAdminActiveTime: Long
        get() = prefs.getLong(KEY_LAST_ADMIN_ACTIVE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ADMIN_ACTIVE, value).apply()

    var instant_lock: Boolean
        get() = prefs.getBoolean("instant_lock", false)
        set(value) = prefs.edit().putBoolean("instant_lock", value).apply()


    /**
     * Clear all preferences.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
