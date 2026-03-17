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

        if (isSettingUp) {
            tvTitle.text = getString(R.string.set_admin_pin)
            tvSubtitle.text = getString(R.string.choose_new_pin)
            tvForgot.visibility = View.GONE
        }

        tvForgot.setOnClickListener {
            handleForgotPin()
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

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
        } else {
            val result = AdminPinManager.verifyPin(this, entry)
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
                                AppBlockManager.uninstallSelf(this)
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
                        Toast.makeText(this, "קוד שגוי (נותרו ${result.remainingAttempts} ניסיונות)", Toast.LENGTH_SHORT).show()
                        pinBuffer.setLength(0)
                        updateDots()
                        
                        // Shake animation for premium feel
                        val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
                        findViewById<View>(R.id.pinIndicatorContainer).startAnimation(shake)
                    }
                    is AdminPinManager.PinResult.NoPinSet -> {
                        Toast.makeText(this, "לא הוגדר קוד הגישה", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
