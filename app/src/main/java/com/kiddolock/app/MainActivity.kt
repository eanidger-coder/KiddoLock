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
import com.kiddolock.app.content.ContentPreferences
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.utils.PermissionUtils

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "SAFELOCK_FLOW" }

    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvProtectionDetail: TextView
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

        // Sync settings from cloud on start (runs async)
        try {
            com.kiddolock.app.management.SettingsSyncManager(this).syncSettingsOnStart()
        } catch (e: Throwable) {
            Log.e(TAG, "MainActivity: syncSettingsOnStart failed (non-fatal)", e)
        }

        AppUsageManager.trackAppOpen(this)

        // Request notification permission silently
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

    private var isRedirectingAway = false

    override fun onResume() {
        Log.i(TAG, "MainActivity.onResume: start")
        super.onResume()
        isRedirectingAway = false
        updateStatus()

        // If critical permissions missing -> redirect to SetupActivity
        if (!PermissionUtils.isSetupComplete(this)) {
            Log.i(TAG, "MainActivity.onResume: setup NOT complete -> launch SetupActivity")
            isRedirectingAway = true
            startActivity(
                Intent(this, SetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            return
        }
        Log.i(TAG, "MainActivity.onResume: setup IS complete")

        // PIN protection (only after setup is complete)
        if (!AdminPinManager.isPinSet(this) || !AdminPinManager.isAuthenticated()) {
            if (!isRedirectingAway) {
                val reason = if (!AdminPinManager.isPinSet(this)) "no PIN set" else "session expired"
                Log.i(TAG, "MainActivity.onResume: $reason -> AdminPinActivity")
                isRedirectingAway = true
                startActivity(
                    Intent(this, AdminPinActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
            return
        }
        Log.i(TAG, "MainActivity.onResume: PIN ok -> showing main UI")
    }

    private fun initViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        tvProtectionDetail = findViewById(R.id.tvProtectionDetail)
        tvVersion = findViewById(R.id.tvVersion)

        swKidsModeMain = findViewById(R.id.swKidsModeMain)
        tvKidsModeStatusMain = findViewById(R.id.tvKidsModeStatusMain)
        kidsModeManager = KidsModeManager(this)

        tvVersion.text = "SafeLock ${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    private fun setupListeners() {
        findViewById<View>(R.id.cardAppManagement).setOnClickListener {
            checkPinAndNavigate(AdminActivity::class.java)
        }

        swKidsModeMain.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener

            kidsModeManager.isEnabled = isChecked
            if (isChecked) {
                AppBlockManager.clearAllBypasses(this)
            } else {
                AppBlockManager.invalidateCache()
            }
            updateStatus()

            if (isChecked) {
                Toast.makeText(this, "מצב הגנה הופעל", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "מצב הגנה כבוי", Toast.LENGTH_SHORT).show()
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
            startActivity(Intent(this, AdminPinActivity::class.java))
        }
    }

    private fun updateStatus() {
        val isLocked = AppBlockManager.isLocked(this)
        val kidsModeEnabled = KidsModeManager(this).isEnabled

        if (isLocked) {
            tvProtectionStatus.text = "המכשיר נעול"
            tvProtectionStatus.setTextColor(Color.RED)
            tvProtectionDetail.text = ""
        } else if (!kidsModeEnabled) {
            tvProtectionStatus.text = "ההגנה כבויה"
            tvProtectionStatus.setTextColor(Color.GRAY)
            tvProtectionDetail.text = ""
        } else {
            tvProtectionStatus.text = getString(R.string.protection_active)
            tvProtectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            tvProtectionDetail.text = ""
        }

        if (::swKidsModeMain.isInitialized) {
            swKidsModeMain.isChecked = kidsModeEnabled
            if (kidsModeEnabled) {
                val contentOn = ContentPreferences(this).contentFilterEnabled
                val features = mutableListOf("חסימת אפליקציות", "ניהול זמן")
                if (contentOn) features.add(0, "סינון תוכן")
                tvKidsModeStatusMain.text = features.joinToString(" + ")
            } else {
                tvKidsModeStatusMain.text = "המכשיר במצב חופשי"
            }
        }
    }
}
