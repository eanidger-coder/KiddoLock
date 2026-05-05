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
        
        // === SINGLE CHECK: if critical permissions are missing → redirect to SetupActivity ===
        // This replaces the old chaos of multiple Toasts + multiple settings intents
        if (!isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
            // Don't finish() — user can come back after completing setup
            return
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

    private fun wireHelpIcons() {
        val helpKids = findViewById<android.view.View?>(R.id.btnHelpKidsMode)
        if (helpKids != null) {
            HelpTooltips.attach(helpKids, HelpTooltips.HelpTopic.PROTECTION_STATUS)
        }
    }

}
