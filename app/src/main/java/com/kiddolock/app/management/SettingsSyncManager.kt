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
        pullSettings { success ->
            if (success) Log.i("SettingsSync", "Initial settings pull successful")
        }
    }

    /**
     * Pulls the latest settings from the cloud for this device.
     */
    fun pullSettings(onComplete: ((Boolean) -> Unit)? = null) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val deviceId = DeviceIdentifier.getPersistentId(context)
                val apiUrl = "${ApiConfig.BASE_URL}/api/sync/$deviceId"
                
                val timestamp = System.currentTimeMillis()
                val path = "/api/sync/$deviceId"
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
                            val result = gson.fromJson(json, Map::class.java) as Map<String, Any>
                            val found = result["found"] as? Boolean ?: false
                            if (found) {
                                val configJson = gson.toJson(result["config"])
                                val settings = gson.fromJson(configJson, CloudSettings::class.java)
                                applySettingsLocally(settings)
                                
                                // Also restore pinHash if present and changed
                                (result["pinHash"] as? String)?.let { cloudHash ->
                                    val localHash = AdminPinManager.getStoredPinHash(context)
                                    if (cloudHash != localHash && cloudHash.isNotBlank()) {
                                        context.getSharedPreferences("admin_pin_prefs", Context.MODE_PRIVATE)
                                            .edit().putString("pin_hash", cloudHash).putBoolean("pin_set", true).apply()
                                        Log.i("SettingsSync", "Restored PIN hash from cloud")
                                    }
                                }
                                onComplete?.invoke(true)
                            } else {
                                onComplete?.invoke(false)
                            }
                        }
                    } else {
                        Log.w("SettingsSync", "Failed to pull settings: ${response.code}")
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsSync", "Error pulling settings", e)
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Pushes local settings to the cloud.
     */
    fun pushSettings(onComplete: ((Boolean) -> Unit)? = null) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val deviceId = DeviceIdentifier.getPersistentId(context)
                val settings = gatherLocalSettings()
                val pinHash = AdminPinManager.getStoredPinHash(context) ?: ""
                
                val payload = mapOf(
                    "deviceId" to deviceId,
                    "pinHash" to pinHash,
                    "config" to settings
                )
                
                val json = gson.toJson(payload)
                val body = json.toRequestBody(mediaType)
                
                val apiUrl = "${ApiConfig.BASE_URL}/api/sync"
                val timestamp = System.currentTimeMillis()
                val signature = SecurityUtils.getKiddoSignature(timestamp, "/api/sync")

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("X-Kiddo-Signature", signature)
                    .addHeader("X-Kiddo-Timestamp", timestamp.toString())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i("SettingsSync", "Successfully pushed settings to cloud")
                        // Update last sync time locally
                        context.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
                            .edit().putLong("last_cloud_sync", System.currentTimeMillis()).apply()
                        onComplete?.invoke(true)
                    } else {
                        Log.w("SettingsSync", "Failed to push settings: ${response.code}")
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsSync", "Error pushing settings", e)
                onComplete?.invoke(false)
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
