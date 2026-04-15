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
        private const val LAUNCHER_CACHE_TTL = 60_000L // 1 minute
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
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", // Samsung Browser
        "com.opera.browser",
        "com.microsoft.emmx", // Edge
        "com.brave.browser",
        "com.duckduckgo.mobile.android",

        // App Stores & Package Managers (Critical Hardening)
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.samsung.android.settings",
        "com.samsung.android.settings.intelligence",
        "com.google.android.packageinstaller",
        "com.android.vending", // Google Play Store
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.huawei.appmarket", // Huawei AppMarket
        "com.xiaomi.mipicks", // Xiaomi GetApps

        // Communication (Default blocked to encourage manual allow)
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",

        // Google Services & Entertainment
        "com.google.android.googlequicksearchbox", // Google Search
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",

        // Media & Files
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.android.gallery",
        "com.miui.gallery",
        "com.huawei.photos",
        "com.coloros.gallery",
        "com.oneplus.gallery",
        "com.google.android.apps.docs",
        "com.microsoft.skydrive", // OneDrive

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
        "com.discord",
        "com.viber.voip",
        "com.whatsapp.w4b", // WhatsApp Business (regular WhatsApp is whitelisted for parents)

        // AI chat (unrestricted content generation is risky for minors)
        "com.openai.chatgpt",
        "com.google.android.apps.bard", // Gemini
        "ai.perplexity.app.android",
        "com.anthropic.claude",
        "com.microsoft.copilot",
        "ai.character.app", // Character.AI

        // Google Assistant / Google app (search + voice)
        "com.google.android.apps.search",
        "com.google.android.apps.googleassistant",

        // Payments & Wallets (prevent unsupervised purchases)
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.revolut.app",

        // Crypto exchanges (high-risk for minors)
        "com.coinbase.android",
        "com.binance.dev",
        "com.kraken.trade",

        // File managers (sideload & bypass risk)
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.sec.android.app.myfiles",
        "com.mi.android.globalFileexplorer",
        "com.huawei.filemanager",

        // More browsers (common leak paths)
        "com.UCMobile.intl",
        "com.android.browser",
        "com.vivaldi.browser",
        "org.mozilla.focus",

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
        "com.kiddolock.app",
        "android",
        "com.google.android.apps.wellbeing",
        "com.google.android.permissioncontroller",
        "com.android.settings.intelligence"
    )

    private val launcherPackages = HashSet<String>()
    private var lastLauncherUpdate = 0L

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

        // Fresh install detection: if neither the blacklist nor the version
        // key exists, seed the FULL DEFAULT_BLACKLIST before running any
        // migrations. Without this, V5-V10 below would save a partial list
        // to prefs, the post-migration `savedBlacklist != null` branch
        // would then skip the DEFAULT_BLACKLIST seed, and new users would
        // silently end up with social-media / dating / AI apps unblocked.
        val isFreshInstall = !prefs.contains(KEY_BLACKLISTED_APPS) &&
            !prefs.contains("blacklist_version")
        if (isFreshInstall) {
            blacklistedApps.clear()
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
            Log.i(TAG, "Fresh install: seeded ${DEFAULT_BLACKLIST.size} default blocks")
        }

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

        // Migration: V6 (Expand blacklist with all browsers and settings intelligence)
        if (blacklistVersion < 6) {
            blacklistedApps.add("org.mozilla.firefox")
            blacklistedApps.add("com.sec.android.app.sbrowser")
            blacklistedApps.add("com.opera.browser")
            blacklistedApps.add("com.microsoft.emmx")
            blacklistedApps.add("com.brave.browser")
            blacklistedApps.add("com.duckduckgo.mobile.android")
            blacklistedApps.add("com.android.settings") 
            blacklistedApps.add("com.samsung.android.settings") 
            blacklistedApps.add("com.android.settings.intelligence")
            blacklistedApps.add("com.samsung.android.settings.intelligence")
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 6).apply()
        }

        // Migration: V7 (Forced Hardening - Correcting previous version oversights)
        if (blacklistVersion < 7) {
            val critical = listOf(
                "com.android.settings",
                "com.samsung.android.settings",
                "com.android.settings.intelligence",
                "com.samsung.android.settings.intelligence",
                "com.android.chrome",
                "org.mozilla.firefox"
            )
            critical.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 7).apply()
            Log.i(TAG, "Migration V7: Hardened critical app list")
        }

        // Migration: V8 (Gmail & Uninstallation Hardening)
        if (blacklistVersion < 8) {
            val critical = listOf(
                "com.google.android.gm",
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.android.settings",
                "com.samsung.android.settings"
            )
            critical.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 8).apply()
            Log.i(TAG, "Migration V8: Hardened Gmail and Uninstallation guards")
        }

        // Migration: V9 (OneDrive Hardening)
        if (blacklistVersion < 9) {
            blacklistedApps.add("com.microsoft.skydrive")
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 9).apply()
            Log.i(TAG, "Migration V9: Hardened OneDrive")
        }
        // Migration: V10 (Comprehensive Gallery & Media Hardening)
        if (blacklistVersion < 10) {
            val galleries = listOf(
                "com.google.android.apps.photos",
                "com.sec.android.gallery3d",
                "com.android.gallery3d",
                "com.android.gallery",
                "com.miui.gallery",
                "com.huawei.photos",
                "com.coloros.gallery",
                "com.oneplus.gallery",
                "com.google.android.gm",
                "com.microsoft.skydrive"
            )
            galleries.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 10).apply()
            Log.i(TAG, "Migration V10: Performed comprehensive Gallery & Media hardening")
        }

        // Migration: V11 (AI chat + payments/crypto + file managers + Assistant)
        // These categories didn't exist / weren't risky when earlier defaults
        // were drawn. Pushed here so upgraders get them without touching
        // parents' custom allow-list.
        if (blacklistVersion < 11) {
            val v11 = listOf(
                // AI chat
                "com.openai.chatgpt",
                "com.google.android.apps.bard",
                "ai.perplexity.app.android",
                "com.anthropic.claude",
                "com.microsoft.copilot",
                "ai.character.app",
                // Google Assistant / Google app (search + voice)
                "com.google.android.apps.search",
                "com.google.android.apps.googleassistant",
                // Payments & wallets
                "com.google.android.apps.walletnfcrel",
                "com.paypal.android.p2pmobile",
                "com.venmo",
                "com.squareup.cash",
                "com.revolut.app",
                // Crypto
                "com.coinbase.android",
                "com.binance.dev",
                "com.kraken.trade",
                // File managers (sideload risk)
                "com.google.android.documentsui",
                "com.android.documentsui",
                "com.sec.android.app.myfiles",
                "com.mi.android.globalFileexplorer",
                "com.huawei.filemanager",
                // More browsers
                "com.UCMobile.intl",
                "com.android.browser",
                "com.vivaldi.browser",
                "org.mozilla.focus",
                // More social
                "com.discord",
                "com.viber.voip",
                "com.whatsapp.w4b"
            )
            v11.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 11).apply()
            Log.i(TAG, "Migration V11: AI/payments/crypto/file-manager hardening")
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
     * Check if a package is a browser.
     */
    fun isBrowser(packageName: String): Boolean {
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://www.google.com"))
            val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                pm.queryIntentActivities(intent, flags)
            }
            return resolveInfos.any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Check if a package is a launcher (home screen).
     */
    fun isLauncher(packageName: String): Boolean {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) return true
        
        val now = System.currentTimeMillis()
        if (launcherPackages.isEmpty() || now - lastLauncherUpdate > LAUNCHER_CACHE_TTL) {
            updateLauncherCache()
        }
        
        return launcherPackages.contains(packageName)
    }

    private fun updateLauncherCache() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)
            val pm = context.packageManager
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                pm.queryIntentActivities(intent, flags)
            }
            
            launcherPackages.clear()
            resolveInfos.forEach { 
                val pkg = it.activityInfo.packageName
                // PRECISION FILTER: A launcher should NOT be a critical blocked app (like Settings)
                // We check basic patterns here to avoid circular dependencies with AppBlockManager
                val isCriticalPattern = pkg.contains("settings", ignoreCase = true) || 
                                      pkg.contains("packageinstaller", ignoreCase = true) ||
                                      pkg.contains("chrome", ignoreCase = true) ||
                                      pkg.contains("browser", ignoreCase = true)
                
                if (!isCriticalPattern) {
                    launcherPackages.add(pkg)
                }
            }
            lastLauncherUpdate = System.currentTimeMillis()
            Log.d(TAG, "Launcher cache updated (filtered): $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating launcher cache", e)
        }
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
