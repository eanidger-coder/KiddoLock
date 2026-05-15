package com.kiddolock.app.management
import com.kiddolock.app.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.kiddolock.app.MainActivity
import com.kiddolock.app.services.TimeScheduler
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WeeklyReportWorker — generates a local weekly summary and delivers it as a notification.
 *
 * The report includes:
 *   - Total content blocks (per category)
 *   - Most-used apps this week
 *   - Time limit compliance
 *   - Days with zero violations (streak)
 *
 * Runs once a week (Friday evening — before Shabbat, relevant for religious users).
 */
class WeeklyReportWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WeeklyReport"
        private const val CHANNEL_ID = "kiddolock_report"
        private const val NOTIF_ID = 2001

        fun schedule(context: Context) {
            // Calculate delay until next Friday 14:00
            val delay = getDelayUntilFriday14()

            val request = OneTimeWorkRequestBuilder<WeeklyReportWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "KiddoLock_WeeklyReport",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(TAG, "Weekly report scheduled in ${delay / 3600000}h")
        }

        private fun getDelayUntilFriday14(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
                set(Calendar.HOUR_OF_DAY, 14)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            // If we already passed Friday 14:00 this week, schedule for next week
            if (target.before(now)) {
                target.add(Calendar.WEEK_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Generating weekly report")
        createNotificationChannel()

        // Per-app time usage
        val perAppTracker = PerAppTimeTracker(applicationContext)
        val usageToday = perAppTracker.getAllUsageToday()
        val totalTimeMin = usageToday.values.sum()
        
        val topApp = usageToday.maxByOrNull { it.value }
        val topAppName = if (topApp != null) {
            try {
                val pm = applicationContext.packageManager
                val label = pm.getApplicationLabel(pm.getApplicationInfo(topApp.key, 0)).toString()
                label
            } catch (e: Exception) { topApp.key }
        } else null

        val summary = buildSummary(topAppName, totalTimeMin)

        showReportNotification(summary)

        // Re-schedule for next week
        schedule(applicationContext)

        return Result.success()
    }

    private fun buildSummary(
        topApp: String?,
        totalMin: Int
    ): String {
        val lines = mutableListOf<String>()
        lines += applicationContext.getString(R.string.weekly_report_summary_header)
        lines += ""
        
        if (totalMin > 0) {
            val hrs = totalMin / 60
            val min = totalMin % 60
            lines += applicationContext.getString(R.string.weekly_report_screen_time, hrs, min)
            if (topApp != null) {
                lines += applicationContext.getString(R.string.weekly_report_top_app, topApp)
            }
        } else {
            lines += applicationContext.getString(R.string.weekly_report_no_usage)
        }
        
        lines += ""
        lines += applicationContext.getString(R.string.weekly_report_footer)
        
        return lines.joinToString("\n")
    }

    private fun showReportNotification(text: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = applicationContext.getString(R.string.weekly_report_notif_title)

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_time)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
        Log.i(TAG, "Weekly report notification shown")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.weekly_report_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = applicationContext.getString(R.string.weekly_report_channel_desc)
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
