package com.kiddolock.app.content

import android.content.Context
import com.kiddolock.app.content.core.ContentClassifier

/**
 * ContentPreferences — SharedPreferences wrapper for the SafeKids content filter
 * sub-module inside SafeLock. Kept independent from the core KiddoLock prefs so
 * that enabling/disabling the filter does not touch parental-control locking.
 */
class ContentPreferences(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "safelock_content_filter"
        private const val KEY_ENABLED = "content_filter_enabled"
        private const val KEY_SENSITIVITY = "sensitivity_level"
        private const val KEY_BLOCK_COUNT = "lifetime_block_count"
    }

    /** When true the AccessibilityService scans YouTube (Kids) for violence. */
    var contentFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var sensitivityLevel: ContentClassifier.SensitivityLevel
        get() {
            val name = prefs.getString(KEY_SENSITIVITY, ContentClassifier.SensitivityLevel.BALANCED.name)
            return runCatching { ContentClassifier.SensitivityLevel.valueOf(name ?: "BALANCED") }
                .getOrDefault(ContentClassifier.SensitivityLevel.BALANCED)
        }
        set(value) = prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()

    var lifetimeBlockCount: Int
        get() = prefs.getInt(KEY_BLOCK_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_BLOCK_COUNT, value).apply()

    fun incrementBlockCount() {
        lifetimeBlockCount += 1
    }
}
