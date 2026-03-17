package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.kiddolock.app.services.TimeScheduler
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

        // Sync global suppression with persistent state (survives restarts)
        val bypassUntil = prefs.emergency_bypass_until
        if (System.currentTimeMillis() < bypassUntil) {
            isGlobalSuppressed = true
            Log.i("AppBlockManager", "Restored persistent global suppression (active for ${((bypassUntil - System.currentTimeMillis())/1000)}s)")
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
        
        ensureInitialized(context)

        // 0. Check for global suppression (Emergency bypass)
        if (isGlobalSuppressed) {
            Log.v("AppBlockManager", "[DECISION] ALLOW $packageName: Global Suppression (Emergency Bypass) is ACTIVE")
            return false
        }

        // 0.05 Check if Kids Mode is ENABLED (Master Switch)
        val kidsModeManager = getKidsModeManager(context)
        if (!kidsModeManager.isEnabled) {
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

            // 4. Check Kids Mode Default Protections
            val kidsModeManager = getKidsModeManager(context)
            if (kidsModeManager.isProtectedInKidsMode(packageName)) {
                Log.d("AppBlockManager", "[DECISION] BLOCK $packageName: Kids Mode Protection")
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

    fun setGlobalSuppression(context: Context, suppressed: Boolean) {
        isGlobalSuppressed = suppressed
        val prefs = Prefs(context)
        if (suppressed) {
            prefs.emergency_bypass_until = System.currentTimeMillis() + (10 * 60 * 1000L)
            Log.i("AppBlockManager", "Global suppression ENABLED (10 min)")
        } else {
            prefs.emergency_bypass_until = 0
            Log.i("AppBlockManager", "Global suppression DISABLED")
        }
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
}
