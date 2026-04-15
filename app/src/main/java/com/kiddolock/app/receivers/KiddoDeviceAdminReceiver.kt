package com.kiddolock.app.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.kiddolock.app.utils.NotificationUtils

class KiddoDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("KiddoDeviceAdmin", "Device Admin Enabled")
        Toast.makeText(context, "SafeLock Protected", Toast.LENGTH_SHORT).show()
        
        // Enforce policies immediately
        val componentName = ComponentName(context, KiddoDeviceAdminReceiver::class.java)
        PolicyManager.enforcePolicies(context, componentName)
    }

    /**
     * Called when the user attempts to disable Device Admin.
     * Returns a warning message that Android shows BEFORE allowing disable.
     * This is the first line of defense — makes the child hesitate.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val kidsModeEnabled = com.kiddolock.app.management.KidsModeManager(context).isEnabled
        
        if (!kidsModeEnabled) {
            Log.i("KiddoDeviceAdmin", "Kids Mode is OFF. Allowing deactivation.")
            return null
        }

        Log.w("KiddoDeviceAdmin", "ALERT: Someone is trying to disable Device Admin while Kids Mode is ON!")
        
        // --- SECURITY TRAP ---
        // Launch the PIN entry screen immediately. In a 2026 app, we use a high-priority 
        // activity to cover the deactivation dialog.
        val pinIntent = Intent(context, com.kiddolock.app.ui.AdminPinActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("FORCE_LOCK", true)
        }
        context.startActivity(pinIntent)

        // Fire a high-priority notification so the parent knows
        com.kiddolock.app.utils.NotificationUtils.showSetupIncompleteNotification(
            context,
            "ניסיון השבתת הגנה זוהה! המכשיר ננעל."
        )
        
        // This message is shown by Android in a confirmation dialog
        return "אזהרה קריטית: הגנת SafeLock מופעלת. השבתת מנהל המכשיר תבטל את כל החסימות ותאפשר לילד להסיר את האפליקציה. \n\nאנא הזן את קוד ה-PIN במסך שזה עתה נפתח כדי להמשיך."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("KiddoDeviceAdmin", "Device Admin Disabled")
        Toast.makeText(context, "אזהרה: הגנת SafeLock הושבתה", Toast.LENGTH_LONG).show()
        
        // Fire urgent notification
        NotificationUtils.showSetupIncompleteNotification(
            context,
            "הגנת מנהל מכשיר הושבתה! לחץ כאן לתיקון."
        )
    }
}
