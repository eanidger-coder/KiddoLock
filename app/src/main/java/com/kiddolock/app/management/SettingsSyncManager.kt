package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kiddolock.app.config.ApiConfig
import com.kiddolock.app.services.TimeScheduler
import com.kiddolock.app.utils.DeviceIdentifier
import com.kiddolock.app.utils.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages syncing of consolidated app settings with the Cloudflare backend.
 */
class SettingsSyncManager(private val context: Context) {
    
    companion object {
        fun syncNow(context: Context) {
             SettingsSyncManager(context).pushSettings()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    data class CloudSettings(
        val kidsModeEnabled: Boolean,
        val strictYoutubeEnabled: Boolean,
        val strictModeEnabled: Boolean,
        val antiUninstallEnabled: Boolean,
        val bedtimeStart: Int,
        val bedtimeStartMin: Int,
        val bedtimeEnd: Int,
        val bedtimeEndMin: Int,
        val dailyLimitMinutes: Int,
        val perAppLimits: Map<String, Int>?,
        val blacklistedApps: List<String>? = null,
        val recoveryEmail: String? = null
    )

    fun syncSettingsOnStart() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val deviceId = DeviceIdentifier.getPersistentId(context)
                val apiUrl = "${ApiConfig.BASE_URL}/api/settings?deviceId=$deviceId"
                
                val timestamp = System.currentTimeMillis()
                val path = "/api/settings"
                val signature = SecurityUtils.getKiddoSignature(timestamp, path)

                val request = Request.Builder()
                    .url(apiUrl)
                    .get()
                    .addHeader("X-Kiddo-Signature", signature)
                    .addHeader("X-Kiddo-Timestamp", timestamp.toString())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { json ->
                            val settings = gson.fromJson(json, CloudSettings::class.java)
                            applySettingsLocally(settings)
                        }
                    } else {
                        Log.w("SettingsSync", "Failed to pull settings: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsSync", "Error syncing settings", e)
            }
        }
    }

    fun pushSettings() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val deviceId = DeviceIdentifier.getPersistentId(context)
                val settings = gatherLocalSettings()
                
                val requestBody = mapOf(
                    "deviceId" to deviceId,
                    "settings" to settings
                )
                
                val json = gson.toJson(requestBody)
                val body = json.toRequestBody(mediaType)
                
                val apiUrl = "${ApiConfig.BASE_URL}/api/settings"
                val timestamp = System.currentTimeMillis()
                val signature = SecurityUtils.getKiddoSignature(timestamp, "/api/settings")

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("X-Kiddo-Signature", signature)
                    .addHeader("X-Kiddo-Timestamp", timestamp.toString())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("SettingsSync", "Failed to push settings: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsSync", "Error pushing settings", e)
            }
        }
    }

    private fun applySettingsLocally(settings: CloudSettings) {
        val prefs = context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
        val kidsModeManager = KidsModeManager(context)
        val timeScheduler = TimeScheduler(context)
        val perAppTracker = com.kiddolock.app.management.PerAppTimeTracker(context)

        prefs.edit()
            .putBoolean("strict_mode_enabled", settings.strictModeEnabled)
            .putBoolean("uninstall_protection_enabled", settings.antiUninstallEnabled)
            .apply()

        kidsModeManager.isEnabled = settings.kidsModeEnabled

        val config = timeScheduler.getConfig().copy(
            quietHoursStart = settings.bedtimeStart,
            quietHoursStartMin = settings.bedtimeStartMin,
            quietHoursEnd = settings.bedtimeEnd,
            quietHoursEndMin = settings.bedtimeEndMin,
            dailyTimeLimitMinutes = settings.dailyLimitMinutes,
            quietHoursEnabled = settings.strictModeEnabled,
            dailyTimeLimitEnabled = settings.dailyLimitMinutes > 0 && settings.dailyLimitMinutes < 999
        )
        timeScheduler.saveConfig(config)

        settings.perAppLimits?.forEach { (pkg, limit) ->
            if (limit > 0) perAppTracker.setLimit(pkg, limit)
            else perAppTracker.setLimit(pkg, null)
        }

        // Restore blacklisted apps from cloud
        settings.blacklistedApps?.let { cloudBlacklist ->
            if (cloudBlacklist.isNotEmpty()) {
                val appManager = AppManager(context).apply { initialize() }
                // Sync: add any cloud-side blocked apps that aren't local
                cloudBlacklist.forEach { pkg -> appManager.blacklistApp(pkg) }
                // Invalidate the AppBlockManager cache so it picks up changes
                AppBlockManager.invalidateCache()
                Log.i("SettingsSync", "Restored ${cloudBlacklist.size} blacklisted apps from cloud")
            }
        }

        // Restore recovery email
        settings.recoveryEmail?.let { email ->
            if (email.isNotBlank()) {
                AdminPinManager.setRecoveryEmail(context, email)
                Log.i("SettingsSync", "Restored recovery email from cloud")
            }
        }
    }

    private fun gatherLocalSettings(): CloudSettings {
        val prefs = context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
        val kidsModeManager = KidsModeManager(context)
        val timeScheduler = TimeScheduler(context)
        val config = timeScheduler.getConfig()
        val perAppTracker = com.kiddolock.app.management.PerAppTimeTracker(context)
        val appManager = AppManager(context).apply { initialize() }

        val appLimits = perAppTracker.getAllLimitedApps().associate { it.packageName to (it.limitMinutes ?: 0) }
        val blacklist = appManager.getInstalledApps().filter { it.isBlacklisted }.map { it.packageName }

        return CloudSettings(
            kidsModeEnabled = kidsModeManager.isEnabled,
            strictYoutubeEnabled = false, // Resource was removed from KidsModeManager, defaulting to false
            strictModeEnabled = config.quietHoursEnabled,
            antiUninstallEnabled = prefs.getBoolean("uninstall_protection_enabled", true),
            bedtimeStart = config.quietHoursStart,
            bedtimeStartMin = config.quietHoursStartMin,
            bedtimeEnd = config.quietHoursEnd,
            bedtimeEndMin = config.quietHoursEndMin,
            dailyLimitMinutes = config.dailyTimeLimitMinutes,
            perAppLimits = appLimits,
            blacklistedApps = blacklist,
            recoveryEmail = AdminPinManager.getRecoveryEmail(context)
        )
    }
}
