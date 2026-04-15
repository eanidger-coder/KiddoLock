package com.kiddolock.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kiddolock.app.MainActivity
import com.kiddolock.app.management.AppBlockManager
import com.kiddolock.app.ui.AdminPinActivity
import com.kiddolock.app.utils.NotificationUtils
import android.widget.Toast
import android.util.Log
import com.kiddolock.app.utils.Prefs

class EmergencyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("EmergencyReceiver", "Received action: $action")

        when (action) {
            NotificationUtils.ACTION_EMERGENCY_UNINSTALL -> {
                // Launch the PIN gate — AdminPinActivity already handles
                // EMERGENCY_UNINSTALL: on correct PIN it calls
                // AppBlockManager.uninstallSelf(), which removes the device
                // admin and fires ACTION_DELETE.
                val pinIntent = Intent(context, AdminPinActivity::class.java).apply {
                    this.action = NotificationUtils.ACTION_EMERGENCY_UNINSTALL
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(pinIntent)
                Toast.makeText(context, "הזן קוד PIN כדי להסיר", Toast.LENGTH_SHORT).show()
            }
            NotificationUtils.ACTION_EMERGENCY_UNLOCK -> {
                // To make it very clear and working:
                // 1. Temporarily suppress all blocks
                Prefs(context).emergency_bypass_until = System.currentTimeMillis() + (10 * 60 * 1000L)
                AppBlockManager.setGlobalSuppression(context, true)
                
                // 2. Open MainActivity directly
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(mainIntent)
                
                Toast.makeText(context, "Emergency Unlock Initiated. Please open Admin and use PIN 8888 if locked.", Toast.LENGTH_LONG).show()
            }
            "com.kiddolock.app.TEST_TRIGGER_BEDTIME" -> {
                Log.w("EmergencyReceiver", "TEST: Triggering Bedtime via broadcast")
                val scheduler = com.kiddolock.app.services.TimeScheduler(context)
                val config = scheduler.getConfig().copy(
                    quietHoursEnabled = true,
                    quietHoursStart = 0,
                    quietHoursEnd = 23,
                    quietHoursStartMin = 0,
                    quietHoursEndMin = 59
                )
                scheduler.saveConfig(config)
                Toast.makeText(context, "TEST: Bedtime ACTIVE (00:00 - 23:59)", Toast.LENGTH_SHORT).show()
            }
            "com.kiddolock.app.TEST_SET_INSTANT_LOCK" -> {
                val locked = intent.getBooleanExtra("locked", true)
                Log.w("EmergencyReceiver", "TEST: Setting Instant Lock to $locked")
                val scheduler = com.kiddolock.app.services.TimeScheduler(context)
                scheduler.setInstantLock(locked)
                Toast.makeText(context, "TEST: Instant Lock ${if (locked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            }
            "com.kiddolock.app.TEST_DUMP_STATE" -> {
                Log.w("EmergencyReceiver", "TEST: Dumping state")
                AppBlockManager.dumpState(context)
                Toast.makeText(context, "State dumped to Logcat", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
