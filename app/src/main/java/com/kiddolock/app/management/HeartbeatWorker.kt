package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.kiddolock.app.config.ApiConfig
import com.kiddolock.app.services.TimeScheduler
import com.kiddolock.app.utils.DeviceIdentifier
import com.kiddolock.app.utils.LoggerUtils
import com.kiddolock.app.utils.SecurityUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Background worker that reports device status and fetches remote commands.
 */
class HeartbeatWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun doWork(): Result {
        val deviceId = DeviceIdentifier.getPersistentId(applicationContext)
        val prefs = applicationContext.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)

        val status = mapOf(
            "protectionActive" to isAccessibilityServiceEnabled()
        )

        val requestBody = mapOf(
            "deviceId" to deviceId,
            "status" to status
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody(mediaType)
        
        // Use production Cloudflare URL from central config (never hardcode IPs!)
        val apiUrl = "${ApiConfig.BASE_URL}/api/heartbeat"

        val timestamp = System.currentTimeMillis()
        val path = "/api/heartbeat"
        val signature = SecurityUtils.getKiddoSignature(timestamp, path)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("X-Kiddo-Signature", signature)
            .addHeader("X-Kiddo-Timestamp", timestamp.toString())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    processCommands(responseData)
                    Result.success()
                } else {
                    Log.w("KiddoLockHeart", "Heartbeat failed: ${response.code}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("KiddoLockHeart", "Error sending heartbeat", e)
            Result.retry()
        }
    }

    private fun processCommands(jsonResponse: String?) {
        if (jsonResponse == null) return
        try {
            val responseMap = gson.fromJson(jsonResponse, Map::class.java)
            val commands = responseMap["commands"] as? List<Map<String, Any>>
            
            commands?.forEach { cmd ->
                val type = cmd["command_type"] as? String
                val payload = cmd["payload"] as? String
                executeCommand(type, payload)
            }
        } catch (e: Exception) {
            Log.e("KiddoLockHeart", "Error parsing commands", e)
        }
    }

    private fun executeCommand(type: String?, payload: String?) {
        RemoteCommandHandler(applicationContext).execute(type, payload)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "com.kiddolock.app/com.kiddolock.app.services.SafeLockAccessibilityService"
        val enabledServices = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(expectedService)
    }
}
