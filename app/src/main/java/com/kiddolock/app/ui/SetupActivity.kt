package com.kiddolock.app.ui
import com.kiddolock.app.R
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.SettingsSyncManager
import com.kiddolock.app.utils.PermissionUtils

import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.kiddolock.app.MainActivity
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View

/**
 * Modern Setup Wizard for SafeLock.
 * Guides parents through enabling essential protection permissions.
 * 6 steps: Notifications → Overlay → Accessibility → Device Admin → Usage Access → Recovery Email
 */
class SetupActivity : AppCompatActivity() {

    companion object { private const val TAG = "SAFELOCK_FLOW" }

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
        Log.i(TAG, "SetupActivity.onCreate: start")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Mark setup as in progress so BypassGuard allows settings access
        setSetupInProgress(true)

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

    // Set when the parent just returned from Accessibility Settings. If
    // the permission is still missing afterwards (typical outcome on
    // Android 13+ for sideloaded apps) we proactively surface the
    // restricted-settings remedy instead of waiting for them to re-open
    // the guide.
    private var triedAccessibilityThisSession = false

    override fun onResume() {
        Log.i(TAG, "SetupActivity.onResume")
        super.onResume()
        updateStatus()
        maybeOfferRestrictedSettingsHelp()
    }

    /**
     * Android 13+ specific: if the parent just left Accessibility Settings
     * and the SafeLock service is still disabled, the most likely cause is
     * that the OS greyed out the entry under "restricted settings"
     * protection. Surface a one-tap prompt to the Apps list + ⋮ flow.
     *
     * Real-world device testing revealed a quirk: the ⋮ "Allow restricted
     * settings" menu item only appears AFTER the parent has attempted to
     * toggle the greyed SafeLock entry (which triggers the system
     * "blocked for safety" dialog). So we only trigger this once, and
     * phrase the toast/banner accordingly.
     */
    private fun maybeOfferRestrictedSettingsHelp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!triedAccessibilityThisSession) return
        if (PermissionUtils.hasAccessibilityService(this)) return

        triedAccessibilityThisSession = false // one-shot per return trip
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
            .setTitle("SafeLock הופיע אפור?")
            .setMessage(
                "Android 13+ חוסם שירותי נגישות לאפליקציות שהותקנו מחוץ ל-Google Play.\n\n" +
                "אם לחצת על SafeLock האפור וראית הודעה על חסימת בטיחות — מצוין, זה השלב הראשון.\n\n" +
                "עכשיו נפתח את רשימת היישומים כדי לאשר הגדרות מוגבלות: SafeLock → ⋮ → \"אפשר הגדרות מוגבלות\"."
            )
            .setPositiveButton("פתח רשימת יישומים") { _, _ -> openAppsListForRestrictedSettings() }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun setupListeners() {
        cardNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showPermissionGuide(
                    getString(R.string.guide_title_notifications),
                    getString(R.string.guide_desc_notifications),
                    R.drawable.guide_notifications
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
            // On Android 13+ the OS sometimes greys out the SafeLock toggle in
            // Accessibility Settings ("restricted settings" protection for
            // sideloaded apps). The remedy lives behind the ⋮ menu inside
            // App Info — expose a one-tap shortcut to that exact screen so
            // the parent doesn't have to hunt for it.
            val showAppInfoShortcut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            showPermissionGuide(
                getString(R.string.guide_title_accessibility),
                getString(R.string.guide_desc_accessibility),
                R.drawable.guide_accessibility,
                secondaryAction = if (showAppInfoShortcut) {
                    { openAppsListForRestrictedSettings() }
                } else null,
            ) {
                // Mark that we're leaving for Accessibility — if SafeLock is
                // still not enabled when we come back, onResume() will
                // proactively offer the restricted-settings remedy.
                triedAccessibilityThisSession = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "מצא את SafeLock ברשימה והפעל אותו", Toast.LENGTH_LONG).show()
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
                R.drawable.guide_usage_access
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "מצא את SafeLock ברשימה והפעל גישה", Toast.LENGTH_LONG).show()
            }
        }

        btnContinue.setOnClickListener {
            Log.i(TAG, "SetupActivity.btnContinue clicked")
            val email = etRecoveryEmail.text.toString().trim()
            if (email.isNotEmpty() && !isValidEmail(email)) {
                tilRecoveryEmail.error = "כתובת אימייל לא תקינה"
                return@setOnClickListener
            }
            tilRecoveryEmail.error = null

            // Defensive: re-check perms at click time to avoid stale UI state
            if (!PermissionUtils.isSetupComplete(this)) {
                Log.w(TAG, "SetupActivity.btnContinue: perms incomplete at click time")
                Toast.makeText(this, "עדיין חסרה הרשאה — בדוק שכל השלבים ירוקים", Toast.LENGTH_LONG).show()
                updateStatus()
                return@setOnClickListener
            }

            if (email.isNotEmpty()) {
                AdminPinManager.setRecoveryEmail(this, email)
            }
            try {
                SettingsSyncManager.syncNow(this)
            } catch (e: Throwable) {
                Log.e(TAG, "SettingsSyncManager.syncNow failed (non-fatal)", e)
            }
            setSetupInProgress(false)
            Log.i(TAG, "SetupActivity.btnContinue: launching MainActivity")
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun updateStatus() {
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        setStepStatus(imgNotificationStatus, cardNotifications, hasNotifications, tvNotifAction)

        val hasOverlay = PermissionUtils.hasOverlay(this)
        setStepStatus(imgOverlayStatus, cardOverlay, hasOverlay, tvOverlayAction)

        val hasAccessibility = PermissionUtils.hasAccessibilityService(this)
        setStepStatus(imgAccessibilityStatus, cardAccessibility, hasAccessibility, tvAccessibilityAction)

        val hasAdmin = PermissionUtils.hasDeviceAdmin(this)
        setStepStatus(imgDeviceAdminStatus, cardDeviceAdmin, hasAdmin, tvAdminAction)

        val hasUsage = PermissionUtils.hasUsageAccess(this)
        setStepStatus(imgUsageStatus, cardUsageAccess, hasUsage, tvUsageAction)

        Log.i(TAG, "SetupActivity.updateStatus: overlay=$hasOverlay a11y=$hasAccessibility admin=$hasAdmin usage=$hasUsage notif=$hasNotifications")

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

    private fun showPermissionGuide(
        guideTitle: String,
        guideDesc: String,
        imageRes: Int,
        secondaryAction: (() -> Unit)? = null,
        onConfirm: () -> Unit,
    ) {
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

        dialogView.findViewById<Button>(R.id.btnGoToSettings).setOnClickListener { confirmAction() }
        tvDesc.setOnClickListener { confirmAction() }
        imgGuide.setOnClickListener { confirmAction() }
        cardGuideImage.setOnClickListener { confirmAction() }

        // Optional secondary action (e.g. "SafeLock is greyed out? Open App
        // Info") — only wired when caller opts in.
        val btnAppInfo = dialogView.findViewById<Button>(R.id.btnOpenAppInfo)
        if (secondaryAction != null) {
            btnAppInfo.visibility = View.VISIBLE
            btnAppInfo.setOnClickListener {
                dialog.dismiss()
                secondaryAction()
            }
        }

        dialog.show()
    }

    /**
     * Opens the system "Apps" list — the screen where the parent taps
     * SafeLock and then reaches the ⋮ overflow that hosts the "Allow
     * restricted settings" toggle on Android 13+.
     *
     * Important: we deliberately use ACTION_MANAGE_APPLICATIONS_SETTINGS
     * (apps list) instead of ACTION_APPLICATION_DETAILS_SETTINGS. On many
     * devices the ⋮ "Allow restricted settings" item is *only* reachable
     * when the user navigated through Settings → Apps → [app], not when
     * App Info was opened directly via a package URI intent.
     *
     * We fall back to APPLICATION_SETTINGS and then to generic SETTINGS
     * so the parent is never left stranded on OEM skins that renamed or
     * removed the primary screen.
     */
    private fun openAppsListForRestrictedSettings() {
        val fallbackIntents = listOf(
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in fallbackIntents) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(intent)
                Toast.makeText(
                    this,
                    getString(R.string.guide_app_info_toast),
                    Toast.LENGTH_LONG,
                ).show()
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // try next fallback
            }
        }
        Log.e(TAG, "No Settings app exposed any of the expected app-list intents")
        Toast.makeText(
            this,
            "לא הצלחנו לפתוח את הגדרות המכשיר — פתח ידנית: הגדרות → יישומים → SafeLock → ⋮",
            Toast.LENGTH_LONG,
        ).show()
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
