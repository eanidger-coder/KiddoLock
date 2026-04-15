package com.kiddolock.app.ui
import com.kiddolock.app.R
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.SettingsSyncManager

import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.kiddolock.app.MainActivity
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.services.SafeLockAccessibilityService
import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.view.View

/**
 * Modern Setup Wizard for KiddoLock.
 * Guides parents through enabling essential protection permissions.
 * 6 steps: Notifications → Overlay → Accessibility → Device Admin → Usage Access → Recovery Email
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var cardNotifications: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var cardOverlay: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var cardAccessibility: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var cardDeviceAdmin: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var cardUsageAccess: androidx.constraintlayout.widget.ConstraintLayout
    
    private lateinit var imgNotificationStatus: ImageView
    private lateinit var imgOverlayStatus: ImageView
    private lateinit var imgAccessibilityStatus: ImageView
    private lateinit var imgDeviceAdminStatus: ImageView
    private lateinit var imgUsageStatus: ImageView

    private lateinit var tvNotifAction: TextView
    private lateinit var tvOverlayAction: TextView
    private lateinit var tvAccessibilityAction: TextView
    private lateinit var tvAdminAction: TextView
    private lateinit var tvUsageAction: TextView
    
    private lateinit var btnContinue: MaterialButton
    private lateinit var etRecoveryEmail: com.google.android.material.textfield.TextInputEditText
    private lateinit var tilRecoveryEmail: com.google.android.material.textfield.TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Mark setup as in progress so BypassGuard allows settings access
        setSetupInProgress(true)

        // Initialize UI components
        cardNotifications = findViewById(R.id.cardNotifications)
        cardOverlay = findViewById(R.id.cardOverlay)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardDeviceAdmin = findViewById(R.id.cardDeviceAdmin)
        cardUsageAccess = findViewById(R.id.cardUsageAccess)

        imgNotificationStatus = findViewById(R.id.imgNotificationStatus)
        imgOverlayStatus = findViewById(R.id.imgOverlayStatus)
        imgAccessibilityStatus = findViewById(R.id.imgAccessibilityStatus)
        imgDeviceAdminStatus = findViewById(R.id.imgDeviceAdminStatus)
        imgUsageStatus = findViewById(R.id.imgUsageStatus)

        tvNotifAction = findViewById(R.id.tvNotifAction)
        tvOverlayAction = findViewById(R.id.tvOverlayAction)
        tvAccessibilityAction = findViewById(R.id.tvAccessibilityAction)
        tvAdminAction = findViewById(R.id.tvAdminAction)
        tvUsageAction = findViewById(R.id.tvUsageAction)

        btnContinue = findViewById(R.id.btnContinue)
        etRecoveryEmail = findViewById(R.id.etRecoveryEmail)
        tilRecoveryEmail = findViewById(R.id.tilRecoveryEmail)

        setupListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        cardNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showPermissionGuide(
                    getString(R.string.guide_title_notifications),
                    getString(R.string.guide_desc_notifications),
                    R.drawable.guide_notifications // Placeholder/Generated
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
        }

        cardOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                showPermissionGuide(
                    getString(R.string.guide_title_overlay),
                    getString(R.string.guide_desc_overlay),
                    R.drawable.guide_overlay
                ) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }

        cardAccessibility.setOnClickListener {
            showPermissionGuide(
                getString(R.string.guide_title_accessibility),
                getString(R.string.guide_desc_accessibility),
                R.drawable.guide_accessibility
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "מצא את KiddoLock ברשימה והפעל אותו", Toast.LENGTH_LONG).show()
            }
        }

        cardDeviceAdmin.setOnClickListener {
            showPermissionGuide(
                getString(R.string.guide_title_admin),
                getString(R.string.guide_desc_admin),
                R.drawable.guide_admin
            ) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@SetupActivity, KiddoDeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.setup_admin_explanation))
                }
                startActivity(intent)
            }
        }

        cardUsageAccess.setOnClickListener {
            showPermissionGuide(
                getString(R.string.guide_title_usage),
                getString(R.string.guide_desc_usage),
                R.drawable.guide_usage_access // Placeholder/Generated
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "מצא את KiddoLock ברשימה והפעל גישה", Toast.LENGTH_LONG).show()
            }
        }


        btnContinue.setOnClickListener {
            val email = etRecoveryEmail.text.toString().trim()
            if (email.isNotEmpty() && !isValidEmail(email)) {
                tilRecoveryEmail.error = "כתובת אימייל לא תקינה"
                return@setOnClickListener
            }
            tilRecoveryEmail.error = null
            
            if (email.isNotEmpty()) {
                AdminPinManager.setRecoveryEmail(this, email)
            }
            SettingsSyncManager.syncNow(this)
            setSetupInProgress(false)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun updateStatus() {
        // Step 1: Notifications
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        setStepStatus(imgNotificationStatus, cardNotifications, hasNotifications, tvNotifAction)

        // Step 2: Overlay
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        setStepStatus(imgOverlayStatus, cardOverlay, hasOverlay, tvOverlayAction)

        // Step 3: Accessibility
        val hasAccessibility = isAccessibilityServiceEnabled(this, SafeLockAccessibilityService::class.java)
        setStepStatus(imgAccessibilityStatus, cardAccessibility, hasAccessibility, tvAccessibilityAction)

        // Step 4: Device Admin
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val hasAdmin = dpm.isAdminActive(ComponentName(this, KiddoDeviceAdminReceiver::class.java))
        setStepStatus(imgDeviceAdminStatus, cardDeviceAdmin, hasAdmin, tvAdminAction)

        // Step 5: Usage Access
        val hasUsage = isUsageAccessEnabled()
        setStepStatus(imgUsageStatus, cardUsageAccess, hasUsage, tvUsageAction)


        // Enable button only when all critical steps done
        btnContinue.isEnabled = hasOverlay && hasAccessibility && hasAdmin && hasUsage
        btnContinue.alpha = if (btnContinue.isEnabled) 1.0f else 0.5f
    }



    private fun setStepStatus(icon: ImageView, card: View, active: Boolean, actionHint: TextView? = null) {
        if (active) {
            icon.setImageResource(R.drawable.ic_status_granted)
            icon.clearColorFilter()
            card.alpha = 0.6f
            card.isEnabled = false
            actionHint?.text = "✓ הופעל"
            actionHint?.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            icon.setImageResource(R.drawable.ic_status_pending)
            icon.clearColorFilter()
            card.alpha = 1.0f
            card.isEnabled = true
            actionHint?.text = "לחץ להפעלה ←"
            actionHint?.setTextColor(Color.parseColor("#00E5FF"))
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showPermissionGuide(guideTitle: String, guideDesc: String, imageRes: Int, onConfirm: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_permission_guide, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.tvGuideTitle).text = guideTitle
        val tvDesc = dialogView.findViewById<TextView>(R.id.tvGuideDescription)
        tvDesc.text = guideDesc
        
        val imgGuide = dialogView.findViewById<ImageView>(R.id.imgGuide)
        imgGuide.setImageResource(imageRes)
        
        val cardGuideImage = dialogView.findViewById<View>(R.id.cardGuideImage)

        val confirmAction = {
            dialog.dismiss()
            onConfirm()
        }

        // Multi-link interactivity: Clicking button, text, or image triggers settings
        dialogView.findViewById<Button>(R.id.btnGoToSettings).setOnClickListener { confirmAction() }
        tvDesc.setOnClickListener { confirmAction() }
        imgGuide.setOnClickListener { confirmAction() }
        cardGuideImage.setOnClickListener { confirmAction() }

        dialog.show()
    }

    private fun setSetupInProgress(inProgress: Boolean) {
        getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("setup_in_progress", inProgress)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        setSetupInProgress(false)
    }
}
