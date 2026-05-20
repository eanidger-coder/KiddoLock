package com.kiddolock.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-starts KiddoLock services on device boot.
 * Ensures protection resumes automatically.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KiddoBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != "com.htc.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Log.i(TAG, "Boot received: ${intent.action}")

        // CRASH FIX (v1.5.58): During LOCKED_BOOT_COMPLETED (before the user unlocks the
        // device for the first time after reboot), credential-encrypted SharedPreferences are
        // NOT available and any access throws IllegalStateException - which crashed BootReceiver.
        // We now read prefs from device-protected storage and wrap everything defensively so a
        // boot crash can never happen. The accessibility service restarts on its own regardless.
        val isLockedBoot = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        // Heartbeat scheduling does not need credential storage - safe to run always.
        try {
            com.kiddolock.app.management.HeartbeatWorker.schedule(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to schedule heartbeat after boot", e)
        }

        // Reading the disable flag requires storage. Use device-protected storage so it works
        // even during locked boot; fall back gracefully if anything is unavailable.
        try {
            val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val prefs = safeContext.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("disable_all_filters", false)) {
                Log.i(TAG, "Filters disabled by user — skipping auto-start")
                return
            }
        } catch (e: Throwable) {
            // Storage not ready yet (locked boot) — that's fine. The accessibility service
            // restarts automatically once the user unlocks. Never crash here.
            Log.w(TAG, "Prefs not available at boot (${if (isLockedBoot) "locked boot" else "boot"}) — relying on auto-restart: ${e.message}")
        }

        // Note: Accessibility Service cannot be started programmatically —
        // it retains its enabled state across reboots and Android restarts it automatically
        // once the user unlocks the device.
    }
}
