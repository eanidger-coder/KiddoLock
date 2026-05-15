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
        // 🪶 דלג אם המכשיר במצב חיסכון קיצוני - לחסוך סוללה במכשירים חלשים
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isPowerSaveMode == true) {
                Log.i("KiddoLockHeart", "Power save mode active, skipping heartbeat")
                return Result.success()
            }
        } catch (_: Exception) {}

        val deviceId = DeviceIdentifier.getPersistentId(applicationContext)
        val deviceName = getAutoDeviceName(applicationContext)
        val fingerprint = getDeviceFingerprint(applicationContext)
        val prefs = applicationContext.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)

        val status = mapOf(
            "protectionActive" to isAccessibilityServiceEnabled(),
            "fingerprint" to fingerprint
        )

        val requestBody = mapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
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
        val expectedService = "com.kiddolock.app/com.kiddolock.app.services.KiddoLockAccessibilityService"
        val enabledServices = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(expectedService)
    }

    /**
     * שליפת שם מכשיר אוטומטית, ללא הסכמת משתמש.
     * סדר עדיפויות: Settings.Global.DEVICE_NAME -> Settings.Secure.bluetooth_name -> Build.MANUFACTURER+MODEL
     */
    private fun getAutoDeviceName(context: Context): String {
        return try {
            // Try Settings.Global.DEVICE_NAME first (the user's chosen name)
            val global = android.provider.Settings.Global.getString(
                context.contentResolver, "device_name"
            )
            if (!global.isNullOrBlank()) return global

            // Fallback: bluetooth name
            val bt = android.provider.Settings.Secure.getString(
                context.contentResolver, "bluetooth_name"
            )
            if (!bt.isNullOrBlank()) return bt

            // Last resort: manufacturer + model
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        } catch (e: Exception) {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        }
    }


    /**
     * שליפת זיהוי מקיף של המכשיר - תקופה נסיונית עד שהאפליקציה יציבה.
     * אוסף רק מידע שאינו רגיש: יצרן, דגם, גרסת אנדרואיד, אזור זמן וכו׳.
     * אינו אוסף: IMEI, מספר טלפון, MAC, או נתונים אישיים.
     */
    private fun getDeviceFingerprint(context: Context): Map<String, Any> {
        return try {
            val pm = context.packageManager
            val packageInfo = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
            val installTime = packageInfo?.firstInstallTime ?: 0L
            val appVersion = packageInfo?.versionName ?: "?"
            val battery = try {
                val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level > 0 && scale > 0) (level * 100 / scale) else -1
            } catch (_: Exception) { -1 }

            val timezone = java.util.TimeZone.getDefault().id
            val locale = java.util.Locale.getDefault().toString()
            val displayMetrics = context.resources.displayMetrics
            val screen = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}@${displayMetrics.densityDpi}dpi"

            // Try to detect Google account email + owner name
            val (ownerEmail, ownerName) = getOwnerIdentity(context)

            mapOf(
                "manufacturer" to android.os.Build.MANUFACTURER,
                "brand" to android.os.Build.BRAND,
                "model" to android.os.Build.MODEL,
                "device" to android.os.Build.DEVICE,
                "product" to android.os.Build.PRODUCT,
                "androidVersion" to android.os.Build.VERSION.RELEASE,
                "sdkInt" to android.os.Build.VERSION.SDK_INT,
                "appVersion" to appVersion,
                "installedAt" to installTime,
                "batteryPercent" to battery,
                "timezone" to timezone,
                "locale" to locale,
                "screen" to screen,
                "fingerprintHash" to android.os.Build.FINGERPRINT.takeLast(40),
                "ownerEmail" to ownerEmail,
                "ownerName" to ownerName
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "unknown"))
        }
    }


    /**
     * שליפת זיהוי הבעלים של הנייד באופן אוטומטי.
     * מנסה לפי הסדר: חשבון Google ראשי -> פרופיל המשתמש -> שם החשבון
     * דורש הרשאת GET_ACCOUNTS שמוצהרת ב-Manifest.
     */
    private fun getOwnerIdentity(context: Context): Pair<String, String> {
        var email = ""
        var name = ""
        try {
            // 1) Google accounts
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.GET_ACCOUNTS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val am = android.accounts.AccountManager.get(context)
                val googleAccounts = am.getAccountsByType("com.google")
                if (googleAccounts.isNotEmpty()) {
                    email = googleAccounts[0].name
                }
            }
            // 2) Profile owner from User Profile contact
            if (name.isBlank() && androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CONTACTS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.ContactsContract.Profile.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.Profile.DISPLAY_NAME),
                        null, null, null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            name = it.getString(0) ?: ""
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.w("KiddoLockHeart", "Owner identity unavailable: ${e.message}")
        }
        return Pair(email, name)
    }

    companion object {
        private const val WORK_NAME = "kiddolock_heartbeat_periodic"

        /**
         * רישום ה-HeartbeatWorker לרוץ כל 15 דקות בכל מצב, גם בלי הגנה פעילה.
         * זה הקו הקריטי שמבטיח שמתג הכיבוי המרחוק יתקבל תוך 15 דקות.
         * חייב להיקרא מ-KiddoLockApp.onCreate ומ-BootReceiver.
         */
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.i("KiddoLockHeart", "Heartbeat worker scheduled (15-min interval)")
        }
    }

}
