package com.kiddolock.app.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.kiddolock.app.config.ApiConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OTA (Over-The-Air) update checker for KiddoLock.
 *
 * KiddoLock is distributed outside Google Play, so it can't auto-update via the store.
 * This polls the Cloudflare worker (/api/latest-version) and, if a newer APK exists,
 * notifies the parent and (on confirm) downloads + launches the installer.
 *
 * Fully defensive: every path is wrapped so an update check can NEVER crash the app.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS = "kiddolock_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // at most once per 6 hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val versionName: String, val versionCode: Int, val apkUrl: String, val changelog: String, val mandatory: Boolean)

    /**
     * Check for an update in the background. Calls onUpdateAvailable on the main thread
     * only if a strictly newer versionCode exists. Throttled to once per 6 hours.
     */
    fun checkForUpdate(context: Context, force: Boolean = false, onUpdateAvailable: (UpdateInfo) -> Unit) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
                Log.v(TAG, "Update check throttled")
                return
            }
            Thread {
                try {
                    val currentCode = try {
                        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
                        else @Suppress("DEPRECATION") pi.versionCode
                    } catch (_: Throwable) { 0 }

                    val request = Request.Builder().url("${ApiConfig.BASE_URL}/api/latest-version").get().build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@Thread
                        val json = JSONObject(resp.body?.string() ?: return@Thread)
                        val info = UpdateInfo(
                            versionName = json.optString("versionName", "?"),
                            versionCode = json.optInt("versionCode", 0),
                            apkUrl = json.optString("apkUrl", ""),
                            changelog = json.optString("changelog", ""),
                            mandatory = json.optBoolean("mandatory", false)
                        )
                        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                        if (info.versionCode > currentCode && info.apkUrl.isNotBlank()) {
                            Log.i(TAG, "Update available: ${info.versionName} (code ${info.versionCode} > $currentCode)")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { onUpdateAvailable(info) } catch (_: Throwable) {}
                            }
                        } else {
                            Log.v(TAG, "Already up to date (current=$currentCode, latest=${info.versionCode})")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Update check thread failed: ${e.message}")
                }
            }.start()
        } catch (e: Throwable) {
            Log.w(TAG, "checkForUpdate outer guard: ${e.message}")
        }
    }

    /**
     * Download the APK via DownloadManager and launch the system installer when done.
     * Requires REQUEST_INSTALL_PACKAGES; if not granted, sends the user to grant it.
     */
    fun downloadAndInstall(context: Context, apkUrl: String) {
        try {
            // On Android 8+, ensure we can install from this source
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    android.widget.Toast.makeText(context, "אשר התקנה ממקור זה, ואז לחץ שוב על העדכון", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
                return
            }

            val fileName = "KiddoLock-update.apk"
            val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(destDir, fileName)
            if (destFile.exists()) destFile.delete()

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("עדכון KiddoLock")
                setDescription("מוריד את הגרסה החדשה...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadId = dm.enqueue(req)
            android.widget.Toast.makeText(context, "מוריד עדכון...", android.widget.Toast.LENGTH_SHORT).show()

            // Listen for completion, then launch installer
            val onComplete = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (id != downloadId) return
                        try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                        val apkUri = FileProvider.getUriForFile(
                            ctx, "${ctx.packageName}.fileprovider", destFile
                        )
                        val install = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        ctx.startActivity(install)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Install launch failed: ${e.message}")
                        android.widget.Toast.makeText(ctx, "ההורדה הושלמה. פתח את הקובץ מההורדות כדי להתקין.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(onComplete, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "downloadAndInstall failed: ${e.message}")
            android.widget.Toast.makeText(context, "שגיאה בהורדת העדכון: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
