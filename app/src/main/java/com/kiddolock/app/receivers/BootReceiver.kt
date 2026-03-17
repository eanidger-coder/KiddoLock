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

        Log.i(TAG, "Boot completed — checking if KiddoLock should auto-start")

        val prefs = context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
        
        // Don't start if user explicitly disabled everything
        if (prefs.getBoolean("disable_all_filters", false)) {
            Log.i(TAG, "Filters disabled by user — skipping auto-start")
            return
        }

        // Note: Accessibility Service cannot be started programmatically —
        // it retains its enabled state across reboots automatically.
        // If the user enabled it before reboot, Android will restart it.
        // it retains its enabled state across reboots automatically.
        // If the user enabled it before reboot, Android will restart it.
    }
}
