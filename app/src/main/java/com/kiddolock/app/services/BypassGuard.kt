package com.kiddolock.app.services

import android.content.Context
import android.util.Log
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.AppManager
import com.kiddolock.app.utils.Prefs

/**
 * Bypass prevention guard that monitors for user attempts to
 * circumvent KiddoLock protection. Integrates with the
 * Accessibility Service to detect and block:
 * - Navigation to Settings to disable Accessibility Service
 * - Attempts to uninstall KiddoLock
 * - Opening blacklisted apps (via AppManager)
 * - Disabling device admin
 */
class BypassGuard(private val context: Context) {

    companion object {
        private const val TAG = "BypassGuard"
    }

    // Settings activities that could be used to disable our services
    private val BLOCKED_SETTINGS_ACTIVITIES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.InstalledAccessibilityServiceSettings",
        "com.android.settings.applications.InstalledAppDetails",
        "com.android.settings.applications.ManageApplications",
        "com.android.settings.DeviceAdminSettings",
        "com.android.settings.DeviceAdminAdd",
        "com.android.settings.Settings\$NotificationAppListActivity",
        "com.android.settings.Settings\$NotificationAccessSettingsActivity",
        "com.android.settings.Settings\$AppNotificationSettingsActivity",
        "com.android.settings.Settings\$AdvancedAppsActivity",
        "com.android.settings.Settings\$HighPowerApplicationsActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity",
        "com.android.settings.Settings\$AccessibilityInstalledServiceActivity",
        "com.android.settings.SubSettings", 
        "com.samsung.android.settings.accessibility.AccessibilitySettings",
        "com.sec.android.app.capability.CapabilityActivity",
        "com.miui.securitycenter.permission.AppPermissionsEditor",
        "com.android.settings.Settings\$DeviceAdminSettingsActivity",
        "com.samsung.android.settings.accessibility.AccessibilityInstalledServiceActivity",
        "com.samsung.android.settings.security.DeviceAdminSettings",
        "com.miui.securitycenter.activity.SettingsActivity",
        "com.oppo.settings.Settings\$AccessibilitySettingsActivity",
        "com.android.settings.DeviceAdminAdd",
        "com.android.settings.Settings\$ManageAppExternalSourcesActivity",
        "com.android.settings.Settings\$ManageAppOverlayActivity",
        "com.android.settings.Settings\$UsageAccessSettingsActivity",
        "com.samsung.android.settings.display.EdgePanelSettingsActivity",
        "com.android.launcher3.SettingsActivity",
        "com.android.launcher3.SettingActivity",
        "com.google.android.apps.nexuslauncher.SettingsActivity",
        "com.miui.home.launcher.SettingsActivity"
    )

    // Package names for Settings and app management
    private val SETTINGS_PACKAGE = "com.android.settings"

    /**
     * Initialize guard.
     * Called on startup.
     */
    fun initialize() {
        Log.i(TAG, "BypassGuard initialized.")
    }

    /**
     * Check if a package/activity switch should be intercepted.
     * Returns a BypassAction describing what to do.
     */
    fun checkNavigation(packageName: String, className: String? = null): BypassAction {
        val prefs = Prefs(context)
        val kidsModeManager = com.kiddolock.app.management.KidsModeManager(context)
        
        // 0. HIGH PRIORITY EXCEPTION: Always allow KiddoLock's own activities
        if (packageName == context.packageName) {
            Log.d(TAG, "Exempting self-navigation: $className")
            return BypassAction.ALLOW
        }

        // 0.0 Check for Intentional Uninstall Flag
        if (prefs.certified_uninstall_in_progress) {
            Log.w(TAG, "Intentional uninstall in progress - allowing all settings access")
            return BypassAction.ALLOW
        }

        // 0. Per-package temporary unlock (PIN-approved single-app access for 5 minutes).
        // CRITICAL: must come BEFORE the emergency_bypass check so a Settings PIN unlock
        // does NOT cascade into a global free-pass.
        if (com.kiddolock.app.management.AppBlockManager.isTemporarilyUnlocked(packageName)) {
            Log.i(TAG, "Temporary per-app unlock active for $packageName - allowing")
            return BypassAction.ALLOW
        }

        // 0a. Check for Emergency Bypass (10-min global pause, explicit parent action only)
        if (System.currentTimeMillis() < prefs.emergency_bypass_until) {
            Log.i(TAG, "Emergency bypass active - allowing all")
            return BypassAction.ALLOW
        }

        // If filters are disabled, allow everything
        if (prefs.disable_all_filters) {
            return BypassAction.ALLOW
        }

        // Check if bypass guard is enabled
        if (!prefs.bypass_guard_enabled) {
            return BypassAction.ALLOW
        }

        // 1. Check if user is trying to access Accessibility Settings or Device Admin to disable us
        val settingsPackages = setOf(
            SETTINGS_PACKAGE, 
            "com.google.android.settings", 
            "com.android.settings.intelligence",
            "com.samsung.android.settings",
            "com.samsung.android.lool", // Samsung Device Care
            "com.samsung.android.sm", // Samsung Smart Manager
            "com.miui.securitycenter",
            "com.miui.settings",
            "com.miui.securityadd",
            "com.oppo.settings",
            "com.coloros.settings",
            "com.vivo.setupwizard",
            "com.oneplus.settings",
            "com.huawei.systemmanager",
            "com.huawei.settings"
        )
        
        val isSettingsPackage = settingsPackages.any { packageName.contains(it) }
        
        if (isSettingsPackage) {
            // During initial setup, allow all settings access for permission grants
            if (prefs.setup_in_progress) {
                return BypassAction.ALLOW
            }

            // If we don't know the class, and it's a settings package, 
            // and Kids Mode is active OR uninstall protection is enabled, we block the whole package.
            // This handles cases where accessibility event doesn't provide className but we are in Settings.
            if (className == null) {
                if (kidsModeManager.isEnabled || prefs.uninstall_protection_enabled) {
                    val kmsEnabled = kidsModeManager.isEnabled
                    Log.w(TAG, "BLOCKED: Whole settings package blocked (className null, Kids Mode=$kmsEnabled): $packageName")
                    return BypassAction.BLOCK_SETTINGS
                }
                return BypassAction.ALLOW
            }

            val lowerClass = className.lowercase()
            
            // Broad pattern matching for sensitive keywords
            val isSensitiveClass = lowerClass.contains("admin") || 
                                 lowerClass.contains("accessibility") || 
                                 lowerClass.contains("security") ||
                                 lowerClass.contains("permission") ||
                                 lowerClass.contains("deviceadmin") ||
                                 lowerClass.contains("deactivate") ||
                                 lowerClass.contains("unblock") ||
                                 lowerClass.contains("setupwizard") ||
                                 lowerClass.contains("managedevice") ||
                                 lowerClass.contains("active") ||
                                 className?.contains("DeviceAdminAdd", ignoreCase = true) == true
            
            val isBlockedActivity = BLOCKED_SETTINGS_ACTIVITIES.any { className.contains(it, ignoreCase = true) }
            
            val isUninstallProtectionEnabled = prefs.uninstall_protection_enabled

            // ACTIVE BLOCKING: If Kids Mode is on, we are much more aggressive
            // EXCEPTION: Allow Launcher Settings and Setup Wizard access
            val isLauncherSettings = lowerClass.contains("launcher") && lowerClass.contains("settings")
            
            // IF KIDS MODE IS OFF: Allow ALL settings access (unless setup in progress, which shouldn't happen here)
            if (!kidsModeManager.isEnabled) {
                return BypassAction.ALLOW
            }

            if ((isSensitiveClass || packageName.contains("settings")) && !isLauncherSettings && !prefs.setup_in_progress) {
                Log.e(TAG, "PANIC MODE: Blocking ALL settings access in Kids Mode: $packageName")
                return BypassAction.BLOCK_SETTINGS
            }

            if ((isBlockedActivity || isSensitiveClass) && isUninstallProtectionEnabled) {
                // Allow browsing general settings lists
                if (packageName == SETTINGS_PACKAGE && !lowerClass.contains("accessibility") && !lowerClass.contains("admin")) {
                    return BypassAction.ALLOW
                }
                
                // EXCEPTION: Allow KiddoLock's own settings if we ever add them there
                if (lowerClass.contains("com.kiddolock.app")) return BypassAction.ALLOW
                
                Log.w(TAG, "BLOCKED: Attempt to access sensitive settings: $className")
                return BypassAction.BLOCK_SETTINGS
            }
        }

        // 2. App-level blocking is now handled EXCLUSIVELY by AppBlockManager
        // to ensure consistent behavior and respect for temporary unlocks.

        // 3. Check if user is trying to uninstall via package installer
        if (isUninstallAttempt(packageName, className)) {
            Log.w(TAG, "BLOCKED: Uninstall attempt detected")
            return BypassAction.BLOCK_UNINSTALL
        }

        // 4. Kids Mode Specific: Home Screen Layout Lock (REAL LOCK relied upon)
        // We no longer use "Virtual Lock" overlays for the launcher as they were too aggressive.
        // The real lock is now managed via KidsModeManager.setHomeLayoutLocked()

        return BypassAction.ALLOW
    }

    /**
     * Check time restrictions — if in quiet hours or daily limit exceeded.
     * Note: Usually handled by AppBlockManager, but keeping for direct calls if needed.
     */
    fun checkTimeRestrictions(packageName: String): BypassAction {
        val scheduler = TimeScheduler(context)
        if (!scheduler.isCurrentlyRestricted()) {
            return BypassAction.ALLOW
        }

        // During restricted hours, use the centralized essential apps whitelist
        val appManager = AppBlockManager.getAppManager(context)
        val essentialApps = appManager.ESSENTIAL_APPS_WHITELIST

        val isLauncher = packageName.contains("launcher") || packageName.contains("home")
        
        if (essentialApps.contains(packageName) || isLauncher) {
            return BypassAction.ALLOW
        }

        Log.d(TAG, "Time restriction: blocking $packageName — ${scheduler.getRestrictionReason()}")
        return BypassAction.BLOCK_TIME_LIMIT
    }

    private fun isUninstallAttempt(packageName: String, className: String?): Boolean {
        // Detect package uninstaller launching
        val uninstallerPackages = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.settings",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller"
        )
        
        val isUninstaller = uninstallerPackages.any { packageName.contains(it) }
        
        val lowerClass = className?.lowercase() ?: ""
        val uninstallerKeywordMatch = lowerClass.contains("uninstaller") || 
                                    lowerClass.contains("uninstall") || 
                                    lowerClass.contains("packageinstaller")
        
        if (isUninstaller || uninstallerKeywordMatch) {
            // CRITICAL: If Kids Mode is OFF, allow uninstallation
            if (!com.kiddolock.app.management.KidsModeManager(context).isEnabled) {
                return false
            }

            val prefs = Prefs(context)
            val uninstallProtection = prefs.uninstall_protection_enabled
            
            if (!uninstallProtection) return false

            // IMPORTANT: If we are in the middle of a "Certified Uninstall" (via AdminActivity), allow it
            if (prefs.certified_uninstall_in_progress) {
                Log.i(TAG, "Allowing certified uninstall")
                return false
            }

            // Also check if the current screen is specifically about unintsalling *this* app
            // Some uninstaller apps show a list, we only block if it's our package details
            if (lowerClass.contains("uninstall") && lowerClass.contains(context.packageName.lowercase())) {
                return true
            }

            return true
        }
        return false
    }

    enum class BypassAction {
        ALLOW,
        BLOCK_SETTINGS,
        BLOCK_APP,
        BLOCK_UNINSTALL,
        BLOCK_TIME_LIMIT
    }
}
