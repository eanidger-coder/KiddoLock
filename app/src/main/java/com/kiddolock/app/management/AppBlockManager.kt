package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.kiddolock.app.services.TimeScheduler
import android.content.Intent
import android.content.ComponentName
import com.kiddolock.app.utils.Prefs

object AppBlockManager {
    private val temporaryUnlocksCache = mutableMapOf<String, Long>()
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var appPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var isInitialized = false

    private fun ensureInitialized(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        
        val prefs = Prefs(appContext)

        // Sync global suppression with persistent state (survives restarts).
        // SANITY CHECK: clamp absurd values (> 24h) that could come from Android Auto Backup
        // restoring an old "10 year permanent bypass" state. No legitimate flow needs more than 1h.
        val bypassUntil = prefs.emergency_bypass_until
        val now = System.currentTimeMillis()
        val maxValid = now + 60L * 60 * 1000  // at most 1 hour into the future
        if (bypassUntil > maxValid) {
            Log.w("AppBlockManager", "Clamping suspicious bypass timestamp (was ${(bypassUntil-now)/1000}s in future) to 0")
            prefs.emergency_bypass_until = 0L
            isGlobalSuppressed = false
        } else if (now < bypassUntil) {
            isGlobalSuppressed = true
            Log.i("AppBlockManager", "Restored persistent global suppression (active for ${((bypassUntil - now)/1000)}s)")
        } else {
            isGlobalSuppressed = false
        }

        // Listener 1: General settings (PIN, Suppressions, Recovery)
        val sharedPrefs = appContext.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "temporary_unlocks" || key == "admin_pin" || key == "emergency_bypass_until" || key == "instant_lock") {
                Log.d("AppBlockManager", "Persistent setting changed: $key. Clearing cache.")
                invalidateCache()
                loadTemporaryUnlocksFromPrefs(appContext)
                
                // Real-time sync of suppression state
                if (key == "emergency_bypass_until") {
                    val newBypass = sharedPrefs.getLong("emergency_bypass_until", 0L)
                    isGlobalSuppressed = System.currentTimeMillis() < newBypass
                }
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Listener 2: App-specific settings (Blacklist, Toggle) - separate file
        val appPrefs = appContext.getSharedPreferences("kiddolock_app_prefs", Context.MODE_PRIVATE)
        appPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "blacklisted_apps" || key == "app_blocking_enabled") {
                Log.d("AppBlockManager", "App-specific setting changed: $key. Clearing cache.")
                invalidateCache()
            }
        }
        appPrefs.registerOnSharedPreferenceChangeListener(appPrefsListener)

        loadTemporaryUnlocksFromPrefs(appContext)
        isInitialized = true
    }

    private fun loadTemporaryUnlocksFromPrefs(context: Context) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val saved = prefs.temporaryUnlocks
        temporaryUnlocksCache.clear()
        
        val validEntries = mutableSetOf<String>()
        saved.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val pkg = parts[0]
                val expiry = parts[1].toLongOrNull() ?: 0L
                if (now < expiry) {
                    temporaryUnlocksCache[pkg] = expiry
                    validEntries.add(entry)
                }
            }
        }
        
        if (validEntries.size != saved.size) {
            prefs.temporaryUnlocks = validEntries
        }
    }

    @Volatile
    var isGlobalSuppressed: Boolean = false


    // --- CACHED instances to avoid re-creating on every event ---
    private var cachedAppManager: AppManager? = null
    private var cachedKidsModeManager: KidsModeManager? = null
    private var cacheTimestamp: Long = 0L
    private const val CACHE_TTL_MS = 30_000L // Refresh every 30 seconds

    /**
     * Get or create a cached AppManager instance.
     * Avoids re-reading SharedPreferences on every accessibility event.
     */
    internal fun getAppManager(context: Context): AppManager {
        val now = System.currentTimeMillis()
        val mgr = cachedAppManager
        if (mgr != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return mgr
        }
        // Cache expired or first call — create new instance
        val newMgr = AppManager(context.applicationContext).apply { initialize() }
        cachedAppManager = newMgr
        cacheTimestamp = now
        Log.d("AppBlockManager", "AppManager cache refreshed (${newMgr.getBlacklistedCount()} apps blacklisted)")
        return newMgr
    }

    private fun getKidsModeManager(context: Context): KidsModeManager {
        val mgr = cachedKidsModeManager
        if (mgr != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
            return mgr
        }
        val newMgr = KidsModeManager(context.applicationContext)
        cachedKidsModeManager = newMgr
        return newMgr
    }

    /** Force-refresh the cache (call when user changes settings in Admin panel) */
    fun invalidateCache() {
        cachedAppManager = null
        cachedKidsModeManager = null
        cacheTimestamp = 0L
        Log.i("AppBlockManager", "Cache invalidated — will refresh on next check")
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean {
        // Safety: NEVER block our own app
        if (packageName == context.packageName) return false

        // CRITICAL: NEVER block keyboards/IMEs/TTS - they are popups inside other apps.
        // If we block them, every text field in WhatsApp/Messages/etc gets a white page.
        if (isInputMethodPackage(context, packageName)) {
            Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: Input Method (keyboard/voice)")
            return false
        }

        ensureInitialized(context)

        // 0. Check for global suppression (Emergency bypass)
        if (isGlobalSuppressed) {
            Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: Global Suppression (Emergency Bypass) is ACTIVE")
            return false
        }

        val appManager = getAppManager(context)
        val kidsModeManager = getKidsModeManager(context)
        val isKidsModeEnabled = kidsModeManager.isEnabled

        // 0.04 CORE WHITELISTS (System Protected) - ALWAYS ALLOW
        // We check this BEFORE the critical app safeguard to prevent blocking the home screen or system UI.
        if (appManager.isSystemProtected(packageName) || appManager.isLauncher(packageName)) {
            Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: CORE system app or Launcher")
            return false
        }


        if (!isKidsModeEnabled) {
            Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: Kids Mode is DISABLED (Master Switch)")
            return false
        }

        // 0.1 Check for temporary unlock (Parent PIN override — HIGHEST PRIORITY)
        val now = System.currentTimeMillis()
        val expiry = temporaryUnlocksCache[packageName]
        if (expiry != null) {
            if (now < expiry) {
                Log.d("AppBlockManager", "[DECISION] ALLOW $packageName: Temporarily UNLOCKED (expires in ${(expiry - now) / 1000}s)")
                return false  // Temporary unlock overrides EVERYTHING
            } else {
                temporaryUnlocksCache.remove(packageName)
                saveTemporaryUnlocksToPrefs(context)
                Log.i("AppBlockManager", "Temporary unlock EXPIRED for $packageName")
            }
        }

        try {
            val appManager = getAppManager(context)

            // 0.2 Kids Mode Default Protections (Priority over system whitelist for launchers)
            if (kidsModeManager.isEnabled) {
                // TIER 1 - ALWAYS ALLOWED (dialer, SMS, contacts, WhatsApp, ourselves).
                // Bypass even bedtime and daily limit because safety-critical.
                if (appManager.ESSENTIAL_APPS_WHITELIST.contains(packageName)) {
                    Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: ESSENTIAL - always accessible")
                    return false
                }

                // TIER 2 - KID-FRIENDLY (YouTube Kids). Skip the blacklist+browser check, but
                // FALL THROUGH to bedtime/daily-limit so it still gets blocked at night.
                val isKidFriendly = appManager.KIDS_FRIENDLY_WHITELIST.contains(packageName)
                if (isKidFriendly) {
                    Log.v("AppBlockManager", "[DECISION] passthrough kid-friendly $packageName - will check bedtime/limit")
                    // skip blacklist/browser checks below, jump straight to time check
                } else {

                // In Kids Mode, we check the standard blacklist.
                if (appManager.isBlacklisted(packageName)) {
                    Log.d("AppBlockManager", "[DECISION] BLOCK $packageName: Specifically blacklisted in Kids Mode")
                    return true
                }

                // 0.2 Check for Critical Safeguards (moved after bypass)
                if (isCriticalApp(packageName)) {
                    Log.w("AppBlockManager", "[DECISION] BLOCK $packageName: Critical app restriction (Kids Mode)")
                    return true
                }

                // REMOVED: isBrowser() auto-block was too aggressive - it matched any app
                // that handles HTTP intents (ChatGPT, Google Translate, Maps, etc.). Real
                // browsers (Chrome, Firefox, Edge, Brave, etc.) are already in DEFAULT_BLACKLIST,
                // so the explicit blacklist is enough. No more false positives.

                Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: Passing Kids Mode whitelist")
                // fall through to other checks (like bedtime) if they apply
                } // end else for kid-friendly
            }

            // 1. Check if it's a CORE critical system app
            if (appManager.CORE_SYSTEM_WHITELIST.contains(packageName)) {
                Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: CORE system app")
                return false
            }

            // 2. Check if the app is explicitly blacklisted
            val isBlacklisted = appManager.isBlacklisted(packageName)
            if (isBlacklisted) {
                Log.d("AppBlockManager", "[DECISION] BLOCK $packageName: User-blacklisted")
                return true
            }

            // 3. Check Global Restrictions (Time Limits, Bedtime, Instant Lock)
            val timeScheduler = TimeScheduler(context)
            if (timeScheduler.isCurrentlyRestricted()) {
                // EXCEPTION: Essential apps allowed during time restrictions 
                if (appManager.ESSENTIAL_APPS_WHITELIST.contains(packageName)) {
                    Log.v("AppBlockManager", "[DECISION] ALLOW essential app $packageName during restricted time")
                    return false
                }
                
                val reason = timeScheduler.getRestrictionReason() ?: "Global restriction"
                Log.d("AppBlockManager", "[DECISION] BLOCK $packageName: $reason")
                return true
            }

            Log.v("AppBlockManager", "[DECISION] PASS: No restrictions for $packageName")
            return false
        } catch (e: Exception) {
            Log.e("AppBlockManager", "CRITICAL ERROR checking $packageName", e)
            return false
        }
    }

    private fun saveTemporaryUnlocksToPrefs(context: Context) {
        val prefs = Prefs(context)
        val entries = temporaryUnlocksCache.map { "${it.key}|${it.value}" }.toSet()
        prefs.temporaryUnlocks = entries
    }

    /** Check if a package is currently temporarily unlocked (for BypassGuard coordination) */
    fun isTemporarilyUnlocked(packageName: String): Boolean {
        val expiry = temporaryUnlocksCache[packageName] ?: return false
        return System.currentTimeMillis() < expiry
    }

    /** Unlocks an app temporarily (default 5 minutes) */
    fun temporaryUnlock(context: Context, packageName: String, durationMs: Long = 5 * 60 * 1000L) {
        ensureInitialized(context)
        val expiry = System.currentTimeMillis() + durationMs
        temporaryUnlocksCache[packageName] = expiry
        saveTemporaryUnlocksToPrefs(context)
        Log.i("AppBlockManager", "TEMPORARY UNLOCK granted for $packageName for ${durationMs / 60000} minutes")
    }

    /** Expert QA Reset: Clears all temporary unlocks to force immediate blocking */
    fun resetTemporaryUnlocks(context: Context) {
        ensureInitialized(context)
        temporaryUnlocksCache.clear()
        saveTemporaryUnlocksToPrefs(context)
        Log.w("AppBlockManager", "EXPERT QA: All temporary unlocks CLEARED")
    }

    /**
     * Resets ALL temporary overrides (Global Suppression and Temporary App Unlocks).
     * Used when re-enabling Kids Mode to ensure immediate maximum protection.
     */
    fun clearAllBypasses(context: Context) {
        ensureInitialized(context)
        isGlobalSuppressed = false
        temporaryUnlocksCache.clear()
        
        val prefs = Prefs(context)
        prefs.emergency_bypass_until = 0
        prefs.temporaryUnlocks = emptySet()
        
        invalidateCache()
        Log.i("AppBlockManager", "CLEAN SLATE: All temporary bypasses and suppressions CLEARED.")
    }


    fun isLocked(context: Context): Boolean {
        val timeScheduler = TimeScheduler(context)
        return timeScheduler.getConfig().isInstantLocked
    }

    fun setInstantLock(context: Context, locked: Boolean) {
        val timeScheduler = TimeScheduler(context)
        timeScheduler.setInstantLock(locked)
    }

    fun neutralize(context: Context) {
        isGlobalSuppressed = true
        Log.w("AppBlockManager", "EMERGENCY NEUTRALIZATION: All protections disabled for 2 hours")
        
        Handler(Looper.getMainLooper()).postDelayed({
            isGlobalSuppressed = false
            Log.i("AppBlockManager", "Emergency neutralization EXPIRED. Protection restored.")
        }, 2 * 60 * 60 * 1000L) // 2 hours
    }

    fun uninstallSelf(context: Context) {
        Log.w("AppBlockManager", "EMERGENCY UNINSTALL triggered")
        
        // 0. Set flag to allow bypass during uninstall
        Prefs(context).certified_uninstall_in_progress = true
        
        // 1. Remove device admin if active
        val admin = android.content.ComponentName(context, com.kiddolock.app.receivers.KiddoDeviceAdminReceiver::class.java)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.removeActiveAdmin(admin)
        
        // 2. Trigger uninstall intent
        val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:" + context.packageName)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // Cached IME list (refreshed when null or stale)
    private var imePackagesCache: Set<String>? = null
    private var imeCacheTimeMs: Long = 0L
    private const val IME_CACHE_TTL_MS = 60_000L  // refresh every minute

    /**
     * Returns true if the package is an Input Method Editor (keyboard/voice input service).
     * KiddoLock must NEVER block IMEs - they're not user-launched apps; they pop up inside
     * other apps when the user taps a text field. Blocking them shows a white page.
     */
    private fun isInputMethodPackage(context: Context, packageName: String): Boolean {
        // Fast path: well-known keyboards
        val hardcoded = setOf(
            "com.touchtype.swiftkey",                          // SwiftKey
            "com.google.android.inputmethod.latin",            // Gboard
            "com.google.android.tts",                          // Google TTS / voice
            "com.samsung.android.honeyboard",                  // Samsung Keyboard (modern)
            "com.sec.android.inputmethod",                     // Samsung Keyboard (legacy)
            "com.sec.android.inputmethod.beta",
            "com.samsung.android.svoiceime",                   // Samsung voice input
            "kr.co.iconnect.mh_global",                        // Multiling O keyboard
            "com.menny.android.anysoftkeyboard",               // AnySoftKeyboard
            "com.android.inputmethod.latin",                   // AOSP Latin IME
            "com.android.inputmethod.pinyin"
        )
        if (hardcoded.contains(packageName)) return true

        // Dynamic detection: ask InputMethodManager for enabled IMEs
        try {
            val now = System.currentTimeMillis()
            if (imePackagesCache == null || (now - imeCacheTimeMs) > IME_CACHE_TTL_MS) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                val list = imm?.inputMethodList ?: emptyList()
                imePackagesCache = list.map { it.packageName }.toSet()
                imeCacheTimeMs = now
            }
            return imePackagesCache?.contains(packageName) == true
        } catch (e: Throwable) {
            return false
        }
    }

    fun setGlobalSuppression(context: Context, suppressed: Boolean, isPermanent: Boolean = false) {
        isGlobalSuppressed = suppressed
        val prefs = Prefs(context)
        if (suppressed) {
            // CRITICAL FIX: cap the "permanent" suppression at 1 hour. The previous 10-year
            // value was being persisted across app starts and silently disabled all blocking
            // for the device's lifetime if the parent ever triggered an emergency uninstall
            // and then canceled (chose "מאוחר יותר"). 1 hour gives plenty of time to uninstall
            // without leaving the device permanently unprotected.
            val duration = if (isPermanent) 60L * 60 * 1000L else 10 * 60 * 1000L
            prefs.emergency_bypass_until = System.currentTimeMillis() + duration
            Log.i("AppBlockManager", "Global suppression ENABLED (${if (isPermanent) "1 hour" else "10 min"})")
        } else {
            prefs.emergency_bypass_until = 0
            Log.i("AppBlockManager", "Global suppression DISABLED")
        }
    }

    /**
     * Force-clear any active global suppression. Called when the parent re-enables Kids Mode
     * after toggling it off, so a leftover bypass timer doesn't silently keep blocking off.
     */
    fun clearGlobalSuppression(context: Context) {
        isGlobalSuppressed = false
        Prefs(context).emergency_bypass_until = 0
        Log.i("AppBlockManager", "Global suppression FORCE-CLEARED by user action")
    }

    /** Expert QA Hook: Dumps current configuration state to Logcat */
    fun dumpState(context: Context) {
        val appManager = getAppManager(context)
        val timeScheduler = TimeScheduler(context)
        val prefs = Prefs(context)
        
        Log.i("AppBlockManager", "=== EXPERT QA STATE DUMP ===")
        Log.i("AppBlockManager", "Package: ${context.packageName}")
        Log.i("AppBlockManager", "Global Suppression: $isGlobalSuppressed")
        Log.i("AppBlockManager", "Bypass Until: ${prefs.emergency_bypass_until} (Current: ${System.currentTimeMillis()})")
        Log.i("AppBlockManager", "Blacklisted Count: ${appManager.getBlacklistedCount()}")
        Log.i("AppBlockManager", "Current Time Restricted: ${timeScheduler.isCurrentlyRestricted()}")
        Log.i("AppBlockManager", "Bedtime Active: ${timeScheduler.isBedtimeActive()}")
        Log.i("AppBlockManager", "Daily Limit Reached: ${timeScheduler.isDailyLimitReached()}")
        Log.i("AppBlockManager", "Instant Lock: ${prefs.instant_lock}")
        Log.i("AppBlockManager", "Temporary Unlocks: ${temporaryUnlocksCache.keys}")
        Log.i("AppBlockManager", "============================")
    }

    /**
     * Identifies critical apps that must remain blocked in Kids Mode regardless of user settings.
     * Includes: Settings, App Stores, Package Installers, and major Browsers.
     */
    /**
     * Identifies critical apps that must remain blocked in Kids Mode regardless of user settings.
     * Includes: Settings, App Stores, Package Installers, and major Browsers.
     * This is PUBLIC so the Accessibility Service can use it for its fast safeguard.
     */
    fun isCriticalApp(packageName: String): Boolean {
        // SAFETY FIX (v1.5.54 - post-driving incident, 2026-05-19):
        // Previous version used p.contains("browser") as a broad fallback which could match
        // any package with "browser" anywhere in its name, including unrelated apps that
        // happen to bundle a WebView component. Replaced with an exact allowlist.
        // If a new browser appears, add its exact package name explicitly.
        val p = packageName.lowercase()
        val exactCritical = setOf(
            "com.android.vending",
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.sec.android.app.samsungapps",
            "com.samsung.android.sm",
            "com.samsung.android.lool",
            // Browsers - exact package names only
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.sec.android.app.sbrowser",
            "com.android.browser",
            "org.torproject.torbrowser",
            "com.yandex.browser",
            "com.uc.browser.en",
            "com.uc.browser.us",
            "com.kiwibrowser.browser"
        )
        if (exactCritical.contains(p)) return true
        // Narrow prefix/suffix checks - intentionally specific
        return p.startsWith("com.android.settings.") ||
               p.startsWith("com.samsung.android.settings.") ||
               p.endsWith(".packageinstaller")
    }
}
