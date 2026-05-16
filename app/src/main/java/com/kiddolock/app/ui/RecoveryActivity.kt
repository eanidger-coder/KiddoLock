package com.kiddolock.app.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.receivers.KiddoDeviceAdminReceiver
import com.kiddolock.app.utils.Prefs

/**
 * RecoveryActivity - the failsafe escape hatch.
 *
 * Why it exists: if the main MainActivity ever gets stuck (black screen, crash loop),
 * the kid - or panicked parent - must always have a working path out. This activity:
 *   1. Runs in a separate process (android:process=":recovery" in the manifest)
 *   2. Has its own launcher icon visible alongside the main KiddoLock icon
 *   3. Never depends on the main process being healthy
 *   4. Disables Kids Mode, clears global suppression, removes Device Admin policies,
 *      and opens the Android uninstall dialog with one tap
 *
 * The kid can find this and click it to unblock everything - that's INTENTIONAL.
 * Parental control isn't about trapping the kid; it's about gentle guidance, and the
 * worst-case scenario must always be recoverable.
 */
class RecoveryActivity : AppCompatActivity() {

    private val tag = "RecoveryActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 96, 64, 96)
            setBackgroundColor(0xFF0D0B1A.toInt())
        }

        val title = TextView(this).apply {
            text = "KiddoLock - שחזור חירום"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val desc = TextView(this).apply {
            text = "מסך זה מבטל את כל ההגנות באופן מיידי. השתמש בו רק אם נתקעת או לא יכול להסיר את האפליקציה רגיל. הגנה תכבה - לא נדרש PIN."
            setTextColor(0xFFB8B5C8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnUnblock = Button(this).apply {
            text = "כבה הכל עכשיו ופתח הגדרות הסרה"
            textSize = 16f
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
            setOnClickListener { performFullRecovery() }
        }

        val btnDisableProtectionOnly = Button(this).apply {
            text = "רק כבה מצב ילדים (שמור התקנה)"
            textSize = 14f
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener {
                disableAllProtection()
                Toast("מצב ילדים כובה. ההתקנה נשמרה.")
                finish()
            }
        }

        val btnCancel = Button(this).apply {
            text = "ביטול - חזרה למצב רגיל"
            textSize = 13f
            setOnClickListener { finish() }
        }

        root.addView(title)
        root.addView(desc)
        root.addView(btnUnblock)
        root.addView(btnDisableProtectionOnly)
        root.addView(btnCancel)
        setContentView(root)
    }

    private fun Toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {}
    }

    /**
     * Full recovery: turn off everything, remove device admin policies, open uninstall dialog.
     */
    private fun performFullRecovery() {
        Log.w(tag, "Full recovery initiated by user")
        disableAllProtection()
        removeDeviceAdminPolicies()
        openUninstallDialog()
    }

    private fun disableAllProtection() {
        try { KidsModeManager(this).isEnabled = false } catch (e: Throwable) { Log.e(tag, "Could not disable Kids Mode", e) }
        try { AppBlockManager.clearGlobalSuppression(this) } catch (e: Throwable) { Log.e(tag, "Could not clear suppression", e) }
        try {
            val p = Prefs(this)
            p.disable_all_filters = true
            p.bypass_guard_enabled = false
            p.uninstall_protection_enabled = false
            p.certified_uninstall_in_progress = true
            p.emergency_bypass_until = 0L
        } catch (e: Throwable) { Log.e(tag, "Could not clear prefs", e) }
    }

    private fun removeDeviceAdminPolicies() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, KiddoDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin)
                Log.i(tag, "Device admin removed")
            }
        } catch (e: Throwable) {
            Log.e(tag, "Could not remove device admin", e)
        }
    }

    private fun openUninstallDialog() {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Throwable) {
            Log.e(tag, "Could not open uninstall dialog", e)
            Toast("פתח את הגדרות אנדרואיד -> אפליקציות -> KiddoLock -> הסר")
        }
    }
}
