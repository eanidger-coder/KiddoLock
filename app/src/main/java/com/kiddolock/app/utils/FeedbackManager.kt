package com.kiddolock.app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.kiddolock.app.config.ApiConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Send parent feedback to the Cloudflare worker so we can prioritize fixes/upgrades.
 * Feedback is anonymous - no PII collected beyond device hash + voluntary text.
 */
object FeedbackManager {

    private const val TAG = "FeedbackManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    // Auto-report throttle: don't send the same auto-report reason more than once per 10 min,
    // so a runaway loop can't flood the server or the parent's inbox.
    private val lastAutoReportMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val AUTO_REPORT_THROTTLE_MS = 10 * 60 * 1000L

    /**
     * Fire-and-forget AUTOMATIC crash/incident report. Called by the app itself when it
     * detects a problem (circuit breaker tripped, overlay failed, exception, self-suspend).
     * Fully defensive: wrapped so it can NEVER crash the caller. Throttled per-reason.
     */
    fun sendAutoReport(context: Context, reason: String, detail: String = "") {
        try {
            val now = System.currentTimeMillis()
            val last = lastAutoReportMs[reason] ?: 0L
            if (now - last < AUTO_REPORT_THROTTLE_MS) {
                Log.v(TAG, "Auto-report '$reason' throttled")
                return
            }
            lastAutoReportMs[reason] = now

            Thread {
                try {
                    val deviceId = try { DeviceIdentifier.getPersistentId(context) } catch (_: Throwable) { "anon" }
                    val appVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
                    val logs = captureRecentLogs()
                    val stateInfo = try {
                        val scheduler = com.kiddolock.app.services.TimeScheduler(context)
                        val cfg = scheduler.getConfig()
                        val kidsOn = com.kiddolock.app.management.KidsModeManager(context).isEnabled
                        "KidsMode=$kidsOn | BedtimeActive=${scheduler.isBedtimeActive()} | LimitReached=${scheduler.isDailyLimitReached()} | UsageToday=${scheduler.getTodayUsageMinutes()}min"
                    } catch (_: Throwable) { "" }

                    val payload = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("text", if (detail.isBlank()) reason else "$reason\n\n$detail")
                        put("category", "auto_crash")
                        put("isAutoReport", true)
                        put("appVersion", appVersion)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android", Build.VERSION.RELEASE)
                        put("timestamp", System.currentTimeMillis())
                        put("appState", stateInfo)
                        put("recentLogs", logs)
                    }
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("${ApiConfig.BASE_URL}/api/feedback").post(body).build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) Log.i(TAG, "Auto-report '$reason' sent")
                        else { saveOffline(context, payload); Log.w(TAG, "Auto-report failed (saved offline)") }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Auto-report thread failed: ${e.message}")
                }
            }.start()
        } catch (e: Throwable) {
            // Absolutely never let auto-reporting crash the app
            Log.w(TAG, "sendAutoReport outer guard caught: ${e.message}")
        }
    }

    /**
     * Send feedback async (fire-and-forget). UI callback delivers success/failure.
     */
    fun sendFeedback(
        context: Context,
        text: String,
        category: String = "general",
        rating: Int = 0,
        includeLogs: Boolean = true,
        screenshotBase64: String? = null,
        onDone: (success: Boolean, message: String) -> Unit
    ) {
        Thread {
            try {
                val deviceId = DeviceIdentifier.getPersistentId(context)
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val appVersion = pkgInfo.versionName ?: "?"
                val recentLogs = if (includeLogs) captureRecentLogs() else ""

                // Also include current app state for context
                val stateInfo = try {
                    val scheduler = com.kiddolock.app.services.TimeScheduler(context)
                    val cfg = scheduler.getConfig()
                    val kidsOn = com.kiddolock.app.management.KidsModeManager(context).isEnabled
                    "KidsMode=$kidsOn | BedtimeActive=${scheduler.isBedtimeActive()} | LimitReached=${scheduler.isDailyLimitReached()} | BonusActive=${scheduler.isBonusTimeActive()} | UsageToday=${scheduler.getTodayUsageMinutes()}min | DailyLimit=${cfg.dailyTimeLimitMinutes}min | Bedtime=${cfg.quietHoursStart}:${cfg.quietHoursStartMin}-${cfg.quietHoursEnd}:${cfg.quietHoursEndMin}"
                } catch (_: Throwable) { "" }

                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("text", text)
                    put("category", category)
                    put("rating", rating)
                    put("appVersion", appVersion)
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android", Build.VERSION.RELEASE)
                    put("timestamp", System.currentTimeMillis())
                    put("appState", stateInfo)
                    if (includeLogs) put("recentLogs", recentLogs)
                    if (!screenshotBase64.isNullOrBlank()) put("screenshot", screenshotBase64)
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${ApiConfig.BASE_URL}/api/feedback")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Feedback sent successfully")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onDone(true, "תודה! המשוב נשלח בהצלחה")
                        }
                    } else {
                        Log.w(TAG, "Feedback failed with code ${response.code}")
                        // Fallback: store locally so we can retry next launch
                        saveOffline(context, payload)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onDone(true, "המשוב נשמר ויישלח כשתהיה רשת")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Feedback exception", e)
                try {
                    val payload = JSONObject().apply {
                        put("text", text)
                        put("category", category)
                        put("rating", rating)
                        put("timestamp", System.currentTimeMillis())
                    }
                    saveOffline(context, payload)
                } catch (_: Throwabl