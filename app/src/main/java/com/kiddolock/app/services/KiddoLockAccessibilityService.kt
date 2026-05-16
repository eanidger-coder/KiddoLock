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
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.ui.OverlayService
import com.kiddolock.app.utils.NotificationUtils

class KiddoLockAccessibilityService : AccessibilityService() {
    private lateinit var bypassGuard: BypassGuard
    private var currentForegroundPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventProcessedMs: Long = 0
    private var lastNavigationTime = 0L // Guard against loops during Home navigation
    private var isHomeTransitioning = false // Flag for 1.5s Home grace period
    
    companion object {
        private const val NAVIGATION_GUARD_MS = 1500L // Increased for better stability
        private const val TAG = "AccessibilityService"
        private const val USAGE_RECORD_INTERVAL_MS = 60000L 
    }
    // ⏰ Refresh persistent notification every 30s so the live timer in the pull-down updates
    private val notificationRefreshRunnable = object : Runnable {
        override fun run() {
            try {
                com.kiddolock.app.utils.NotificationUtils.updateNotification(this@KiddoLockAccessibilityService)
            } catch (_: Exception) {}
            handler.postDelayed(this, 60000)
        }
    }

    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            try {
                // BATTERY OPTIMIZATION: Pause work if screen is off
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) {
                    Log.v(TAG, "Screen is off - skipping periodic check")
                    handler.postDelayed(this, 5000)
                    return
                }

                // BATTERY: If Kids Mode is OFF and no suppression timer, skip entire periodic loop.
                // This makes the app effectively dormant (0% CPU) when the parent uses the phone.
                // Re-check every 60 sec instead of every 10 sec.
                try {
                    val kidsOn = KidsModeManager(this@KiddoLockAccessibilityService).isEnabled
                    if (!kidsOn) {
                        Log.v(TAG, "Kids Mode OFF - dormant mode, next check in 60s")
                        handler.postDelayed(this, 60000)
                        return
                    }
                } catch (_: Throwable) {}

                // REFRESH PACKAGE TRACKING: Ensure we aren't using a stale value from a missed event
                val activePkg = rootInActiveWindow?.packageName?.toString()
                if (activePkg != null && !activePkg.contains("com.android.systemui") && activePkg != packageName) {
                    currentForegroundPackage = activePkg
                }

                currentForegroundPackage?.let { pkg ->
                    // Do not block our own app or system UI
                    if (pkg != packageName && !pkg.contains("com.android.systemui")) {
                        val appManager = AppBlockManager.getAppManager(this@KiddoLockAccessibilityService)
                        
                        // DOUBLE CHECK: Is the app actually in foreground AND not the launcher?
                        // This prevents "jumping" when the record is stale but user is actually Home.
                        val realPkg = rootInActiveWindow?.packageName?.toString()
                        if (realPkg != null) {
                            if (appManager.isLauncher(realPkg) || appManager.isSystemProtected(realPkg)) {
                                Log.v(TAG, "Periodic check: Actual window is $realPkg (Safe). Skipping stale block for $pkg")
                                // 2. CHECK: If we are on a safe screen (Home/System/Allowed), hide any stale overlay
                                val timeSinceBlock = System.currentTimeMillis() - lastBlockTime
                                
                                if ((appManager.isLauncher(realPkg) || appManager.isSystemProtected(realPkg)) && timeSinceBlock > 5000) {
                                    Log.v(TAG, "Periodic check: Actual window is $realPkg (Safe). Hiding stale overlay (Time since block: $timeSinceBlock ms)")
                                    val intent = Intent(this@KiddoLockAccessibilityService, OverlayService::class.java).apply {
                                        action = "HIDE_OVERLAY"
                                    }
                                    startService(intent)
                                } else {
                                    Log.v(TAG, "Periodic check: $realPkg is active. Persistence guard active or not a safe screen. Keeping overlay.")
                                }
                                currentForegroundPackage = realPkg
                                return@let
                            }
                        } else {
                            // HEURISTIC: If root window is null during transition, assume it's safe to wait
                            Log.v(TAG, "Periodic check: Root window is null. Skipping block to avoid race condition.")
                            return@let
                        }

                        // 1. Record usage
                        checkAndRecordUsage(pkg)
                        
                        // 2a. FULL LOCK when daily limit reached - even launcher gets blocked
                        // BUT critical apps (dialer, SMS, WhatsApp, contacts) stay accessible for safety/emergency
                        val scheduler = TimeScheduler(this@KiddoLockAccessibilityService)
                        if (scheduler.isDailyLimitReached() && !AppBlockManager.isGlobalSuppressed
                            && pkg != packageName
                            && !com.kiddolock.app.management.AppManager(this@KiddoLockAccessibilityService).ESSENTIAL_APPS_WHITELIST.contains(pkg)) {
                            // Daily limit hit - lockdown screen
                            Log.i(TAG, "Periodic check: Daily limit reached. FULL LOCK on $pkg")
                            showBlockOverlay(pkg)
                            // schedule continuation
                            handler.postDelayed(this, 5000)
                            return@let
                        }

                        // 2b. Re-verify blocking logic
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
                handler.postDelayed(this, 10000)
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
        if (now - lastUsageRecordTime < USAGE_RECORD_INTERVAL_MS) return
        // 👨‍👩‍👧 Use case: parent lends their phone to kid.
        // Track usage time ONLY when Kids Mode is ACTIVE (kid is using the phone).
        // When parent uses their own phone, don't accumulate against the kid's limit.
        val kidsMode = KidsModeManager(this)
        if (!kidsMode.isEnabled) {
            return
        }
        if (packageName != this.packageName && !packageName.contains("com.kiddolock.app")) {
            Log.i(TAG, "Recording 1 minute KIDS-MODE usage for $packageName")
            TimeScheduler(this).recordUsageMinute()
            lastUsageRecordTime = now
        }
    }



    override fun onCreate() {
        super.onCreate()
        bypassGuard = BypassGuard(this)
        bypassGuard.initialize()
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicCheckRunnable)
        handler.removeCallbacks(notificationRefreshRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 🚀 PERFORMANCE FIX: Drop events from KiddoLock itself or systemui early.
        if (packageName == this.packageName ||
            packageName.contains("com.kiddolock.app") ||
            packageName == "com.android.systemui") {
            return
        }

        // 🔋 BATTERY: If Kids Mode is off, exit IMMEDIATELY before any expensive work.
        // This drops CPU to ~0% when the parent is using their own phone normally.
        try {
            if (!KidsModeManager(this).isEnabled) return
        } catch (_: Throwable) { return }

        // 🔥 CRITICAL: Update foreground package tracking BEFORE debouncing.
        // Otherwise periodic checks (daily limit, bedtime, instant lock) use stale data and don't enforce.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            currentForegroundPackage = packageName
        }

        // ⚡ INSTANT BLOCK: בדיקה מהירה לפני debouncing.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                val isInstantBlock = AppBlockManager.isAppBlocked(this, packageName)
                if (isInstantBlock && !AppBlockManager.isGlobalSuppressed
                    && !AppBlockManager.isTemporarilyUnlocked(packageName)) {
                    Log.i(TAG, "⚡ INSTANT BLOCK: $packageName")
                    lastNavigationTime = System.currentTimeMillis()
                    // ⚡ Show overlay FIRST so screen is covered instantly.
                    // Delay HOME by 250ms so the overlay actually renders before the
                    // launcher takes focus - otherwise the child sees the app briefly
                    // and then home with no explanation (CRIT-3 bug).
                    showBlockOverlay(packageName)
                    handler.postDelayed({
                        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
                    }, 250L)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Instant block check failed for $packageName", e)
            }
        }

        // 🚀 DEBOUNCING (heavy work only): max 1 secondary check per 2500ms.
        val now = System.currentTimeMillis()
        if (now - lastEventProcessedMs < 1000) {
            return
        }
        lastEventProcessedMs = now

        // 🪶 LOW-MEMORY GUARD: skip on devices < 200MB available.
        try {
            val mi = android.app.ActivityManager.MemoryInfo()
            (getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mi)
            if (mi.lowMemory || mi.availMem < 200L * 1024 * 1024) {
                Log.w(TAG, "Low memory mode: skipping heavy event for $packageName")
                return
            }
        } catch (_: Exception) {}

        // Track foreground app more aggressively (Window switch, Focus change, etc.)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            if (!packageName.contains("com.android.systemui") && packageName != this.packageName) {
                currentForegroundPackage = packageName
                
                // FORCE HIDE: If we transition to a launcher, immediately kill any lingering overlay
                val appManager = AppBlockManager.getAppManager(this)
                // REGRESSION FIX: If we just blocked an app, we want the overlay to STAY visible
                // over the HOME screen. Only hide if it's been a while.
                val timeSinceBlock = System.currentTimeMillis() - lastBlockTime
                if (appManager.isLauncher(packageName) && timeSinceBlock > 5000) {
                    Log.v(TAG, "Aggressive Detection: Launcher $packageName detected. Hiding overlay.")
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "HIDE_OVERLAY"
                    }
                    startService(intent)
                }
            }
        }

        // ONLY perform blocking logic on window state changes (major transitions)
        // TYPE_WINDOW_CONTENT_CHANGED is only for package tracking
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val className = event.className?.toString()


        // Skip our own app and system UI for blocking
        if (packageName == this.packageName || packageName.contains("com.kiddolock.app")) return
        if (packageName == "com.android.systemui") return

        try {
            // 0.1 Absolute Whitelist Check (Launcher & Core System)
            // We check this FIRST to prevent loops on the home screen.
            val now = System.currentTimeMillis()
            if (now - lastNavigationTime < NAVIGATION_GUARD_MS) {
                Log.v(TAG, "Navigation Guard: Ignoring event for $packageName (Too soon after navigation)")
                return
            }

            // OPTIMIZATION: Use cached AppManager to avoid re-reading prefs on every event
            val appManager = AppBlockManager.getAppManager(this)
            val kidsMode = KidsModeManager(this)

            if (appManager.isSystemProtected(packageName) || appManager.isLauncher(packageName)) {
                Log.v(TAG, "Event in $packageName: Whitelisted (System/Launcher). Ensuring overlay hidden.")
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = "HIDE_OVERLAY"
                }
                startService(intent)
                return
            }

            // 0.2 Root Window Heuristic
            // Ensure the window being reported is actually the active one.
            // If the user just pressed "Home", we might still get events for the previous app (Gallery).
            val activePkg = rootInActiveWindow?.packageName?.toString()
            if (activePkg != null && activePkg != packageName) {
                if (appManager.isLauncher(activePkg) || appManager.isSystemProtected(activePkg)) {
                    Log.v(TAG, "Heuristic: Ignoring event for $packageName. Active window is $activePkg (Safe)")
                    return
                }
            }

            // 0.3 Zero-Latency Safeguard (Critical Apps)
            val isCritical = AppBlockManager.isCriticalApp(packageName)
            // EMERGENCY RULE: If Global Suppression is active, the parent is in control.
            // PER-APP RULE: If the app is temporarily unlocked by PIN, allow it.
            // Do not block critical apps during this window (allows for fixes or uninstallation).
            if (kidsMode.isEnabled && isCritical && !packageName.contains("kiddolock") && 
                !AppBlockManager.isGlobalSuppressed && !AppBlockManager.isTemporarilyUnlocked(packageName)) {
                Log.w(TAG, "SAFEGUARD: Blocking critical app $packageName")
                lastNavigationTime = System.currentTimeMillis() // Start guard
                performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockOverlay(packageName)
                return
            }

            // 1. Core Blocking Check (Blacklist & Time Limits)
            val isBlocked = AppBlockManager.isAppBlocked(this, packageName)
            Log.v(TAG, "Check: pkg=$packageName isBlocked=$isBlocked kidsOn=${kidsMode.isEnabled}")
            
            if (isBlocked) {
                Log.i(TAG, "Blocking $packageName (isAppBlocked=true)")
                showBlockOverlay(packageName)
                return
            }

            // HEURISTIC 3: Check if the event is high-priority for window changes
            if (!event.isFullScreen) {
                return
            }

            // 2. Check Bypass Guard (Settings protect, Uninstall protect, locked system apps)
            val action = bypassGuard.checkNavigation(packageName, className)
            if (action == BypassGuard.BypassAction.BLOCK_SETTINGS || action == BypassGuard.BypassAction.BLOCK_UNINSTALL) {
                Log.i(TAG, "BypassGuard blocking: $packageName ($action)")
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

        // CRITICAL ORDER (fix CRIT-3): START overlay FIRST, then HOME. If we go HOME first,
        // the kid sees a confusing "app just closed" effect for ~300ms before the overlay
        // shows. Worse, on slow devices the overlay can fail entirely if the service is
        // killed during the HOME transition.
        val intent = Intent(this, OverlayService::class.java).apply {
            action = "SHOW_OVERLAY"
            putExtra("package_name", packageName)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService - falling back to HOME only", e)
        }

        // Small delay so overlay window is laid out before we yank focus to home
        handler.postDelayed({
            lastNavigationTime = System.currentTimeMillis() // Start guard
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 120L)

        // MEMORY CLEANUP: kill the blocked app's background processes so it doesn't accumulate in RAM.
        // This frees memory and battery when the kid taps many blocked apps in sequence.
        // Note: This kills only background processes, not foreground (which we just sent to home anyway).
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null && packageName != this.packageName) {
                handler.postDelayed({
                    try {
                        am.killBackgroundProcesses(packageName)
                        Log.d(TAG, "Killed background processes for blocked $packageName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not kill background for $packageName: ${e.message}")
                    }
                }, 800L)  // delay so the user sees the overlay first, then we cleanup
            }
        } catch (e: Exception) {
            Log.w(TAG, "killBackground setup failed: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected. Configuring...")

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                handler.postDelayed(notificationRefreshRunnable, 5000)
    } ?: serviceInfo

        // Start periodic check
        handler.postDelayed(periodicCheckRunnable, 12000)

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
