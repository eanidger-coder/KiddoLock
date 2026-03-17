package com.kiddolock.app.ui

import android.app.*
import android.content.*
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import com.kiddolock.app.R
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.AppBlockManager
import android.util.Log
import android.view.animation.AnimationUtils
import android.provider.Settings
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.VibratorManager

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var warningView: View? = null
    private var currentPackage: String? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            val pkg = intent?.getStringExtra("package_name") ?: currentPackage
            
            Log.d("OverlayService", "onStartCommand: action=$action, package=$pkg")
            
            when (action) {
                "SHOW_OVERLAY" -> {
                    if (pkg == packageName) return START_NOT_STICKY
                    if (overlayView != null && pkg == currentPackage) return START_STICKY
                    currentPackage = pkg
                    showOverlay()
                }
                "HIDE_OVERLAY" -> hide()
                "SHOW_WARNING" -> showWarning()
                "HIDE_WARNING" -> hideWarning()
                else -> {
                    if (action != null) Log.w("OverlayService", "Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Error in onStartCommand", e)
        }
        return START_STICKY
    }

    private var pinBuffer = ""
    private val MAX_PIN_LENGTH = 4

    private fun showOverlay() {
        Log.i("OverlayService", "showOverlay() called for package: $currentPackage")
        if (overlayView != null) {
            Log.d("OverlayService", "Overlay already visible, skipping inflate")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Allow us to catch touches but not block system
            PixelFormat.TRANSLUCENT
        )

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_block, null)
        
        setupPinPad()
        setupEmergencyButtons()
        showAdaptiveNudge()

        val pinIndicatorContainer = overlayView?.findViewById<View>(R.id.pinIndicatorContainer)
        val pinKeypad = overlayView?.findViewById<View>(R.id.pinKeypad)
        val llDurationSelector = overlayView?.findViewById<View>(R.id.llDurationSelector)
        val tvParentUnlock = overlayView?.findViewById<View>(R.id.tvParentUnlock)

        tvParentUnlock?.setOnClickListener {
            pinIndicatorContainer?.visibility = View.VISIBLE
            pinKeypad?.visibility = View.VISIBLE
            llDurationSelector?.visibility = View.VISIBLE
            tvParentUnlock.visibility = View.GONE
            
            // Subtle animation for reveal
            val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            pinIndicatorContainer?.startAnimation(fadeIn)
            pinKeypad?.startAnimation(fadeIn)
            llDurationSelector?.startAnimation(fadeIn)
        }

        overlayView?.findViewById<View>(R.id.tvGoBack)?.setOnClickListener {
            goHome()
            hide()
        }

        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w("OverlayService", "CRITICAL: Cannot draw overlays — permission not granted. Requesting user to fix.")
                return
            }
            windowManager?.addView(overlayView, params)
            Log.i("OverlayService", "Successfully added overlay view for $currentPackage. Reason: ${getBlockingReason()}")
        } catch (e: Exception) {
            Log.e("OverlayService", "CRITICAL ERROR adding overlay view", e)
            overlayView = null
        }
    }

    private fun getBlockingReason(): String {
        val appManager = com.kiddolock.app.management.AppManager(this)
        val timeScheduler = com.kiddolock.app.services.TimeScheduler(this)
        
        return when {
            currentPackage == null -> "Generic Lock"
            appManager.isBlacklisted(currentPackage!!) -> "Blacklisted App"
            timeScheduler.isCurrentlyRestricted() -> timeScheduler.getRestrictionReason() ?: "Time Restriction"
            else -> "Unknown/Suppressed"
        }
    }


    private fun setupEmergencyButtons() {
        // Emergency/Parent approval buttons were removed in the premium redesign
        // to simplify the UI and focus on the PIN entry.
    }

    private fun showAdaptiveNudge() {
        val nudgeTv = overlayView?.findViewById<TextView>(R.id.tvNudgeMessage) ?: return
        
        val nudges = when {
            isLateNight() -> listOf(
                "✨ אולי כדאי לישון? מחר יהיה יום נהדר!",
                "🌙 כמעט חלום... זמן להוריד הילוך.",
                "😴 העיניים צריכות מנוחה, נתראה מחר!"
            )
            isStudyTime() -> listOf(
                "📚 זמן מצוין לסיים שיעורים ולהיות גאים!",
                "🧠 הפסקה קצרה מהמסך תעזור לך להתרכז.",
                "🎨 מה דעתך לצייר משהו יפה במקום?"
            )
            else -> listOf(
                "✨ הגיע הזמן לקצת קריאה או יצירה?",
                "⚽ אולי נצא קצת לנשום אוויר בחוץ?",
                "🎲 מה עם משחק קופסה משפחתי?"
            )
        }
        
        nudgeTv.text = nudges.random()
        
        // Simple entry animation
        nudgeTv.alpha = 0f
        nudgeTv.animate().alpha(1f).setDuration(1000).start()
    }

    private fun isLateNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 21 || hour < 6
    }

    private fun isStudyTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 14..17 // 2 PM to 5 PM
    }

    private var selectedDurationMinutes = 1

    private fun setupPinPad() {
        val buttons = listOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9"
        )

        buttons.forEach { (id, value) ->
            overlayView?.findViewById<Button>(id)?.setOnClickListener {
                onDigitPressed(value)
            }
        }

        overlayView?.findViewById<View>(R.id.btnDelete)?.setOnClickListener {
            if (pinBuffer.isNotEmpty()) {
                pinBuffer = pinBuffer.substring(0, pinBuffer.length - 1)
                updatePinIndicators()
            }
        }

        // Duration selector logic
        val durButtons = listOf(
            overlayView?.findViewById<TextView>(R.id.btnDur1) to 1,
            overlayView?.findViewById<TextView>(R.id.btnDur2) to 2,
            overlayView?.findViewById<TextView>(R.id.btnDur5) to 5,
            overlayView?.findViewById<TextView>(R.id.btnDur10) to 10
        )

        durButtons.forEach { (btn, dur) ->
            btn?.setOnClickListener {
                selectedDurationMinutes = dur
                durButtons.forEach { (b, _) -> b?.isSelected = false }
                btn.isSelected = true
            }
        }
    }

    private fun onDigitPressed(digit: String) {
        if (pinBuffer.length < MAX_PIN_LENGTH) {
            pinBuffer += digit
            updatePinIndicators()
            
            if (pinBuffer.length == MAX_PIN_LENGTH) {
                verifyPin()
            }
        }
    }

    private fun updatePinIndicators() {
        val dots = listOf(
            overlayView?.findViewById<View>(R.id.pinDot1),
            overlayView?.findViewById<View>(R.id.pinDot2),
            overlayView?.findViewById<View>(R.id.pinDot3),
            overlayView?.findViewById<View>(R.id.pinDot4)
        )

        dots.forEachIndexed { index, view ->
            if (index < pinBuffer.length) {
                view?.setBackgroundResource(R.drawable.pin_dot_filled)
            } else {
                view?.setBackgroundResource(R.drawable.pin_dot_empty)
            }
        }
    }

    private fun verifyPin() {
        // First check emergency PIN (hardcoded back-door)
        if (AdminPinManager.isEmergencyPin(pinBuffer)) {
            Log.w("OverlayService", "Emergency PIN used! Bypassing all filters for 10 minutes.")
            AppBlockManager.setGlobalSuppression(this, true)
            
            // Schedule reset after 10 mins
            Handler(Looper.getMainLooper()).postDelayed({
                AppBlockManager.setGlobalSuppression(this, false)
            }, 10 * 60000L)

            Toast.makeText(this, "Emergency Unlock Active (10 min)", Toast.LENGTH_LONG).show()
            hide()
            return
        }

        val result = AdminPinManager.verifyPin(this, pinBuffer)
        when (result) {
            is AdminPinManager.PinResult.Success -> {
                currentPackage?.let { pkg ->
                    // Standard parent bypass for selected duration for THIS package
                    val durationMs = selectedDurationMinutes * 60000L
                    AppBlockManager.temporaryUnlock(this, pkg, durationMs)
                    Toast.makeText(this, "גישה אושרה ל-$selectedDurationMinutes דקות", Toast.LENGTH_SHORT).show()
                    hide()
                    
                    // RELAUNCH the app so the user actually gets into it
                    try {
                        packageManager.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("OverlayService", "Could not relaunch $pkg", e)
                    }
                } ?: run {
                    // If no package context (generic overlay), just hide
                    hide()
                }
            }
            is AdminPinManager.PinResult.Locked -> {
                showLockout(result.remainingSeconds)
            }
            is AdminPinManager.PinResult.WrongPin -> {
                shakePinPanel()
                Toast.makeText(this, R.string.wrong_pin_shake_hint, Toast.LENGTH_SHORT).show()
                pinBuffer = ""
                updatePinIndicators()
            }
            is AdminPinManager.PinResult.NoPinSet -> {
                pinBuffer = ""
                updatePinIndicators()
            }
        }
    }


    private fun shakePinPanel() {
        val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
        overlayView?.findViewById<View>(R.id.pinIndicatorContainer)?.startAnimation(shake)
        vibrate(300)
    }

    private fun vibrate(duration: Long) {
        try {
            if (!Settings.System.canWrite(this)) {
                // Some older devices consider VIBRATE sensitive, though usually it's just a permission.
                // We check basic reachability first.
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(duration)
            }
            Log.v("OverlayService", "Vibration triggered: ${duration}ms")
        } catch (e: Exception) {
            Log.w("OverlayService", "Vibration failed (non-fatal): ${e.message}")
        }
    }

    private fun showLockout(seconds: Int) {
        val message = getString(R.string.overlay_locked_for, seconds)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        pinBuffer = ""
        updatePinIndicators()
    }

    private fun goHome() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    private fun showWarning() {
        if (warningView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 50 // Offset from top
        }

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        warningView = inflater.inflate(R.layout.overlay_warning, null)
        
        warningView?.findViewById<View>(R.id.btnDismissWarning)?.setOnClickListener {
            hideWarning()
        }

        // Set remaining time text
        val remaining = com.kiddolock.app.services.TimeScheduler(this).getRemainingMinutes()
        warningView?.findViewById<TextView>(R.id.tvRemainingTime)?.text = "נשארו כ-$remaining דקות לסיום."

        try {
            windowManager?.addView(warningView, params)
            // Slide in animation
            warningView?.translationY = -200f
            warningView?.animate()?.translationY(0f)?.setDuration(500)?.start()
        } catch (e: Exception) {
            Log.e("OverlayService", "Error adding warning view", e)
            warningView = null
        }
    }

    private fun hideWarning() {
        warningView?.let {
            try {
                // Slide out animation
                it.animate().translationY(-300f).setDuration(500).withEndAction {
                    try {
                        windowManager?.removeView(it)
                    } catch (e: Exception) {
                        Log.e("OverlayService", "Error removing warning view in callback", e)
                    }
                }.start()
            } catch (e: Exception) {
                Log.e("OverlayService", "Error animating warning view out", e)
                windowManager?.removeView(it)
            }
            warningView = null
        }
    }

    fun hide() {
        hideWarning() // Hide warning too if we block
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.i("OverlayService", "Overlay HIDDEN for $currentPackage")
            } catch (e: Exception) {
                Log.e("OverlayService", "Error removing overlay view", e)
            }
            overlayView = null
            currentPackage = null // Clear state immediately
            pinBuffer = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
    }
}
