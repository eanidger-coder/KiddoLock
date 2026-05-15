package com.kiddolock.app.receivers

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

object PolicyManager {
    private const val TAG = "KiddoPolicy"

    fun isUninstallBlocked(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, KiddoDeviceAdminReceiver::class.java)
        return try {
            if (dpm.isAdminActive(adminComponent)) {
                // Check DPM-level block (only works if Device Owner)
                val dpmBlocked = try {
                    dpm.isUninstallBlocked(adminComponent, context.packageName)
                } catch (_: Exception) { false }
                
                // Also check our own pref-based flag  
                val prefBlocked = context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
                    .getBoolean("uninstall_protection_enabled", true)
                
                dpmBlocked || prefBlocked
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking uninstall block", e)
            false
        }
    }

    /**
     * Enforce policies. Works in two tiers:
     * - Tier 1 (Device Admin): Always available — basic protection
     * - Tier 2 (Device Owner): Only on provisioned devices — full restrictions
     */
    fun enforcePolicies(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Admin not active, cannot enforce policies")
            return
        }

        // --- Tier 1: Always works with Device Admin ---
        // Save our own preference flag (this ALWAYS works)
        context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("uninstall_protection_enabled", true)
            .apply()
        Log.i(TAG, "Tier 1: Preference-based uninstall protection enabled")

        // --- Tier 2: Only works if Device Owner ---
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not Device Owner — Tier 2 policies skipped (this is normal for regular installs)")
            return
        }

        try {
            val restrictions = arrayOf(
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_CONFIG_VPN,
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_SET_WALLPAPER,
                UserManager.DISALLOW_CONFIG_DATE_TIME,
                UserManager.DISALLOW_CONFIG_BRIGHTNESS
            )

            for (restriction in restrictions) {
                dpm.addUserRestriction(adminComponent, restriction)
            }

            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
            Log.i(TAG, "Tier 2: Device Owner policies enforced!")
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing Tier 2 policies", e)
        }
    }

    fun clearPolicies(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        
        // Always clear our pref flag
        context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("uninstall_protection_enabled", false)
            .apply()
        Log.i(TAG, "Preference-based uninstall protection disabled")

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf())
            
            val restrictions = arrayOf(
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            )

            for (restriction in restrictions) {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
            
            dpm.setUninstallBlocked(adminComponent, context.packageName, false)
            Log.i(TAG, "Tier 2 policies cleared!")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing policies", e)
        }
    }

    /**
     * Remove Device Admin entirely. Used during emergency uninstall.
     */
    fun removeDeviceAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, KiddoDeviceAdminReceiver::class.java)
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                Log.i(TAG, "Device Admin removed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Device Admin", e)
        }
    }
}
