package com.kiddolock.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.utils.NotificationUtils
import com.kiddolock.app.utils.SafetyWatchdog

class KiddoLockApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // CRITICAL: install the watchdog FIRST before anything else can throw.
        // This guarantees the kid can never be locked in if some other component crashes.
        try {
            SafetyWatchdog.install(this)
        } catch (e: Throwable) {
            android.util.Log.e("KiddoLockApp", "Could not install SafetyWatchdog", e)
        }

        // If a previous run hit the safety threshold, alert the parent and reset the flag
        try {
            if (SafetyWatchdog.wasAutoDisabledRecently(this)) {
                val crashMsg = SafetyWatchdog.getLastCrashMessage(this) ?: "תקלה לא ידועה"
                showAutoDisableNotification(this, crashMsg)
                // AUTO-REPORT to developer: the watchdog disabled protection after repeated crashes.
                try {
                    com.kiddolock.app.utils.FeedbackManager.sendAutoReport(
                        this,
                        "🚨 ההגנה בוטלה אוטומטית אחרי קריסות חוזרות",
                        "SafetyWatchdog זיהה קריסות חוזרות וכיבה את ההגנה. הסיבה האחרונה: $crashMsg"
                    )
                } catch (_: Throwable) {}
                SafetyWatchdog.clearAutoDisabledFlag(this)
            }
            SafetyWatchdog.considerResetingOnHealthyLaunch(this)
        } catch (e: Throwable) {
            android.util.Log.e("KiddoLockApp", "Watchdog post-check failed", e)
        }

        // Initial sync from cloud to restore settings if missing/reinstalled
        try {
            com.kiddolock.app.management.SettingsSyncManager(this).syncSettingsOnStart()
        } catch (e: Exception) {
            android.util.Log.e("KiddoLockApp", "Cloud sync failed at startup", e)
        }

        // Heartbeat: ensures the remote kill switch always has a fresh check-in within 15 min.
        try {
            com.kiddolock.app.management.HeartbeatWorker.schedule(this)
        } catch (e: Exception) {
            android.util.Log.e("KiddoLockApp", "Failed to schedule heartbeat", e)
        }

        // Register observer to lock the app when it goes into the background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App backgrounded - clear the PIN session
                AdminPinManager.clearSession()
            }
        })
    }

    private fun showAutoDisableNotification(context: Context, lastCrash: String) {
        try {
            NotificationUtils.createNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, NotificationUtils.CHANNEL_ID)
                .setContentTitle("KiddoLock - ההגנה בוטלה אוטומטית")
                .setContentText("זוהו 3 קריסות ב-10 דקות. ההגנה כובתה כדי לא לתפוס את הילד.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "מערכת הבטיחות זיהתה 3 קריסות תוך 10 דקות וכיבתה את ההגנה אוטומטית. " +
                    "הסיבה: " + lastCrash + ". הילד יכול להשתמש במכשיר רגיל. " +
                    "בדוק את האפליקציה לפני הפעלה מחדש של מצב ילדים."
                ))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(0xFFFFA502.toInt())
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(2030, notification)
        } catch (e: Throwable) {
            android.util.Log.e("KiddoLockApp", "Could not show auto-disable notification", e)
        }
    }
}
