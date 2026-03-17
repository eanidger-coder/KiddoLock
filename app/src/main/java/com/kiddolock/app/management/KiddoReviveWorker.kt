package com.kiddolock.app.management

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.work.*
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.utils.NotificationUtils
import com.kiddolock.app.utils.Prefs
import java.util.concurrent.TimeUnit

/**
 * KiddoReviveWorker — Runs every 15 minutes to:
 * 1. Re-enable protection after emergency bypass expires
 * 2. Check Device Admin + Accessibility health
 * 3. Fire urgent notifications if protection is down
 */
class KiddoReviveWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "KiddoReviveWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<KiddoReviveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "KiddoLock_KiddoRevive",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override fun doWork(): Result {
        Log.i(TAG, "Running protection health check...")
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        // 1. Re-enable protections if emergency bypass expired
        if (prefs.emergency_bypass_until > 0 && System.currentTimeMillis() >= prefs.emergency_bypass_until) {
            ctx.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("disable_all_filters", false)
                .putLong("emergency_bypass_until", 0)
                .apply()
            Log.i(TAG, "Emergency bypass expired — protection re-enabled")
        }

        // 2. Skip health checks if certified uninstall is in progress
        if (prefs.certified_uninstall_in_progress) {
            Log.i(TAG, "Certified uninstall in progress — skipping health checks")
            return Result.success()
        }

        // 3. Check Device Admin status
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(ctx, KiddoDeviceAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(adminComponent)

        // 4. Check Accessibility Service status
        val isAccessibilityActive = isAccessibilityServiceRunning(ctx)

        // 5. Fire notifications for any issues
        val issues = mutableListOf<String>()
        if (!isAdminActive) issues.add("מנהל מכשיר כבוי")
        if (!isAccessibilityActive) issues.add("שירות נגישות כבוי")

        if (issues.isNotEmpty()) {
            val message = "ההגנה מושבתת: ${issues.joinToString(", ")}. לחץ לתיקון."
            NotificationUtils.showSetupIncompleteNotification(ctx, message)
            Log.w(TAG, "Protection issues detected: $issues")
        } else {
            Log.i(TAG, "All protection layers healthy")
        }

        return Result.success()
    }

    private fun isAccessibilityServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { 
            it.resolveInfo?.serviceInfo?.packageName == context.packageName 
        }
    }
}
