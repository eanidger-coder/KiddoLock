package com.kiddolock.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.ui.OverlayService
import com.kiddolock.app.utils.NotificationUtils

class KiddoLockAccessibilityService : AccessibilityService() {
    private lateinit var bypassGuard: BypassGuard
    private var currentForegroundPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Periodic check every 10 seconds to track usage and enforce limits
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            try {
                // BATTERY OPTIMIZATION: Pause work if screen is off
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) {
                    Log.v(TAG, "Screen is off - skipping periodic check")
                    handler.postDelayed(this, 10000)
                    return
                }

                currentForegroundPackage?.let { pkg ->
                    if (pkg != packageName && !pkg.contains("com.android.systemui")) {
                        // 1. Record usage
                        checkAndRecordUsage(pkg)
                        
                        // 2. Re-verify blocking logic
                        if (AppBlockManager.isAppBlocked(this@KiddoLockAccessibilityService, pkg)) {
                            Log.i(TAG, "Periodic check: $pkg is BLOCKED. Ensuring overlay.")
                            showBlockOverlay(pkg)
                        } else {
                            // 3. Smart Feature: Real-time Limit Prediction Warning
                            checkWarningZone()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in periodic check loop", e)
            } finally {
                // Increased frequency: 3 seconds for active foreground apps
                handler.postDelayed(this, 3000)
            }
        }
    }

    private var isWarningVisible = false

    private fun checkWarningZone() {
        val scheduler = TimeScheduler(this)
        val inZone = scheduler.isInWarningZone()
        
        if (inZone && !isWarningVisible) {
            Log.i(TAG, "Entering Warning Zone. Showing banner.")
            val intent = Intent(this, OverlayService::class.java).apply {
                action = "SHOW_WARNING"
            }
            startService(intent)
            isWarningVisible = true
        } else if (!inZone && isWarningVisible) {
            Log.i(TAG, "Exiting Warning Zone. Hiding banner.")
            val intent = Intent(this, OverlayService::class.java).apply {
                action = "HIDE_WARNING"
            }
            startService(intent)
            isWarningVisible = false
        }
    }

    private var lastUsageRecordTime = 0L

    private fun checkAndRecordUsage(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastUsageRecordTime >= 60000) { // Every 1 minute
            // OPTIMIZATION: Use cached AppManager instead of creating a new one
            val appManager = AppBlockManager.getAppManager(this)
            if (!appManager.isSystemProtected(packageName)) {
                Log.v(TAG, "Recording 1 minute usage for $packageName")
                TimeScheduler(this).recordUsageMinute()
                lastUsageRecordTime = now
            }
        }
    }


    companion object {
        private const val TAG = "AccessibilityService"
    }

    override fun onCreate() {
        super.onCreate()
        bypassGuard = BypassGuard(this)
        bypassGuard.initialize()
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicCheckRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // ONLY process window state changes — this is the actual app-switch event.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString()

        // Track foreground app
        if (!packageName.contains("com.android.systemui") && packageName != this.packageName) {
            currentForegroundPackage = packageName
        }

        // Skip our own app and system UI for blocking
        if (packageName == this.packageName || packageName.contains("com.kiddolock.app")) return
        if (packageName == "com.android.systemui") return

        try {
            // HEURISTIC 1: Check if the event is high-priority for window changes
            // Some non-full-screen events (search results) trigger this but shouldn't be blocked
            if (!event.isFullScreen) {
                Log.v(TAG, "Ignoring non-full-screen window change for $packageName (likely search result or dialog)")
                return
            }

            val activePkg = rootInActiveWindow?.packageName?.toString()
            Log.d(TAG, "Window changed: pkg=$packageName (active=$activePkg) cls=$className isFullScreen=${event.isFullScreen}")

            // HEURISTIC 2: Double-check with root window package
            // If the event package doesn't match the root window package, it's likely a sub-view or search result
            if (activePkg != null && activePkg != packageName) {
                // EXCEPTION: If we are in Launcher or SystemUI, and the event is for a DIFFERENT package,
                // it's almost certainly a search result or a floating notification/preview.
                if (activePkg.contains("launcher") || activePkg.contains("home") || activePkg == "com.android.systemui") {
                    Log.v(TAG, "Ignoring event for $packageName: Root window belongs to $activePkg (Likely search result/preview)")
                    return
                }
            }
            
            // Expert QA Hook: Dump internal state on every window change to catch desyncs
            AppBlockManager.dumpState(this)

            // 1. Check Global Block (Time, Bedtime, Instant Lock, Blacklist)
            if (AppBlockManager.isAppBlocked(this, packageName)) {
                showBlockOverlay(packageName)
                return
            }

            // 2. Check Bypass Guard (Settings protect, Uninstall protect, locked system apps)
            val action = bypassGuard.checkNavigation(packageName, className)
            if (action != BypassGuard.BypassAction.ALLOW) {
                showBlockOverlay(packageName)
            }
        } catch (e: Exception) {
            // Never crash the accessibility service — it's unrecoverable
            Log.e(TAG, "Error processing event for $packageName", e)
        }
    }

    private var lastBlockedPkg: String? = null
    private var lastBlockTime: Long = 0

    private fun showBlockOverlay(packageName: String) {
        val now = System.currentTimeMillis()
        
        // THROTTLING: If we just requested a block for the SAME package within 1s, skip redundant startService.
        // This prevents "flicker storm" from rapid TYPE_WINDOW_STATE_CHANGED events (common in Chrome/Incognito).
        if (packageName == lastBlockedPkg && (now - lastBlockTime < 1000)) {
            Log.v(TAG, "Throttling redundant block request for $packageName")
            return
        }

        lastBlockedPkg = packageName
        lastBlockTime = now
        
        // Verify overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show overlay — permission not granted.")
            NotificationUtils.showSetupIncompleteNotification(this, "חסרה הרשאת תצוגה מעל אפליקציות")
            return
        }

        Log.i(TAG, "Showing block overlay for $packageName")
        
        val intent = Intent(this, OverlayService::class.java).apply {
            action = "SHOW_OVERLAY"
            putExtra("package_name", packageName)
        }
        startService(intent)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected. Configuring...")

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        } ?: serviceInfo

        // Start periodic check
        handler.postDelayed(periodicCheckRunnable, 5000)

        // Start foreground
        try {
            val notification = NotificationUtils.buildNotification(this, true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationUtils.KIDDO_NOTIFICATION_ID, 
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationUtils.KIDDO_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }
}

