package com.kiddolock.app.utils

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.services.SafeLockAccessibilityService

/**
 * Centralized permission check utilities.
 *
 * Critical: Previous implementations varied between MainActivity / SetupActivity /
 * HeartbeatWorker — some used `contains(flattenToString())` (fragile, locale-ish)
 * and others used `unflattenFromString + equals` (robust).
 * The inconsistency caused the setup wizard to let the user tap "Continue"
 * while MainActivity immediately redirected back to the wizard → perceived freeze.
 * All callers now route through this utility.
 */
object PermissionUtils {

    private const val TAG = "SAFELOCK_PERM"

    fun hasOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun hasDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(context, KiddoDeviceAdminReceiver::class.java))
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Robust accessibility-service check.
     * Parses the colon-separated list of enabled services and compares each
     * ComponentName to our expected one via proper equality (not substring).
     * Handles both short-form ("pkg/.Class") and long-form notation.
     */
    fun hasAccessibilityService(context: Context): Boolean {
        val expected = ComponentName(context, SafeLockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val raw = splitter.next()
            val parsed = ComponentName.unflattenFromString(raw) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    /**
     * Returns true iff ALL four critical permissions are granted.
     * Notifications permission is optional.
     */
    fun isSetupComplete(context: Context): Boolean {
        val overlay = hasOverlay(context)
        val a11y = hasAccessibilityService(context)
        val admin = hasDeviceAdmin(context)
        val usage = hasUsageAccess(context)
        Log.i(TAG, "setupComplete check: overlay=$overlay a11y=$a11y admin=$admin usage=$usage")
        return overlay && a11y && admin && usage
    }
}
