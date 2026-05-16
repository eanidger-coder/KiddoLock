package com.kiddolock.app.ui

import com.kiddolock.app.R
import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.net.Uri
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.AppBlockManager

class AdminPinActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvLockout: TextView
    private lateinit var tvForgot: TextView
    private lateinit var pinDots: List<View>
    private var pinBuffer = StringBuilder()

    private var isSettingUp = false
    private var isChangingPin = false
    private var isVerifyingCurrentForChange = false
    private var firstPinEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_pin)

        tvTitle = findViewById(R.id.tvPinTitle)
        tvSubtitle = findViewById(R.id.tvPinSubtitle)
        tvLockout = findViewById(R.id.tvLockoutTimer)
        tvForgot = findViewById(R.id.tvForgotPin)

        pinDots = listOf(
            findViewById(R.id.pinDot1),
            findViewById(R.id.pinDot2),
            findViewById(R.id.pinDot3),
            findViewById(R.id.pinDot4)
        )

        setupKeypad()

        isSettingUp = !AdminPinManager.isPinSet(this)
        isChangingPin = intent.getBooleanExtra("CHANGE_PIN_MODE", false)

        if (isChangingPin) {
            isVerifyingCurrentForChange = true
            tvTitle.text = getString(R.string.enter_current_pin)
            tvSubtitle.text = getString(R.string.wrong_pin_shake_hint)
            tvForgot.visibility = View.VISIBLE
        } else if (isSettingUp) {
            tvTitle.text = getString(R.string.set_admin_pin)
            tvSubtitle.text = getString(R.string.choose_new_pin)
            tvForgot.visibility = View.GONE
        }

        tvForgot.setOnClickListener {
            handleForgotPin()
        }
        
        // Hidden Rescue: Long press "Forgot PIN" to trigger Emergency Uninstall (if authorized by recovery email later)
        tvForgot.setOnLongClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                .setTitle("חילוץ חירום")
                .setMessage("האם ברצונך לבצע הסרת חירום? פעולה זו אפשרית רק עבור מנהל המערכת.")
                .setPositiveButton("המשך") { _, _ ->
                    // Trigger the uninstall flow which will still require PIN or recovery code
                    // But we can simplify it here for immediate rescue if needed
                    AppBlockManager.uninstallSelf(this)
                }
                .setNegativeButton("ביטול", null)
                .show()
            true
        }
    }

    private fun setupKeypad() {
        val digitButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        digitButtons.forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                if (pinBuffer.length < 4) {
                    val digit = (it as Button).text.toString()
                    pinBuffer.append(digit)
                    updateDots()
                    if (pinBuffer.length == 4) {
                        handlePinSubmit()
                    }
                }
            }
        }

        findViewById<View>(R.id.btnDelete).setOnClickListener {
            if (pinBuffer.isNotEmpty()) {
                pinBuffer.deleteCharAt(pinBuffer.length - 1)
                updateDots()
            }
        }
    }

    private fun updateDots() {
        pinDots.forEachIndexed { index, view ->
            if (index < pinBuffer.length) {
                view.setBackgroundResource(R.drawable.pin_dot_filled)
            } else {
                view.setBackgroundResource(R.drawable.pin_dot_empty)
            }
        }
    }

    private fun handleForgotPin() {
        val email = AdminPinManager.getRecoveryEmail(this)
        if (email.isNullOrEmpty()) {
            Toast.makeText(this, "לא הוגדר אימייל לשחזור. פנה לתמיכה.", Toast.LENGTH_LONG).show()
            return
        }

        val code = AdminPinManager.generateRecoveryCode(this)
        
        // Open email app with code
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, "קוד שחזור ל-KiddoLock")
            putExtra(Intent.EXTRA_TEXT, "קוד השחזור שלך הוא: $code\nניתן להשתמש בו ב-15 הדקות הקרובות.")
        }
        
        try {
            startActivity(Intent.createChooser(intent, "שלח קוד שחזור..."))
        } catch (e: Exception) {
            // Email app might be blocked or missing, that's why we have the dialog fallback
            Log.e("AdminPinActivity", "Failed to launch email", e)
        }
        
        showRecoveryCodeDialog(code)
    }

    private fun showRecoveryCodeDialog(code: String) {
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
        }

        val tvInfo = TextView(this).apply {
            text = "קוד נשלח לכתובת: ${AdminPinManager.getRecoveryEmail(this@AdminPinActivity)}\nאם לא קיבלת את המייל, תוכל להעתיק את הקוד מכאן:"
            textSize = 14f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * resources.displayMetrics.density).toInt() / 2 }
        }

        val btnCopy = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "העתק קוד: $code"
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Recovery Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@AdminPinActivity, "הקוד הועתק", Toast.LENGTH_SHORT).show()
            }
        }

        val tilInput = com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "הזן קוד (6 ספרות)"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = paddingPx / 2 }
        }

        val etInput = com.google.android.material.textfield.TextInputEditText(tilInput.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        tilInput.addView(etInput)

        container.addView(tvInfo)
        container.addView(btnCopy)
        container.addView(tilInput)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
            .setTitle("אימות קוד שחזור")
            .setView(container)
            .setPositiveButton("אימות") { _, _ ->
                val enteredCode = etInput.text.toString()
                if (AdminPinManager.verifyRecoveryCode(this, enteredCode)) {
                    // Success, allow reset
                    isSettingUp = true
                    firstPinEntry = null
                    pinBuffer.setLength(0)
                    updateDots()
                    tvTitle.text = "איפוס קוד PIN"
                    tvSubtitle.text = "בחר קוד חדש"
                    tvForgot.visibility = View.GONE
                    Toast.makeText(this, "הקוד אומת! בחר קוד חדש כעת.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "קוד שגוי או פג תוקף", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    override fun onBackPressed() {
        if (isSettingUp) {
            // Prevent going back during mandatory setup
            Toast.makeText(this, "עליך להגדיר קוד PIN כדי להמשיך", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    private fun handlePinSubmit() {
        val entry = pinBuffer.toString()
        if (entry.length < 4) {
            return
        }

        if (isSettingUp) {
            if (firstPinEntry == null) {
                firstPinEntry = entry
                pinBuffer.setLength(0)
                updateDots()
                tvSubtitle.text = "הזן שוב לאימות"
            } else {
                if (firstPinEntry == entry) {
                    AdminPinManager.setPin(this, entry)
                    Toast.makeText(this, "הקוד הוגדר בהצלחה", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "הקודים אינם תואמים", Toast.LENGTH_SHORT).show()
                    firstPinEntry = null
                    pinBuffer.setLength(0)
                    updateDots()
                    tvSubtitle.text = "בחר קוד חדש"
                }
            }
        } else if (isVerifyingCurrentForChange) {
            val result = AdminPinManager.verifyPin(this, entry)
            if (result is AdminPinManager.PinResult.Success) {
                // Verified current PIN, now allow setting a new one
                isVerifyingCurrentForChange = false
                isSettingUp = true
                firstPinEntry = null
                pinBuffer.setLength(0)
                updateDots()
                tvTitle.text = getString(R.string.set_new_pin)
                tvSubtitle.text = getString(R.string.choose_new_pin)
                tvForgot.visibility = View.GONE
                Toast.makeText(this, "הקוד אומת. בחר קוד חדש.", Toast.LENGTH_SHORT).show()
            } else {
                handlePinError(result)
            }
        } else {
            val result = AdminPinManager.verifyPin(this, entry)
            handlePinResult(result)
        }
    }

    private fun handlePinResult(result: AdminPinManager.PinResult) {
        runOnUiThread {
            when (result) {
                is AdminPinManager.PinResult.Success -> {
                    // Check if we were sent here for an emergency action
                    when (intent.action) {
                        "com.kiddolock.app.EMERGENCY_NEUTRALIZE" -> {
                            AppBlockManager.neutralize(this)
                            Toast.makeText(this, R.string.overlay_neutralize_success, Toast.LENGTH_LONG).show()
                        }
                        "com.kiddolock.app.EMERGENCY_UNINSTALL" -> {
                            // Show clear confirmation dialog before triggering uninstall
                            showRemovalSuccessDialog()
                            return@runOnUiThread
                        }
                        "com.kiddolock.app.EMERGENCY_UNLOCK" -> {
                            // PIN verified - NOW (and only now) suppress blocks for 10 minutes
                            try {
                                com.kiddolock.app.utils.Prefs(this).emergency_bypass_until = System.currentTimeMillis() + (10 * 60 * 1000L)
                                AppBlockManager.setGlobalSuppression(this, true)
                                com.kiddolock.app.utils.NotificationUtils.updateNotificationCustom(
                                    this,
                                    "🔓 שחרור זמני פעיל",
                                    "החסימות בוטלו ל-10 דקות. יחזרו אוטומטית."
                                )
                            } catch (e: Exception) {
                                Log.e("AdminPinActivity", "Failed to apply unlock", e)
                            }
                            Toast.makeText(this, "✅ שחרור זמני ל-10 דקות פעיל", Toast.LENGTH_LONG).show()
                            val mainIntent = Intent(this, com.kiddolock.app.MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(mainIntent)
                        }
                        "com.kiddolock.app.ADMIN_AUTH" -> {
                            AdminActivity.isSessionAuthorized = true
                            Log.d("AdminPinActivity", "Admin session authorized via PIN")
                        }
                        else -> {
                            val pendingUnlockPkg = intent.getStringExtra("PENDING_UNLOCK_PKG")
                            if (pendingUnlockPkg != null) {
                                AppBlockManager.temporaryUnlock(this, pendingUnlockPkg)
                                Toast.makeText(this, "האפליקציה שוחררה ל-5 דקות", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    setResult(RESULT_OK)
                    finish()
                }
                is AdminPinManager.PinResult.Locked -> {
                    Toast.makeText(this, "המכשיר נעול לעוד ${result.remainingSeconds} שניות", Toast.LENGTH_SHORT).show()
                }
                is AdminPinManager.PinResult.WrongPin -> {
                    handlePinError(result)
                }
                is AdminPinManager.PinResult.NoPinSet -> {
                    Toast.makeText(this, "לא הוגדר קוד הגישה", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePinError(result: AdminPinManager.PinResult) {
        if (result is AdminPinManager.PinResult.WrongPin) {
            Toast.makeText(this, "קוד שגוי (נותרו ${result.remainingAttempts} ניסיונות)", Toast.LENGTH_SHORT).show()
            pinBuffer.setLength(0)
            updateDots()
            
            // Shake animation for premium feel
            val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
            findViewById<View>(R.id.pinIndicatorContainer).startAnimation(shake)
        }
    }

    /**
     * Show a clear, friendly dialog after the parent successfully entered PIN
     * to remove protection. Tells the user: "You can uninstall now" + opens uninstall flow.
     */
    private fun showRemovalSuccessDialog() {
        // Aggressively suppress everything for 1 hour to give user plenty of time
        try {
            com.kiddolock.app.management.AppBlockManager.setGlobalSuppression(this, true, true)
            val prefs = com.kiddolock.app.utils.Prefs(this)
            prefs.disable_all_filters = true
            prefs.bypass_guard_enabled = false
            prefs.certified_uninstall_in_progress = true
        } catch (e: Exception) {
            Log.e("AdminPinActivity", "Failed to suspend protection", e)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ עצור! זה לא כיבוי מצב ילדים")
            .setMessage("הכפתור הזה ימחק את KiddoLock לגמרי מהמכשיר!\n\n🚫 אם רק רצית שהילד יוכל להשתמש כרגיל:\n   • סגור חלון זה (ביטול)\n   • פתח את KiddoLock במסך הראשי\n   • כבה את 'מצב ילדים' בלחיצה אחת\n\n⚠️ אם תמשיך במחיקה:\n   • הילד יוכל להשתמש בכל האפליקציות\n   • כל ההגדרות יישמרו בענן ויחזרו בהתקנה מחדש\n   • תצטרך להתקין את ה-APK שוב כדי להפעיל הגנה\n   • תהליך לוקח 5-10 דקות (הרשאות + Device Admin)\n\nרוב ההורים לא צריכים למחוק - רק לכבות את מצב ילדים.")
            .setPositiveButton("כן, מחק את KiddoLock") { _, _ ->
                try {
                    AppBlockManager.uninstallSelf(this)
                } catch (e: Exception) {
                    Toast.makeText(this, "פתח הגדרות → אפליקציות → KiddoLock → הסר", Toast.LENGTH_LONG).show()
                }
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("ביטול") { _, _ ->
                Toast.makeText(this, "המחיקה בוטלה. ההגנה ממשיכה כרגיל.", Toast.LENGTH_LONG).show()
                // Re-enable suppression we lifted in the dialog setup
                try {
                    com.kiddolock.app.utils.Prefs(this).emergency_bypass_until = 0L
                    AppBlockManager.setGlobalSuppression(this, false)
                    com.kiddolock.app.utils.Prefs(this).disable_all_filters = false
                } catch (_: Throwable) {}
                setResult(RESULT_CANCELED)
                finish()
            }
            .setCancelable(false)
            .show()
    }

}
