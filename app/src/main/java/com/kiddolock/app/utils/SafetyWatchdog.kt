package com.kiddolock.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.KidsModeManager

/**
 * SafetyWatchdog - The last line of defense against KiddoLock locking a child inside the app.
 *
 * What it does:
 *  1. Catches every uncaught exception in the entire process via Thread.setDefaultUncaughtExceptionHandler.
 *  2. Counts crashes in a rolling 10-minute window via SharedPreferences (survives process death).
 *  3. If 3 crashes happen within 10 minutes, automatically:
 *     - Disables Kids Mode (master switch OFF)
 *     - Enables global block suppression (no app will be blocked)
 *     - Writes a flag so the parent sees a clear notification on next launch
 *  4. After 30 minutes without crashes, the counter resets so a parent who has fixed the
 *     environment doesn't permanently lose protection.
 *
 * This guarantees: even if the accessibility service, overlay service, or any other component
 * crashes repeatedly, the child can always reach Settings → Apps → KiddoLock → Uninstall.
 */
object SafetyWatchdog {

    private const val TAG = "SafetyWatchdog"
    private const val PREFS_NAME = "kiddolock_safety_prefs"
    private const val KEY_CRASH_TIMES = "crash_times_csv"
    private const val KEY_AUTO_DISABLED_AT = "auto_disabled_at"
    private const val KEY_LAST_CRASH_MSG = "last_crash_msg"

    // CRIT-1 fix: lower threshold from 3/10min to 2/5min so the parent doesn't
    // get stuck for 5 minutes during a crash loop before the watchdog kicks in.
    private const val CRASH_THRESHOLD = 2
    private const val CRASH_WINDOW_MS = 5L * 60 * 1000           // 5 minutes
    private const val COUNTER_RESET_AFTER_MS = 30L * 60 * 1000   // reset history after 30 min calm

    @Volatile private var installed = false

    /**
     * Install the watchdog. Must be called from Application.onCreate() before anything else.
     * Safe to call multiple times - only installs once.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrashAndMaybeDisable(appContext, throwable)
            } catch (e: Throwable) {
                // Never let the watchdog itself crash the crash handler
                Log.e(TAG, "Watchdog failed while handling crash", e)
            }
            // Always delegate to the previous handler so Android can do its job (logcat, ANR, etc.)
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) { }
        }

        Log.i(TAG, "SafetyWatchdog installed - threshold $CRASH_THRESHOLD crashes / ${CRASH_WINDOW_MS / 60000}min")
    }

    /**
     * Check at startup whether the watchdog has disabled protection in a previous run.
     * Returns true if protection was auto-disabled and parent should be notified.
     */
    fun wasAutoDisabledRecently(context: Context): Boolean {
        val prefs = prefs(context)
        val ts = prefs.getLong(KEY_AUTO_DISABLED_AT, 0L)
        return ts > 0L && (System.currentTimeMillis() - ts) < 24L * 60 * 60 * 1000  // notify within 24h
    }

    /** Clear the auto-disabled flag once parent has acknowledged it. */
    fun clearAutoDisabledFlag(context: Context) {
        prefs(context).edit().remove(KEY_AUTO_DISABLED_AT).remove(KEY_LAST_CRASH_MSG).apply()
    }

    fun getLastCrashMessage(context: Context): String? {
        return prefs(context).getString(KEY_LAST_CRASH_MSG, null)
    }

    /**
     * Record a crash with the current timestamp, prune old crashes outside the window,
     * and disable protection if the threshold is exceeded.
     */
    private fun recordCrashAndMaybeDisable(context: Context, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)

        // Load existing timestamps and prune those outside the window
        val raw = prefs.getString(KEY_CRASH_TIMES, "") ?: ""
        val recent = raw.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { (now - it) <= CRASH_WINDOW_MS }
            .toMutableList()
        recent.add(now)

        val newCsv = recent.joinToString(",")
        prefs.edit()
            .putString(KEY_CRASH_TIMES, newCsv)
            .putString(KEY_LAST_CRASH_MSG, throwable.javaClass.simpleName + ": " + (throwable.message ?: "no message"))
            .apply()

        Log.w(TAG, "Crash recorded. Count in window: ${recent.size} / $CRASH_THRESHOLD")

        if (recent.size >= CRASH_THRESHOLD) {
            autoDisableProtection(context, recent.size)
        }
    }

    /**
     * Hard fail-safe: turn off Kids Mode and globally suppress blocks so the parent
     * (or anyone) can navigate to Settings and uninstall.
     */
    private fun autoDisableProtection(context: Context, crashCount: Int) {
        Log.e(TAG, "EMERGENCY: $crashCount crashes in 5 min - DISABLING ALL PROTECTION")
        try {
            // Master switch OFF
            KidsModeManager(context).isEnabled = false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to disable Kids Mode", e)
        }
        try {
            // Belt-and-suspenders: globally suppress blocks
            AppBlockManager.setGlobalSuppression(context, true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enable global suppression", e)
        }
        try {
            // Set extended bypass via Prefs so the next process start still has it
            Prefs(context).emergency_bypass_until = System.currentTimeMillis() + (60L * 60 * 1000)  // 1 hour
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set emergency bypass", e)
        }
        try {
            // CRIT-1: also remove Device Admin so the user can uninstall normally via Settings.
            // Without this, after auto-disable the parent still can't remove KiddoLock until
            // they manually deactivate Device Admin first.
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(context, com.kiddolock.app.receivers.KiddoDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin)
                Log.w(TAG, "Device Admin removed - parent can now uninstall via Settings")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to remove Device Admin", e)
        }
        prefs(context).edit()
            .putLong(KEY_AUTO_DISABLED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Called from a successful, normal app launch. If enough quiet time has passed,
     * clear the crash history so a previously-shaky environment that has stabilised
     * doesn't keep auto-disabling protection.
     */
    fun considerResetingOnHealthyLaunch(context: Context) {
        try {
            val prefs = prefs(context)
            val raw = prefs.getString(KEY_CRASH_TIMES, "") ?: ""
            val now = System.currentTimeMillis()
            val recent = raw.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
            val lastCrash = recent.maxOrNull() ?: return
            if (now - lastCrash > COUNTER_RESET_AFTER_MS) {
                prefs.edit().remove(KEY_CRASH_TIMES).apply()
                Log.i(TAG, "Crash history cleared after ${(now - lastCrash) / 60000} min of stability")
            }
        } catch (_: Throwable) { }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
