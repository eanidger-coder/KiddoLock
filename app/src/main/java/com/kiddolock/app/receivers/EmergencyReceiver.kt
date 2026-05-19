package com.kiddolock.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kiddolock.app.MainActivity
import com.kiddolock.app.management.AppBlockManager
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
                // ⚡ INSTANT FEEDBACK only - no bypass before PIN!
                vibrateConfirmation(context)
                Toast.makeText(context, "🔐 נדרש PIN הורה להמשך הסרה", Toast.LENGTH_LONG).show()
                showProgressNotification(context, "🔐 הזן את PIN ההורה כדי להמשיך בהסרה")

                // Open AdminPinActivity. NO suppression / bypass set here - that happens only after PIN success inside AdminPinActivity.
                val pinIntent = Intent(context, com.kiddolock.app.ui.AdminPinActivity::class.java)
                pinIntent.action = "com.kiddolock.app.EMERGENCY_UNINSTALL"
                pinIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY
                pinIntent.putExtra("emergency_uninstall", true)
                context.startActivity(pinIntent)
            }
            NotificationUtils.ACTION_ENABLE_KIDS_MODE -> {
                // CRITICAL FIX (black-screen bug): turn on Kids Mode without opening any Activity.
                // Also covers the "restore from 10-min pause" case - clearing global suppression and
                // any pending bypasses so protection is immediately active when the parent taps the button.
                Log.i("EmergencyReceiver", "Enabling Kids Mode from notification tap")
                try {
                    vibrateConfirmation(context)
                    val kidsManager = com.kiddolock.app.management.KidsModeManager(context)
                    val wasSuppressed = AppBlockManager.isGlobalSuppressed
                    if (!kidsManager.isEnabled) {
                        kidsManager.isEnabled = true
                    }
                    // Always clear any active bypasses / pause, so a tap during the 10-minute pause restores protection.
                    AppBlockManager.clearAllBypasses(context)
                    // Refresh the notification immediately so the parent sees the green "active" state
                    NotificationUtils.updateNotification(context, true)
                    val msg = if (wasSuppressed) "🛡️ ההגנה חזרה לפעולה" else "🛡️ ההגנה הופעלה"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Log.e("EmergencyReceiver", "Failed to enable Kids Mode from notification", e)
                    Toast.makeText(context, "שגיאה בהפעלת ההגנה. פתח את KiddoLock ידנית.", Toast.LENGTH_LONG).show()
                }
            }
            NotificationUtils.ACTION_EMERGENCY_UNLOCK -> {
                // ⚡ INSTANT FEEDBACK only - no bypass before PIN!
                vibrateConfirmation(context)
                Toast.makeText(context, "🔐 נדרש PIN הורה לשחרור זמני", Toast.LENGTH_LONG).show()
                showProgressNotification(context, "🔐 הזן PIN לשחרור זמני של 10 דקות")

                // Open AdminPinActivity. NO suppression / bypass set here - that happens only after PIN success inside AdminPinActivity.
                val pinIntent = Intent(context, com.kiddolock.app.ui.AdminPinActivity::class.java)
                pinIntent.action = "com.kiddolock.app.EMERGENCY_UNLOCK"
                pinIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY
                pinIntent.putExtra("emergency_unlock", true)
                context.startActivity(pinIntent)
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

    /** Quick haptic confirmation that the tap was registered */
    private fun vibrateConfirmation(context: Context) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(150)
            }
        } catch (e: Exception) {
            Log.w("EmergencyReceiver", "Vibration failed: ${e.message}")
        }
    }

    /** Update the foreground notification with a clear progress/confirmation text */
    private fun showProgressNotification(context: Context, text: String) {
        try {
            NotificationUtils.updateNotificationCustom(context, "🚨 KiddoLock - חירום פעיל", text)
        } catch (e: Exception) {
            Log.w("EmergencyReceiver", "Could not update notification: ${e.message}")
        }
    }

}
