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
    private const val CHANNEL_NAME = "KiddoLock Protection"
    private const val SETUP_CHANNEL_ID = "kiddolock_setup_channel"
    private const val SETUP_CHANNEL_NAME = "KiddoLock Setup"
    const val KIDDO_NOTIFICATION_ID = 2026
    private const val SETUP_NOTIFICATION_ID = 2027
    const val ACTION_BLOCK_LAST = "com.kiddolock.app.BLOCK_LAST_SITE"
    const val ACTION_EMERGENCY_UNINSTALL = "com.kiddolock.app.EMERGENCY_UNINSTALL"
    const val ACTION_EMERGENCY_UNLOCK = "com.kiddolock.app.EMERGENCY_UNLOCK"

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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Setup and configuration alerts"
            }
            manager.createNotificationChannel(setupChannel)
        }
    }

    fun buildNotification(context: Context, active: Boolean): Notification {
        createNotificationChannel(context)
        isProtectionActive = active
        
        val title = if (active) "KiddoLock: הגנה פעילה 🛡️" else "KiddoLock: בהמתנה"
        val content = if (active) "המכשיר מוגן ומנוטר בזמן אמת" else "לחץ להפעלת ההגנה"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(0xFF4CAF50.toInt()) // Green color for active protection
            .setPriority(NotificationCompat.PRIORITY_MAX) 
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setAutoCancel(false)
            .build()
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

    fun updateNotification(context: Context, active: Boolean = isProtectionActive) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(KIDDO_NOTIFICATION_ID, buildNotification(context, active))
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
