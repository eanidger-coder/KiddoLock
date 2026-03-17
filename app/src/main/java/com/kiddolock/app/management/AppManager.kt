package com.kiddolock.app.management

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Manages app whitelist/blacklist for KiddoLock.
 * Controls which apps are allowed to run on the device.
 */
class AppManager(private val context: Context) {

    companion object {
        private const val TAG = "AppManager"
        private const val PREFS_NAME = "kiddolock_app_prefs"
        private const val KEY_BLACKLISTED_APPS = "blacklisted_apps"
        private const val KEY_APP_BLOCKING_ENABLED = "app_blocking_enabled"
    }

    /**
     * Default blacklisted packages — social media, browsers, dating, streaming.
     * These are blocked at Strict protection level.
     */
    private val DEFAULT_BLACKLIST = setOf(
        // Social Media
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",  // Messenger
        "com.instagram.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",  // TikTok
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.tumblr",
        "com.pinterest",

        // Browsers
        "com.android.chrome",

        // App Stores & Package Managers (Critical Hardening)
        "com.android.settings",
        "com.samsung.android.settings",
        "com.google.android.packageinstaller",
        "com.android.vending", // Google Play Store
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.huawei.appmarket", // Huawei AppMarket
        "com.xiaomi.mipicks", // Xiaomi GetApps

        // Communication (Default blocked to encourage manual allow)
        "com.google.android.gm",
        "com.microsoft.office.outlook",

        // Google Services & Entertainment
        "com.google.android.googlequicksearchbox", // Google Search
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",

        // Media & Files
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.google.android.apps.docs",

        // Dating
        "com.tinder",
        "com.bumble.app",
        "com.hinge.app",
        "com.match.android.matchmobile",
        "com.okcupid.okcupid",
        "com.badoo.mobile",
        "com.grindr.android",

        // Video Streaming (non-educational)
        "tv.twitch.android.app",

        // More Social & Messaging
        "com.linkedin.android",
        "org.telegram.messenger",
        "video.likee",
        "com.kwai.video",

        // Content
        "com.imgur.mobile",
        "com.ninegag.android.app"
    )

    // System apps that must NEVER be blocked (even by the parent)
    val CORE_SYSTEM_WHITELIST = setOf(
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.kiddolock.app"
    )

    // Apps that bypass Time Restrictions by default but CAN be manually locked by parent
    val ESSENTIAL_APPS_WHITELIST = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.phone",
        "com.kiddolock.app",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.whatsapp"
    )

    // Active blacklist (user-configurable)
    private val blacklistedApps = HashSet<String>()
    private var blockingEnabled = false

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val isBlacklisted: Boolean,
        val isSystemProtected: Boolean
    )

    /**
     * Initialize the app manager.
     */
    fun initialize() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        blockingEnabled = true // Always enabled now that master switch is removed

        // Migration: V5 (Expand blacklist with stores, YouTube, and Gallery)
        val blacklistVersion = prefs.getInt("blacklist_version", 0)
        if (blacklistVersion < 5) {
            // Explicitly add new defaults for any user upgrading to V5
            blacklistedApps.add("com.android.vending")
            blacklistedApps.add("com.sec.android.app.samsungapps")
            blacklistedApps.add("com.huawei.appmarket")
            blacklistedApps.add("com.xiaomi.mipicks")
            blacklistedApps.add("com.google.android.googlequicksearchbox")
            blacklistedApps.add("com.google.android.youtube")
            blacklistedApps.add("com.google.android.apps.youtube.kids")
            blacklistedApps.add("com.google.android.apps.photos")
            blacklistedApps.add("com.sec.android.gallery3d")
            blacklistedApps.add("com.google.android.apps.docs")
            saveBlacklist()

            prefs.edit().putInt("blacklist_version", 5).apply()
        }

        val savedBlacklist = prefs.getStringSet(KEY_BLACKLISTED_APPS, null)
        blacklistedApps.clear()
        if (savedBlacklist != null) {
            blacklistedApps.addAll(savedBlacklist)
        } else {
            // First run or post-migration: use defaults
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
        }

        Log.i(TAG, "App Manager initialized: ${blacklistedApps.size} apps blacklisted, blocking=${blockingEnabled}")
    }

    /**
     * Check if a package is blacklisted and should be blocked.
     */
    fun isBlacklisted(packageName: String): Boolean {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) return false
        return blacklistedApps.contains(packageName)
    }

    /**
     * Check if a package is a system-protected app that cannot be toggled.
     */
    fun isSystemProtected(packageName: String): Boolean {
        return CORE_SYSTEM_WHITELIST.contains(packageName)
    }

    /**
     * Add an app to the blacklist.
     */
    fun blacklistApp(packageName: String) {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) {
            Log.w(TAG, "Cannot blacklist CORE system app: $packageName")
            return
        }
        blacklistedApps.add(packageName)
        saveBlacklist()
    }

    /**
     * Remove an app from the blacklist.
     */
    fun whitelistApp(packageName: String) {
        blacklistedApps.remove(packageName)
        saveBlacklist()
    }

    fun isBlockingEnabled(): Boolean = true

    /**
     * Get all installed apps with their blacklist status.
     */
    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return apps.filter { app ->
            // Only show apps with a launcher icon (user-facing apps)
            pm.getLaunchIntentForPackage(app.packageName) != null
        }.map { app ->
            AppInfo(
                packageName = app.packageName,
                appName = pm.getApplicationLabel(app).toString(),
                isBlacklisted = blacklistedApps.contains(app.packageName),
                isSystemProtected = isSystemProtected(app.packageName)
            )
        }.sortedWith(compareByDescending<AppInfo> { it.isBlacklisted }.thenBy { it.appName })
    }

    /**
     * Get the count of blacklisted apps.
     */
    fun getBlacklistedCount(): Int = blacklistedApps.size

    /**
     * Reset blacklist to defaults.
     */
    fun resetToDefaults() {
        blacklistedApps.clear()
        blacklistedApps.addAll(DEFAULT_BLACKLIST)
        saveBlacklist()
    }

    private fun saveBlacklist() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BLACKLISTED_APPS, HashSet(blacklistedApps)) // Defensive copy
            .apply()

        // Invalidate the accessibility service cache so changes take effect immediately
        AppBlockManager.invalidateCache()

        // Push settings to cloud whenever blacklist is updated
        SettingsSyncManager(context).pushSettings()
    }
}
