package com.kiddolock.app.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kiddolock.app.R
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.AppManager
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.receivers.PolicyManager
import com.kiddolock.app.services.TimeScheduler
import com.kiddolock.app.management.SettingsSyncManager
import com.kiddolock.app.utils.Prefs
import com.kiddolock.app.ui.adapter.AppListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminActivity : AppCompatActivity() {
    
    companion object {
        // Flag to tracking if the parent has verified their PIN for this session
        // This is static so it survives while the app process is alive
        var isSessionAuthorized = false
        
        fun resetSession() {
            isSessionAuthorized = false
        }
    }

    private val appManager by lazy { AppManager(this) }
    private lateinit var kidsModeManager: KidsModeManager
    private lateinit var timeScheduler: TimeScheduler
    private lateinit var appListAdapter: AppListAdapter
    
    private lateinit var tvVersion: TextView
    private lateinit var etSearch: EditText
    private lateinit var rvApps: RecyclerView
    private lateinit var pbLoading: ProgressBar

    
    private lateinit var cardProtectionStatus: com.google.android.material.card.MaterialCardView
    private lateinit var viewStatusDot: View
    
    // Time views
    private lateinit var tvBedtimeValue: TextView
    private lateinit var tvDailyLimitValue: TextView

    // Security & Recovery views
    // Removed to streamline Admin UI per user request

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var allApps: List<AppManager.AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        kidsModeManager = KidsModeManager(this)
        timeScheduler = TimeScheduler(this)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KiddoDeviceAdminReceiver::class.java)

        initViews()
        setupListeners()
        appManager.initialize() // Ensure blacklist is loaded/migrated
        loadApps()
        updateTimeValues()
        updateSystemStatus()
    }

    override fun onStart() {
        super.onStart()
        
        // Check if the session is currently authorized via AdminPinManager
        // This is cleared automatically in KiddoLockApp if the app goes to background
        if (!com.kiddolock.app.management.AdminPinManager.isAuthenticated()) {
            isSessionAuthorized = false
            val intent = Intent(this, AdminPinActivity::class.java).apply {
                action = "com.kiddolock.app.ADMIN_AUTH"
            }
            startActivity(intent)
        } else {
            isSessionAuthorized = true
            Log.d("AdminActivity", "Session active")
        }
    }

    override fun onStop() {
        super.onStop()
        // No longer using grace period logic here as KiddoLockApp handles process-level lockout
    }

    private fun initViews() {
        tvVersion = findViewById(R.id.tvVersion)
        etSearch = findViewById(R.id.etSearchApps)
        rvApps = findViewById(R.id.rvApps)
        pbLoading = findViewById(R.id.pbLoadingApps)
        
        tvBedtimeValue = findViewById(R.id.tvBedtimeValue)
        tvDailyLimitValue = findViewById(R.id.tvDailyLimitValue)




        // Setup RecyclerView
        appListAdapter = AppListAdapter(emptyList()) { packageName, isBlacklisted ->
            if (isBlacklisted) appManager.blacklistApp(packageName)
            else appManager.whitelistApp(packageName)
            
            // CRITICAL: Clear the cached blacklist so changes take effect immediately
            AppBlockManager.invalidateCache()
            
            lifecycleScope.launch(Dispatchers.IO) {
                val updatedApps = appManager.getInstalledApps()
                withContext(Dispatchers.Main) {
                    appListAdapter.updateApps(updatedApps)
                }
            }
        }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = appListAdapter

        // Setup switches
        // Setup Admin app state
        


        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "גרסה ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "גרסה 1.3.4"
        }


        
        cardProtectionStatus = findViewById(R.id.cardProtectionStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        
        startPulseAnimation()

        // Removed recovery email initialization to streamline UI


    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Search functionality

        // Search functionality
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Time Management buttons (consolidated under App Management)
        findViewById<View>(R.id.btnBedtime).setOnClickListener {
            showBedtimePicker()
        }

        findViewById<View>(R.id.btnDailyLimit).setOnClickListener {
            showDailyLimitPicker()
        }




        // Instant Lock logic (Removed from UI but keeping functionality available for remote if needed)

        // AI Insights logic
        findViewById<View>(R.id.cardAiInsights).setOnClickListener {
            startActivity(Intent(this, ParentInsightsActivity::class.java))
        }

        // Removed recovery email listeners to streamline UI

        // Help & About listener
        findViewById<View>(R.id.cardHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
    }


    private fun loadApps() {
        pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = appManager.getInstalledApps()
            withContext(Dispatchers.Main) {
                allApps = apps
                pbLoading.visibility = View.GONE
                appListAdapter.updateApps(allApps)
            }
        }
    }

    private fun filterApps(query: String) {
        val filtered = allApps.filter { 
            it.appName.contains(query, ignoreCase = true) || 
            it.packageName.contains(query, ignoreCase = true) 
        }
        appListAdapter.updateApps(filtered)
    }

    private fun updateTimeValues() {
        tvBedtimeValue.text = timeScheduler.getBedtimeRangeString() ?: "לא הוגדר (21:00 - 07:00)"
        tvDailyLimitValue.text = timeScheduler.getDailyLimitString() ?: "ללא הגבלה"
    }

    private fun showBedtimePicker() {
        val config = timeScheduler.getConfig()
        val startPicker = TimePickerDialog(this, { _, h, m ->
            // Start time set, now pick end time
            val endPicker = TimePickerDialog(this, { _, eh, em ->
                timeScheduler.saveConfig(config.copy(
                    quietHoursEnabled = true,
                    quietHoursStart = h,
                    quietHoursStartMin = m,
                    quietHoursEnd = eh,
                    quietHoursEndMin = em
                ))
                updateTimeValues()
                Toast.makeText(this, "הגדרות שינה עודכנו", Toast.LENGTH_SHORT).show()
            }, config.quietHoursEnd, config.quietHoursEndMin, true)
            endPicker.setTitle("בחר שעת סיום (שעות שקט)")
            endPicker.show()
        }, config.quietHoursStart, config.quietHoursStartMin, true)
        startPicker.setTitle("בחר שעת התחלה (שעות שקט)")
        startPicker.show()
    }

    private fun showDailyLimitPicker() {
        val items = arrayOf("5 דקות", "10 דקות", "15 דקות", "30 דקות", "שעה אחת", "שעתיים", "3 שעות", "לא מוגבל")
        val values = intArrayOf(5, 10, 15, 30, 60, 120, 180, -1)
        
        AlertDialog.Builder(this)
            .setTitle("בחר מגבלה יומית")
            .setItems(items) { _, which ->
                val limit = values[which]
                if (limit == -1) {
                    timeScheduler.disableDailyTimeLimit()
                } else {
                    timeScheduler.setDailyTimeLimit(limit)
                }
                updateTimeValues()
                Toast.makeText(this, "מגבלה עודכנה: ${items[which]}", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showUninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_action_uninstall)
            .setMessage("האם אתה בטוח שברצונך להסיר את KiddoLock? פעולה זו תבטל את כל ההגנות באופן מיידי.")
            .setPositiveButton("הסר עכשיו") { _, _ ->
                // Full cleanup chain to guarantee uninstall works:
                
                // 1. Set certified flag FIRST (prevents BypassGuard from blocking)
                Prefs(this).certified_uninstall_in_progress = true
                
                // 2. Clear all Device Owner policies (if applicable)
                PolicyManager.clearPolicies(this, adminComponent)
                
                // 3. Remove Device Admin entirely (allows system uninstall)
                PolicyManager.removeDeviceAdmin(this)
                
                // 4. Disable uninstall protection pref locally immediately
                getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("uninstall_protection_enabled", false)
                    .putBoolean("bypass_guard_enabled", false)
                    .apply()
                
                // 5. Explicitly allow the package manager to uninstall us
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        dpm.setUninstallBlocked(adminComponent, packageName, false)
                    } catch (_: Exception) {}
                }

                // 6. Launch system uninstall intent
                val intent = Intent(Intent.ACTION_DELETE)
                intent.data = Uri.parse("package:$packageName")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                
                finish()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateSystemStatus()
    }

    private fun updateSystemStatus() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        
        // Update Protection Banner based on core accessibility service AND Kids Mode toggle
        if (isAccessibilityEnabled && kidsModeManager.isEnabled) {
            findViewById<TextView>(R.id.tvProtectionStatusTitle).text = "מצב הגנה: פעיל"
            findViewById<TextView>(R.id.tvProtectionStatusSubtitle).text = "KiddoLock שומר על המכשיר כעת"
            viewStatusDot.setBackgroundResource(R.drawable.shape_dot_green)
            cardProtectionStatus.strokeColor = 0xFF4CAF50.toInt()
            if (viewStatusDot.animation == null) {
                startPulseAnimation()
            }
        } else if (isAccessibilityEnabled && !kidsModeManager.isEnabled) {
            findViewById<TextView>(R.id.tvProtectionStatusTitle).text = "מצב הגנה: מושעה"
            findViewById<TextView>(R.id.tvProtectionStatusSubtitle).text = "הגנות הושעו על ידי ההורה"
            viewStatusDot.setBackgroundResource(R.drawable.shape_dot_yellow)
            viewStatusDot.clearAnimation()
            cardProtectionStatus.strokeColor = 0xFFFFC107.toInt() // Amber/Yellow
        } else {
            findViewById<TextView>(R.id.tvProtectionStatusTitle).text = "מצב הגנה: כבוי"
            findViewById<TextView>(R.id.tvProtectionStatusSubtitle).text = "הפעל שירות נגישות כדי להגן"
            viewStatusDot.setBackgroundResource(R.drawable.shape_dot_red)
            viewStatusDot.clearAnimation()
            cardProtectionStatus.strokeColor = 0xFFF44336.toInt()
        }
    }


    private fun startPulseAnimation() {
        val pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse)
        viewStatusDot.startAnimation(pulse)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${com.kiddolock.app.services.KiddoLockAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(service) == true
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
