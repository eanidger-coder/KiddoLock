package com.kiddolock.app.utils
import com.kiddolock.app.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kiddolock.app.MainActivity
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationUtils {
    const val CHANNEL_ID = "kiddolock_service_channel"
    private const val CHANNEL_NAME = "הגנת KiddoLock"
    private const val SETUP_CHANNEL_ID = "kiddolock_setup_channel"
    private const val SETUP_CHANNEL_NAME = "הגדרת KiddoLock"
    const val KIDDO_NOTIFICATION_ID = 2026
    private const val SETUP_NOTIFICATION_ID = 2027
    const val ACTION_BLOCK_LAST = "com.kiddolock.app.BLOCK_LAST_SITE"
    const val ACTION_EMERGENCY_UNINSTALL = "com.kiddolock.app.EMERGENCY_UNINSTALL"
    const val ACTION_EMERGENCY_UNLOCK = "com.kiddolock.app.EMERGENCY_UNLOCK"
    const val ACTION_PAUSE_PROTECTION_1H = "com.kiddolock.app.PAUSE_PROTECTION_1H"
    const val ACTION_ENABLE_KIDS_MODE = "com.kiddolock.app.ENABLE_KIDS_MODE"

    private var isProtectionActive = false

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows that KiddoLock protection is active"
            }
            manager.createNotificationChannel(channel)

            val setupChannel = NotificationChannel(
                SETUP_CHANNEL_ID,
                SETUP_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Setup and configuration alerts"
            }
            manager.createNotificationChannel(setupChannel)
        }
    }

    fun buildNotification(context: Context, active: Boolean): Notification {
        createNotificationChannel(context)
        isProtectionActive = active

        // Real source of truth - check Kids Mode + suppression state in case caller passed stale value
        val kidsModeOn = try { com.kiddolock.app.management.KidsModeManager(context).isEnabled } catch (_: Throwable) { active }
        val suppressed = try { com.kiddolock.app.management.AppBlockManager.isGlobalSuppressed } catch (_: Throwable) { false }
        val effectiveActive = kidsModeOn && !suppressed

        // 3 states: ACTIVE / PAUSED (Kids Mode on but suppressed) / DORMANT (Kids Mode off)
        val isPaused = kidsModeOn && suppressed
        val title = when {
            isPaused -> "⏸ KiddoLock בהפסקה זמנית"
            effectiveActive -> "KiddoLock: הגנה פעילה"
            else -> "KiddoLock במצב חופשי"
        }
        val content = when {
            isPaused -> "ההגנה מושעית — לחץ 'הפעל הגנה עכשיו' לחזרה מיידית"
            effectiveActive -> "המכשיר מוגן ומנוטר בזמן אמת"
            else -> "לחץ להפעלת ההגנה"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // CRITICAL FIX (black-screen bug): when Kids Mode is OFF, the dormant notification
        // used to open MainActivity directly. MainActivity.onResume chains setup/PIN/auto-revoke
        // checks that can leave the user on a blank window. Instead, route the tap through a
        // BroadcastReceiver that turns on Kids Mode without opening any Activity at all.
        val enableKidsIntent = Intent(context, com.kiddolock.app.receivers.EmergencyReceiver::class.java).apply {
            action = ACTION_ENABLE_KIDS_MODE
            // Explicit package - required for receivers on Android 8+
            setPackage(context.packageName)
        }
        val enableKidsPendingIntent = PendingIntent.getBroadcast(
            context, 5, enableKidsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val unlockIntent = Intent(context, com.kiddolock.app.receivers.EmergencyReceiver::class.java).apply {
            action = ACTION_EMERGENCY_UNLOCK
        }
        val unlockPendingIntent = PendingIntent.getBroadcast(
            context, 1, unlockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val uninstallIntent = Intent(context, com.kiddolock.app.receivers.EmergencyReceiver::class.java).apply {
            action = ACTION_EMERGENCY_UNINSTALL
        }
        val uninstallPendingIntent = PendingIntent.getBroadcast(
            context, 2, uninstallIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ⏰ Dynamic content: priority order - bonus > bedtime active > limit reached > limit remaining > snooze
        var dynamicTitle = title
        var dynamicContent = content
        try {
            val scheduler = com.kiddolock.app.services.TimeScheduler(context)
            val config = scheduler.getConfig()

            // PRIORITY 1: Bonus time active (overrides everything)
            if (active && scheduler.isBonusTimeActive()) {
                val bonusSec = scheduler.getBonusTimeRemainingSec()
                val bonusMin = (bonusSec / 60).toInt()
                dynamicTitle = "🎁 בונוס פעיל - הכל מותר"
                dynamicContent = "נשארו $bonusMin דקות בונוס"
            }
            // PRIORITY 2: Bedtime active right now (and not snoozed)
            else if (active && config.quietHoursEnabled && scheduler.isBedtimeActive()) {
                val eh = config.quietHoursEnd
                val em = config.quietHoursEndMin
                dynamicTitle = "🌙 שעת שינה פעילה"
                dynamicContent = "האפליקציות חסומות עד %02d:%02d".format(eh, em)
            }
            // PRIORITY 3: Bedtime snoozed by parent
            else if (active && config.quietHoursEnabled && scheduler.isBedtimeSnoozed()) {
                dynamicTitle = "🌙 שעת שינה דחויה"
                dynamicContent = "שעת השינה הושעתה - תחזור מחר אוטומטית"
            }
            // PRIORITY 4: Daily limit reached
            else if (active && config.dailyTimeLimitEnabled && scheduler.isDailyLimitReached()) {
                dynamicTitle = "⏰ הזמן היומי נגמר"
                dynamicContent = "הענק בונוס מהאפליקציה כדי להמשיך"
            }
            // PRIORITY 5: Normal - show remaining time
            else if (active && config.dailyTimeLimitEnabled) {
                val usageMin = scheduler.getTodayUsageMinutes()
                val remainMin = maxOf(0, config.dailyTimeLimitMinutes - usageMin)
                val timeStr = when {
                    remainMin < 60 -> "$remainMin דק׳"
                    else -> "${remainMin / 60}שע׳ ${remainMin % 60}דק׳"
                }
                dynamicContent = "⏰ זמן מסך שנותר: $timeStr"
                if (config.quietHoursEnabled) {
                    val sh = config.quietHoursStart
                    val sm = config.quietHoursStartMin
                    dynamicContent += " • 🌙 שינה ב-%02d:%02d".format(sh, sm)
                }
            }
        } catch (_: Exception) {}

        // Build notification - 3-state styling
        // CRITICAL FIX: dormant + paused notification taps now trigger the receiver (no Activity, no black screen)
        val tapIntent = if (effectiveActive) pendingIntent else enableKidsPendingIntent
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(dynamicTitle)
            .setContentText(dynamicContent)
            .setSmallIcon(R.drawable.ic_shield)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setAutoCancel(false)

        when {
            isPaused -> {
                // PAUSED: Orange/amber, parent gets a one-tap "restore protection now" button.
                builder.setColor(0xFFFFA502.toInt())  // Amber
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                builder.addAction(R.drawable.ic_shield, "🛡️ הפעל הגנה עכשיו", enableKidsPendingIntent)
                builder.addAction(R.drawable.ic_status_pending, "🗑️ הסר KiddoLock", uninstallPendingIntent)
            }
            effectiveActive -> {
                // ACTIVE: Green color, emergency actions available
                builder.setColor(0xFF4CAF50.toInt())  // Green
                builder.setPriority(NotificationCompat.PRIORITY_LOW)
                builder.addAction(R.drawable.ic_lock_open, "⏸ הפסקה 10 דק׳", unlockPendingIntent)
                builder.addAction(R.drawable.ic_status_pending, "🗑️ הסר KiddoLock", uninstallPendingIntent)
            }
            else -> {
                // DORMANT: Gray, minimal priority, action button to enable protection in one tap.
                // No Activity opens, no chance of a black screen.
                builder.setColor(0xFF6B6780.toInt())  // Gray
                builder.setPriority(NotificationCompat.PRIORITY_MIN)
                builder.addAction(R.drawable.ic_shield, "🛡️ הפעל הגנה", enableKidsPendingIntent)
            }
        }
        return builder.build()
    }

    /**
     * Show a non-blocking notification when setup is incomplete.
     * Tapping it opens the app for the user to fix the issue.
     */
    fun showSetupIncompleteNotification(context: Context, missingDetail: String) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 10, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, SETUP_CHANNEL_ID)
            .setContentTitle("KiddoLock לא עובד במלואו")
            .setContentText("חסר: $missingDetail — לחץ לתיקון")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SETUP_NOTIFICATION_ID, notification)
    }

    /** Dismiss the setup notification (e.g., when user completes setup) */
    fun dismissSetupNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(SETUP_NOTIFICATION_ID)
    }

    // Throttle: avoid rapid rebuilds that cause status-bar flicker
    @Volatile private var lastNotifPostMs: Long = 0L
    @Volatile private var lastNotifSignature: String = ""

    fun updateNotification(context: Context, active: Boolean = isProtectionActive) {
        val now = System.currentTimeMillis()
        // Build a signature of all relevant state - if any of these change, the notification text changes
        val signature = try {
            val scheduler = com.kiddolock.app.services.TimeScheduler(context)
            "${active}|${scheduler.isBedtimeActive()}|${scheduler.isBonusTimeActive()}|${scheduler.isBedtimeSnoozed()}|${scheduler.isDailyLimitReached()}|${scheduler.getTodayUsageMinutes()}"
        } catch (_: Throwable) { active.toString() }

        // Repost if state signature CHANGED, or if more than 60s passed (catches time-based changes)
        if (lastNotifSignature == signature && (now - lastNotifPostMs) < 60_000L) {
            return
        }
        lastNotifSignature = signature
        lastNotifPostMs = now
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(KIDDO_NOTIFICATION_ID, buildNotification(context, active))
    }

    /**
     * עדכון מהיר של ההתראה הראשית עם טקסט מותאם.
     * משמש למשוב חזותי כשמשתמש לוחץ על כפתור חירום בהתראה.
     */
    fun updateNotificationCustom(context: Context, title: String, content: String) {
        createNotificationChannel(context)
        val intent = android.content.Intent(context, com.kiddolock.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(com.kiddolock.app.R.drawable.ic_shield)
            .setColor(0xFFFFA502.toInt())
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(KIDDO_NOTIFICATION_ID, n)
    }

    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
