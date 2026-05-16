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

    /**
     * Send feedback async (fire-and-forget). UI callback delivers success/failure.
     */
    fun sendFeedback(
        context: Context,
        text: String,
        category: String = "general",
        rating: Int = 0,
        includeLogs: Boolean = true,
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
                } catch (_: Throwable) {}
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onDone(true, "המשוב נשמר ויישלח כשתהיה רשת")
                }
            }
        }.start()
    }

    /** Save feedback locally for retry later */
    private fun saveOffline(context: Context, payload: JSONObject) {
        val prefs = context.getSharedPreferences("kiddolock_feedback", Context.MODE_PRIVATE)
        val pending = prefs.getString("pending", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(pending)
            arr.put(payload)
            prefs.edit().putString("pending", arr.toString()).apply()
            Log.i(TAG, "Feedback stored offline (${arr.length()} pending)")
        } catch (e: Throwable) {
            Log.e(TAG, "Could not save offline feedback", e)
        }
    }

    /**
     * Capture the last N KiddoLock-tagged log lines.
     * Note: Android 4.1+ requires READ_LOGS or being a system app to read logcat of OTHER apps.
     * For our OWN app's logs, this works without any permission.
     */
    private fun captureRecentLogs(maxLines: Int = 80): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-t", maxLines.toString(),
                "AppBlockManager:V",
                "AccessibilityService:V",
                "OverlayService:V",
                "AdminPinManager:V",
                "TimeScheduler:V",
                "KiddoLockApp:V",
                "SafetyWatchdog:V",
                "KidsModeManager:V",
                "AndroidRuntime:E",
                "*:S"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Truncate if too long (Cloudflare worker limit)
            if (output.length > 8000) output.substring(output.length - 8000) else output
        } catch (e: Throwable) {
            Log.w(TAG, "Could not capture logs: " + e.message)
            "log capture failed: ${e.message}"
        }
    }

    /** Try to flush pending offline feedback. Called on app start. */
    fun flushPending(context: Context) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("kiddolock_feedback", Context.MODE_PRIVATE)
                val pending = prefs.getString("pending", "[]") ?: "[]"
                val arr = org.json.JSONArray(pending)
                if (arr.length() == 0) return@Thread

                var sent = 0
                val remaining = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val body = item.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("${ApiConfig.BASE_URL}/api/feedback")
                        .post(body)
                        .build()
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) sent++ else remaining.put(item)
                        }
                    } catch (_: Throwable) { remaining.put(item) }
                }
                prefs.edit().putString("pending", remaining.toString()).apply()
                Log.i(TAG, "Flushed $sent / ${arr.length()} pending feedback items")
            } catch (e: Throwable) {
                Log.w(TAG, "flushPending failed: " + e.message)
            }
        }.start()
    }
}
