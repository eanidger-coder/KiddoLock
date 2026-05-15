package com.kiddolock.app
import com.kiddolock.app.R

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.AppUsageManager
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.services.KiddoLockAccessibilityService
import com.kiddolock.app.ui.AdminActivity
import com.kiddolock.app.ui.AdminPinActivity
import com.kiddolock.app.ui.SetupActivity
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.utils.HelpTooltips

class MainActivity : AppCompatActivity() {

    private lateinit var tvChildName: TextView
    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvProtectionDetail: TextView
    private lateinit var tvPinStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var swKidsModeMain: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvKidsModeStatusMain: TextView
    private lateinit var kidsModeManager: KidsModeManager
    private var cachedBlockedCount: Int = -1
    private var lastBlockedCountRefreshMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        wireHelpIcons()
        
        // Sync settings from cloud on start
        com.kiddolock.app.management.SettingsSyncManager(this).syncSettingsOnStart()
        
        AppUsageManager.trackAppOpen(this)

        // Request notification permission silently (no Toast, no redirect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        startTickerIfNeeded()

        // === SINGLE CHECK: if critical permissions are missing → redirect to SetupActivity ===
        // This replaces the old chaos of multiple Toasts + multiple settings intents
        if (!isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
            // Don't finish() — user can come back after completing setup
            return
        }

        // Auto-revoke protection: ask user (once) to disable App Hibernation so Android does not
        // strip our permissions or pause background work after weeks of low parent interaction.
        try {
            checkAndPromptUnusedAppRestrictions()
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "Unused-app prompt failed: " + e.message)
        }

        // === PIN protection (only after setup is complete) ===
        if (!AdminPinManager.isPinSet(this)) {
            startActivity(Intent(this, AdminPinActivity::class.java))
        } else if (!AdminPinManager.isAuthenticated()) {
            startActivity(Intent(this, AdminPinActivity::class.java))
        }
    }

    /**
     * Check if all critical permissions are set up.
     * If ANY is missing, redirect to SetupActivity guided flow.
     */
    private fun isSetupComplete(): Boolean {
        // 1. Overlay permission
        if (!Settings.canDrawOverlays(this)) return false

        // 2. Accessibility service
        if (!isAccessibilityServiceEnabled()) return false

        // 3. Device Admin
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, KiddoDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComponent)) return false

        // 4. Usage Access
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        if (mode != AppOpsManager.MODE_ALLOWED) return false

        return true
    }

    private fun initViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        tvProtectionDetail = findViewById(R.id.tvProtectionDetail)
        tvPinStatus = findViewById(R.id.tvPinStatus)
        tvVersion = findViewById(R.id.tvVersion)

        swKidsModeMain = findViewById(R.id.swKidsModeMain)
        tvKidsModeStatusMain = findViewById(R.id.tvKidsModeStatusMain)
        kidsModeManager = KidsModeManager(this)

        tvVersion.text = "KiddoLock גרסה ${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    private fun setupListeners() {
        findViewById<View>(R.id.cardAppManagement).setOnClickListener {
            checkPinAndNavigate(AdminActivity::class.java)
        }

        findViewById<View>(R.id.cardAdminPin).setOnClickListener {
            checkPinAndNavigate(AdminPinActivity::class.java)
        }

        swKidsModeMain.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener // Only trigger on user interaction
            
            kidsModeManager.isEnabled = isChecked
            // Invalidate cache and clear all temporary bypasses to ensure immediate protection
            if (isChecked) {
                AppBlockManager.clearAllBypasses(this)
            } else {
                AppBlockManager.invalidateCache()
            }
            updateStatus()
            
            if (isChecked) {
                Toast.makeText(this, "מצב ילדים הופעל - ההגנות נכנסו לתוקף", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "מצב ילדים כבוי - המכשיר חופשי", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPinAndNavigate(destination: Class<*>) {
        if (!AdminPinManager.isPinSet(this)) {
            startActivity(Intent(this, destination))
            return
        }

        // Parent-friendly mode: when Kids Mode is OFF (parent is using their own phone)
        // do not require PIN to enter admin areas. PIN is only required when the kid is
        // actually using the phone (Kids Mode = ON).
        if (!KidsModeManager(this).isEnabled) {
            AdminPinManager.extendSession()
            startActivity(Intent(this, destination))
            return
        }

        if (AdminPinManager.isAuthenticated()) {
            startActivity(Intent(this, destination))
        } else {
            val intent = Intent(this, AdminPinActivity::class.java)
            startActivity(intent)
        }
    }

    private fun updateStatus() {
        val isLocked = AppBlockManager.isLocked(this)
        val kidsModeEnabled = KidsModeManager(this).isEnabled

        // 🎯 LIVE DATA: bind dashboard widgets to real values (was hardcoded "2:15"/"12"/"4h"/"1:45")
        try {
            val scheduler = com.kiddolock.app.services.TimeScheduler(this)
            val config = scheduler.getConfig()
            val usageMin = scheduler.getTodayUsageMinutes()
            val limitMin = if (config.dailyTimeLimitEnabled) config.dailyTimeLimitMinutes else -1
            val remainMin = if (config.dailyTimeLimitEnabled) maxOf(0, limitMin - usageMin) else -1

            // ⏰ Big ring shows HH:MM:SS, small cards show compact format (HH:MM only)
            // ⏰ HH:MM format (no fake :00 seconds - the timer changes per minute, not second)
            fun formatTime(m: Int): String {
                if (m < 0) return "∞"
                return "%02d:%02d".format(m / 60, m % 60)
            }
            findViewById<android.widget.TextView>(R.id.tvRemainingTime)?.text = formatTime(remainMin)
            findViewById<android.widget.TextView>(R.id.tvDailyLimit)?.text = formatTime(limitMin)
            findViewById<android.widget.TextView>(R.id.tvUsageToday)?.text = formatTime(usageMin)
            // ⚡ Cached blocked count - refresh only once per 30 seconds to prevent flicker.
            val now = System.currentTimeMillis()
            val tvBlocked = findViewById<android.widget.TextView>(R.id.tvBlockedCount)
            if (cachedBlockedCount >= 0) {
                tvBlocked?.text = cachedBlockedCount.toString()
            } else {
                tvBlocked?.text = "..."
            }
            if (now - lastBlockedCountRefreshMs > 30_000) {
                lastBlockedCountRefreshMs = now
                Thread {
                    try {
                        val pm = packageManager
                        val appManager = com.kiddolock.app.management.AppBlockManager.getAppManager(this)
                        val installed = pm.getInstalledApplications(0)
                        var actualBlocked = 0
                        for (info in installed) {
                            if (appManager.isBlacklisted(info.packageName)) actualBlocked++
                        }
                        cachedBlockedCount = actualBlocked
                        runOnUiThread { tvBlocked?.text = actualBlocked.toString() }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Blocked count failed", e)
                    }
                }.start()
            }

            // Hide/show stats row based on Kids Mode
            findViewById<android.view.View>(R.id.statsRow)?.visibility =
                if (kidsModeEnabled) android.view.View.VISIBLE else android.view.View.VISIBLE
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to bind dashboard widgets", e)
        }
        
        if (isLocked) {
            tvProtectionStatus.text = "המכשיר נעול"
            tvProtectionStatus.setTextColor(Color.RED)
            tvProtectionDetail.text = "🔒"
        } else if (!kidsModeEnabled) {
            tvProtectionStatus.text = "מצב ילדים כבוי"
            tvProtectionStatus.setTextColor(Color.GRAY)
            tvProtectionDetail.text = "💤"
        } else {
            tvProtectionStatus.text = getString(R.string.protection_active)
            tvProtectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            tvProtectionDetail.text = "🛡️"
        }

        // Sync local switch state with manager
        if (::swKidsModeMain.isInitialized) {
            swKidsModeMain.isChecked = kidsModeEnabled
            tvKidsModeStatusMain.text = if (kidsModeEnabled) "כל ההגנות פעילות" else "המכשיר במצב חופשי"
        }

        updatePinStatus()
    }

    private fun updatePinStatus() {
        val isPinSet = AdminPinManager.isPinSet(this)
        tvPinStatus.text = if (isPinSet) getString(R.string.pin_set) else "לא הוגדר קוד"
        tvPinStatus.setTextColor(if (isPinSet) ContextCompat.getColor(this, R.color.success_green) else Color.GRAY)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, KiddoLockAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expectedComponentName.flattenToString()) == true
    }

    /**
     * Android 11+ hibernates apps that haven't been used in ~3 months, revoking permissions.
     * This would break KiddoLock for parents who set protection and don't open the app often.
     * We ask the user (once) to allow our exemption from auto-revoke / hibernation.
     */
    private fun checkAndPromptUnusedAppRestrictions() {
        val prefs = getSharedPreferences("kiddolock_main_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("unused_app_prompted", false)) return
        try {
            val future = androidx.core.content.PackageManagerCompat.getUnusedAppRestrictionsStatus(this)
            future.addListener({
                try {
                    val status = future.get()
                    if (status == androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT ||
                        status == androidx.core.content.UnusedAppRestrictionsConstants.API_30 ||
                        status == androidx.core.content.UnusedAppRestrictionsConstants.API_31) {
                        // Restrictions are enabled - ask user to disable
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("הגנה חשובה - לאשר פעם אחת")
                            .setMessage("אנדרואיד עלולה לבטל את ההרשאות של KiddoLock אם לא תפעיל את האפליקציה במשך 3 חודשים. כדי שההגנה על הילדים לא תיפסק לבד, אישור חד-פעמי חיוני. לחיצה על 'אשר' תעביר אותך להגדרה ב-Settings - שם הזז את 'הסר הרשאות אם האפליקציה לא בשימוש' לכבוי.")
                            .setPositiveButton("אשר") { _, _ ->
                                try {
                                    val intent = androidx.core.content.IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName)
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.w("MainActivity", "Could not open unused-app settings: " + e.message)
                                }
                                prefs.edit().putBoolean("unused_app_prompted", true).apply()
                            }
                            .setNegativeButton("לא עכשיו") { _, _ ->
                                prefs.edit().putBoolean("unused_app_prompted", true).apply()
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        // Already disabled or feature not available - record so we don't ask again
                        prefs.edit().putBoolean("unused_app_prompted", true).apply()
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("MainActivity", "getUnusedAppRestrictionsStatus failed: " + e.message)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "PackageManagerCompat unavailable: " + e.message)
        }
    }


    /**
     * 🚨 פרצת אבטחה תוקנה: כשמשתמש יוצא מהאפליקציה (כפתור בית, multi-tasking, אפליקציה אחרת),
     * נמחק את ה-session ויידרש PIN שוב בחזרה לאפליקציה.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AdminPinManager.clearSession()
    }

    // ⏱️ Live ticker: updates the timer every second while MainActivity is visible
    private val timeUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            try { updateStatus() } catch (_: Exception) {}
            timeUpdateHandler.postDelayed(this, 10000)
        }
    }
    private fun startTickerIfNeeded() {
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
        // Update every 10 seconds (timer only changes per minute, but 10s gives smooth experience)
        timeUpdateHandler.postDelayed(timeUpdateRunnable, 10000)
    }

    override fun onPause() {
        super.onPause()
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
    }
    private fun wireHelpIcons() {
        val helpKids = findViewById<android.view.View?>(R.id.btnHelpKidsMode)
        if (helpKids != null) {
            HelpTooltips.attach(helpKids, HelpTooltips.HelpTopic.PROTECTION_STATUS)
        }
    }

}
