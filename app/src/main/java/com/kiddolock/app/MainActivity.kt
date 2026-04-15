package com.kiddolock.app
import com.kiddolock.app.R

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.AppUsageManager
import com.kiddolock.app.ui.AdminActivity
import com.kiddolock.app.ui.AdminPinActivity
import com.kiddolock.app.ui.SetupActivity
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.utils.PermissionUtils

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "SAFELOCK_FLOW" }

    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvProtectionDetail: TextView
    private lateinit var tvPinStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var swKidsModeMain: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvKidsModeStatusMain: TextView
    private lateinit var kidsModeManager: KidsModeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "MainActivity.onCreate: start")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // Sync settings from cloud on start (runs async — never blocks UI)
        try {
            com.kiddolock.app.management.SettingsSyncManager(this).syncSettingsOnStart()
        } catch (e: Throwable) {
            Log.e(TAG, "MainActivity: syncSettingsOnStart failed (non-fatal)", e)
        }

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
        Log.i(TAG, "MainActivity.onCreate: complete")
    }

    override fun onResume() {
        Log.i(TAG, "MainActivity.onResume: start")
        super.onResume()
        updateStatus()

        // === SINGLE CHECK: if critical permissions are missing → redirect to SetupActivity ===
        if (!PermissionUtils.isSetupComplete(this)) {
            Log.i(TAG, "MainActivity.onResume: setup NOT complete → launch SetupActivity")
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        Log.i(TAG, "MainActivity.onResume: setup IS complete")

        // === PIN protection (only after setup is complete) ===
        if (!AdminPinManager.isPinSet(this)) {
            Log.i(TAG, "MainActivity.onResume: no PIN set → AdminPinActivity")
            startActivity(Intent(this, AdminPinActivity::class.java))
        } else if (!AdminPinManager.isAuthenticated()) {
            Log.i(TAG, "MainActivity.onResume: PIN set but session expired → AdminPinActivity")
            startActivity(Intent(this, AdminPinActivity::class.java))
        } else {
            Log.i(TAG, "MainActivity.onResume: PIN ok — showing main UI")
        }
    }

    private fun initViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        tvProtectionDetail = findViewById(R.id.tvProtectionDetail)
        tvPinStatus = findViewById(R.id.tvPinStatus)
        tvVersion = findViewById(R.id.tvVersion)

        swKidsModeMain = findViewById(R.id.swKidsModeMain)
        tvKidsModeStatusMain = findViewById(R.id.tvKidsModeStatusMain)
        kidsModeManager = KidsModeManager(this)

        tvVersion.text = "SafeLock גרסה ${packageManager.getPackageInfo(packageName, 0).versionName}"
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
}
