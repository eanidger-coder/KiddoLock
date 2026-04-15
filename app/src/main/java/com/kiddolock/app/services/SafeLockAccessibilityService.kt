package com.kiddolock.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kiddolock.app.SafeLockApp
import com.kiddolock.app.content.ContentPreferences
import com.kiddolock.app.content.core.ChannelAnalyzer
import com.kiddolock.app.content.core.ContentClassifier
import com.kiddolock.app.content.core.EscalationTracker
import com.kiddolock.app.content.entities.BlockedEvent
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.ui.BlockedActivity
import com.kiddolock.app.ui.OverlayService
import com.kiddolock.app.utils.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SafeLockAccessibilityService — the unified AccessibilityService for SafeLock.
 *
 * Combines two previously separate engines:
 *   1. App-blocking (inherited from KiddoLock) — blocks foreground apps that are
 *      on the blacklist, enforces time limits, protects system Settings from
 *      uninstall attempts.
 *   2. YouTube content filter (inherited from SafeKids) — scans text visible in
 *      YouTube Kids / YouTube for violence/horror keywords, detects escalation
 *      patterns, and blocks channels on the parent's blacklist.
 *
 * Both engines run inside the same service because Android only allows one
 * AccessibilityService instance per app, and both engines need the same
 * WINDOW_STATE/CONTENT_CHANGED events.
 */
class SafeLockAccessibilityService : AccessibilityService() {

    // ---- App-blocking state (from KiddoLock) ----
    private lateinit var bypassGuard: BypassGuard
    private var currentForegroundPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNavigationTime = 0L
    private var lastBlockedPkg: String? = null
    private var lastBlockTime: Long = 0
    private var isWarningVisible = false
    private var lastUsageRecordTime = 0L

    // ---- Content-filter state (from SafeKids) ----
    private lateinit var classifier: ContentClassifier
    private lateinit var escalationTracker: EscalationTracker
    private lateinit var channelAnalyzer: ChannelAnalyzer
    private lateinit var contentPrefs: ContentPreferences
    private val contentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastContentScanTime = 0L
    private var lastBlockedTitle = ""

    companion object {
        private const val TAG = "SafeLockService"
        private const val NAVIGATION_GUARD_MS = 1500L
        private const val USAGE_RECORD_INTERVAL_MS = 60_000L
        private const val CONTENT_DEBOUNCE_MS = 1200L

        private val YOUTUBE_PACKAGES = setOf(
            "com.google.android.apps.youtube.kids",
            "com.google.android.youtube"
        )
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        bypassGuard = BypassGuard(this)
        bypassGuard.initialize()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected. Configuring...")

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        } ?: serviceInfo

        // Content filter init (tolerate failures — app blocking must still work).
        initContentFilter()

        // Periodic app-blocking check
        handler.postDelayed(periodicCheckRunnable, 5000)

        // Foreground notification (app-blocking keeps the service alive)
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

    private fun initContentFilter() {
        try {
            contentPrefs = ContentPreferences(applicationContext)
            val db = SafeLockApp.get().database

            classifier = ContentClassifier().apply {
                setSensitivity(contentPrefs.sensitivityLevel)
            }
            channelAnalyzer = ChannelAnalyzer(db.blacklistDao())
            escalationTracker = EscalationTracker(db.sessionDao())

            contentScope.launch {
                db.blacklistDao().getAllChannels().collectLatest {
                    channelAnalyzer.refreshBlacklist()
                }
            }
            contentScope.launch {
                db.blacklistDao().getAllKeywords().collectLatest {
                    val custom = channelAnalyzer.refreshCustomKeywords()
                    classifier.updateCustomBlacklist(custom)
                }
            }
            Log.i(TAG, "Content filter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Content filter init failed — YouTube scanning disabled", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicCheckRunnable)
        contentScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------------
    // Event dispatch
    // ---------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 1) YouTube content filter path (runs in parallel with app-blocking).
        if (YOUTUBE_PACKAGES.contains(packageName) && ::contentPrefs.isInitialized
            && contentPrefs.contentFilterEnabled
        ) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    maybeScanYouTubeContent()
                }
            }
        }

        // 2) App-blocking path (exactly the legacy KiddoLock logic).
        handleAppBlockingEvent(event, packageName)
    }

    // ---------------------------------------------------------------------
    // Content filter path
    // ---------------------------------------------------------------------

    private fun maybeScanYouTubeContent() {
        val now = System.currentTimeMillis()
        if (now - lastContentScanTime < CONTENT_DEBOUNCE_MS) return
        lastContentScanTime = now

        val rootNode = rootInActiveWindow ?: return

        try {
            val allText = StringBuilder()
            val titleCandidates = mutableListOf<String>()
            val channelCandidates = mutableListOf<String>()
            extractContent(rootNode, allText, titleCandidates, channelCandidates, depth = 0)

            val screenText = allText.toString()
            if (screenText.isBlank()) return

            val videoTitle = pickBestTitle(titleCandidates)
            val channelName = pickBestChannel(channelCandidates)

            val score = classifier.classify(screenText)
            val channelBlocked = channelName.isNotEmpty() && channelAnalyzer.isBlacklisted(channelName)

            contentScope.launch {
                val escalation = escalationTracker.recordAndAnalyze(videoTitle, channelName, score)
                val shouldBlock = score.isBlocked || channelBlocked || escalation.isEscalating
                if (!shouldBlock) return@launch

                val blockTitle = videoTitle.ifEmpty { screenText.take(50).trim() }
                if (blockTitle == lastBlockedTitle) return@launch
                lastBlockedTitle = blockTitle

                val reason = when {
                    channelBlocked -> "blacklist"
                    escalation.isEscalating -> "escalation"
                    else -> "keyword"
                }
                Log.w(TAG, "CONTENT BLOCKED: '$blockTitle' score=${score.totalScore} reason=$reason")

                if (channelName.isNotEmpty()) channelAnalyzer.recordViolation(channelName)
                contentPrefs.incrementBlockCount()

                SafeLockApp.get().database.blockedEventDao().insert(
                    BlockedEvent(
                        videoTitle = blockTitle,
                        channelName = channelName,
                        reason = reason,
                        matchedTerms = score.categories.flatMap { it.matchedTerms }.joinToString(", "),
                        violenceScore = score.totalScore
                    )
                )

                showContentBlockScreen(blockTitle, reason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning YouTube content", e)
        }
    }

    private fun extractContent(
        node: AccessibilityNodeInfo,
        allText: StringBuilder,
        titleCandidates: MutableList<String>,
        channelCandidates: MutableList<String>,
        depth: Int
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

        if (text.isNotEmpty()) {
            allText.append(text).append(' ')
            categorize(text, titleCandidates, channelCandidates)
        }
        if (contentDesc.isNotEmpty() && contentDesc != text) {
            allText.append(contentDesc).append(' ')
            categorize(contentDesc, titleCandidates, channelCandidates)
        }

        if (depth < 15) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    extractContent(child, allText, titleCandidates, channelCandidates, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
    }

    private val uiLabels = setOf(
        "הבא", "חזרה", "חפש", "בית", "ספרייה", "Search", "Home", "Library",
        "More", "Settings", "עוד", "הגדרות", "שתף", "Share"
    )

    private fun categorize(
        text: String,
        titles: MutableList<String>,
        channels: MutableList<String>
    ) {
        val len = text.length
        if (len < 3 || len > 200) return
        if (text in uiLabels) return
        if (len in 10..120) titles.add(text)
        if (len in 3..50) channels.add(text)
    }

    private fun pickBestTitle(candidates: List<String>): String =
        candidates.filter { it.length >= 10 }.maxByOrNull { it.length }
            ?: candidates.firstOrNull().orEmpty()

    private fun pickBestChannel(candidates: List<String>): String =
        candidates
            .filter { it.length in 3..40 && !it.contains('\n') }
            .minByOrNull { it.length }
            .orEmpty()

    private fun showContentBlockScreen(title: String, reason: String) {
        val intent = Intent(applicationContext, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("blocked_title", title)
            putExtra("blocked_reason", reason)
        }
        startActivity(intent)
    }

    // ---------------------------------------------------------------------
    // App-blocking path (ported from KiddoLockAccessibilityService)
    // ---------------------------------------------------------------------

    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) {
                    handler.postDelayed(this, 10_000)
                    return
                }

                val activePkg = rootInActiveWindow?.packageName?.toString()
                if (activePkg != null && !activePkg.contains("com.android.systemui") && activePkg != packageName) {
                    currentForegroundPackage = activePkg
                }

                currentForegroundPackage?.let { pkg ->
                    if (pkg == packageName || pkg.contains("com.android.systemui")) return@let

                    val appManager = AppBlockManager.getAppManager(this@SafeLockAccessibilityService)
                    val realPkg = rootInActiveWindow?.packageName?.toString()
                    if (realPkg != null) {
                        if (appManager.isLauncher(realPkg) || appManager.isSystemProtected(realPkg)) {
                            val timeSinceBlock = System.currentTimeMillis() - lastBlockTime
                            if (timeSinceBlock > 5000) {
                                val intent = Intent(
                                    this@SafeLockAccessibilityService,
                                    OverlayService::class.java
                                ).apply { action = "HIDE_OVERLAY" }
                                startService(intent)
                            }
                            currentForegroundPackage = realPkg
                            return@let
                        }
                    } else {
                        return@let
                    }

                    checkAndRecordUsage(pkg)

                    if (AppBlockManager.isAppBlocked(this@SafeLockAccessibilityService, pkg)) {
                        showBlockOverlay(pkg)
                    } else {
                        checkWarningZone()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in periodic check loop", e)
            } finally {
                handler.postDelayed(this, 3000)
            }
        }
    }

    private fun checkWarningZone() {
        val scheduler = TimeScheduler(this)
        val inZone = scheduler.isInWarningZone()
        if (inZone && !isWarningVisible) {
            val intent = Intent(this, OverlayService::class.java).apply { action = "SHOW_WARNING" }
            startService(intent)
            isWarningVisible = true
        } else if (!inZone && isWarningVisible) {
            val intent = Intent(this, OverlayService::class.java).apply { action = "HIDE_WARNING" }
            startService(intent)
            isWarningVisible = false
        }
    }

    private fun checkAndRecordUsage(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastUsageRecordTime >= USAGE_RECORD_INTERVAL_MS) {
            val appManager = AppBlockManager.getAppManager(this)
            if (!appManager.isSystemProtected(pkg)) {
                TimeScheduler(this).recordUsageMinute()
                lastUsageRecordTime = now
            }
        }
    }

    private fun handleAppBlockingEvent(event: AccessibilityEvent, packageName: String) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (!packageName.contains("com.android.systemui") && packageName != this.packageName) {
                currentForegroundPackage = packageName
                val appManager = AppBlockManager.getAppManager(this)
                val timeSinceBlock = System.currentTimeMillis() - lastBlockTime
                if (appManager.isLauncher(packageName) && timeSinceBlock > 5000) {
                    val intent = Intent(this, OverlayService::class.java).apply { action = "HIDE_OVERLAY" }
                    startService(intent)
                }
            }
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val className = event.className?.toString()

        if (packageName == this.packageName || packageName.contains("com.kiddolock.app")) return
        if (packageName == "com.android.systemui") return

        try {
            val now = System.currentTimeMillis()
            if (now - lastNavigationTime < NAVIGATION_GUARD_MS) return

            val appManager = AppBlockManager.getAppManager(this)
            val kidsMode = KidsModeManager(this)

            if (appManager.isSystemProtected(packageName) || appManager.isLauncher(packageName)) {
                val intent = Intent(this, OverlayService::class.java).apply { action = "HIDE_OVERLAY" }
                startService(intent)
                return
            }

            val activePkg = rootInActiveWindow?.packageName?.toString()
            if (activePkg != null && activePkg != packageName) {
                if (appManager.isLauncher(activePkg) || appManager.isSystemProtected(activePkg)) return
            }

            val isCritical = AppBlockManager.isCriticalApp(packageName)
            if (kidsMode.isEnabled && isCritical && !packageName.contains("kiddolock") &&
                !AppBlockManager.isGlobalSuppressed &&
                !AppBlockManager.isTemporarilyUnlocked(packageName)
            ) {
                Log.w(TAG, "SAFEGUARD: Blocking critical app $packageName")
                lastNavigationTime = System.currentTimeMillis()
                performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockOverlay(packageName)
                return
            }

            if (AppBlockManager.isAppBlocked(this, packageName)) {
                showBlockOverlay(packageName)
                return
            }

            if (!event.isFullScreen) return

            val action = bypassGuard.checkNavigation(packageName, className)
            if (action == BypassGuard.BypassAction.BLOCK_SETTINGS ||
                action == BypassGuard.BypassAction.BLOCK_UNINSTALL
            ) {
                Log.i(TAG, "BypassGuard blocking: $packageName ($action)")
                showBlockOverlay(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing app-blocking event for $packageName", e)
        }
    }

    private fun showBlockOverlay(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPkg && (now - lastBlockTime < 1000)) return

        lastBlockedPkg = packageName
        lastBlockTime = now

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show overlay — permission not granted.")
            NotificationUtils.showSetupIncompleteNotification(this, "חסרה הרשאת תצוגה מעל אפליקציות")
            return
        }

        lastNavigationTime = System.currentTimeMillis()
        performGlobalAction(GLOBAL_ACTION_HOME)

        val intent = Intent(this, OverlayService::class.java).apply {
            action = "SHOW_OVERLAY"
            putExtra("package_name", packageName)
        }
        startService(intent)
    }
}
