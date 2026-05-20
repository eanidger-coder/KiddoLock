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

    // SAFETY (v1.5.54 - post-driving incident): every overlay self-destructs after
    // MAX_OVERLAY_LIFETIME_MS to prevent a stuck block screen from trapping the user.
    // Driving emergency: the user must always be able to reach navigation/emergency apps.
    companion object {
        private const val MAX_OVERLAY_LIFETIME_MS = 60_000L  // 60 seconds, then auto-hide

        // SAFETY (v1.5.56 - post-driving incident, 2026-05-19):
        // Set to true only AFTER WindowManager.addView succeeds. AccessibilityService MUST
        // check this flag before performing GLOBAL_ACTION_HOME — otherwise apps will close
        // silently if addView fails (memory pressure, WindowManager exception, missing perm).
        @Volatile var isOverlayCurrentlyShown: Boolean = false
            private set

        // Minimum time the overlay must remain visible before periodic-check can hide it.
        // 2 seconds is enough for a user to register what happened.
        const val MIN_OVERLAY_DISPLAY_MS = 2_500L
    }
    private val safetyHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        Log.w("OverlayService", "SAFETY: overlay lifetime exceeded ${MAX_OVERLAY_LIFETIME_MS}ms - auto-hiding")
        try { hide() } catch (e: Throwable) { Log.e("OverlayService", "auto-hide failed", e) }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var warningView: View? = null
    private var currentPackage: String? = null
    private var isEmergencyAction = false
    
    // UI Elements for PIN interaction
    private var pinIndicatorContainer: View? = null
    private var pinKeypad: View? = null
    private var tvParentUnlock: View? = null
    private var tvNudgeMessage: TextView? = null
    private var ivLockIcon: ImageView? = null
    private var tvEmergencyRemoval: View? = null
    private var llDurationSelector: View? = null

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
            
            if (action == "HIDE_OVERLAY") {
                // CRITICAL FIX: Do not hide if parent is interacting with the PIN pad
                if (isEmergencyAction || pinIndicatorContainer?.visibility == View.VISIBLE) {
                    Log.d("OverlayService", "Ignoring HIDE_OVERLAY: Parent interaction in progress.")
                    return START_NOT_STICKY
                }
                hide()
                return START_NOT_STICKY
            }

            when (action) {
                "SHOW_OVERLAY" -> {
                    if (pkg == packageName) return START_NOT_STICKY
                    if (overlayView != null && pkg == currentPackage) return START_STICKY
                    currentPackage = pkg
                    showOverlay()
                }
                // "HIDE_OVERLAY" -> hide() // Handled by the if-block above
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
            // SAFETY: even on duplicate show, restart the auto-hide timer
            safetyHandler.removeCallbacks(autoHideRunnable)
            safetyHandler.postDelayed(autoHideRunnable, MAX_OVERLAY_LIFETIME_MS)
            return
        }
        // SAFETY: arm the dead-man auto-hide. If user never dismisses (e.g. system is stuck),
        // overlay self-destructs after 60s so the user is never trapped behind a frozen screen.
        safetyHandler.removeCallbacks(autoHideRunnable)
        safetyHandler.postDelayed(autoHideRunnable, MAX_OVERLAY_LIFETIME_MS)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // FIX: Services don't have a theme by default. Wrap context to resolve theme attributes like ?attr/selectableItemBackground.
        val themedContext = ContextThemeWrapper(this, R.style.Theme_KiddoLock)
        val inflater = themedContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_block, null)

        // 🧒 Kid-friendly text based on block reason
        try {
            val timeScheduler = com.kiddolock.app.services.TimeScheduler(this)
            val titleTv = overlayView?.findViewById<android.widget.TextView>(R.id.tvBlockedTitle)
            val msgTv = overlayView?.findViewById<android.widget.TextView>(R.id.tvBlockedMessage)
            val iconIv = overlayView?.findViewById<android.widget.ImageView>(R.id.ivLockIcon)
            when {
                timeScheduler.isBedtimeActive() -> {
                    titleTv?.text = "🌙 הגיע הזמן לישון"
                    msgTv?.text = "החסימה תוסר בבוקר. לילה טוב!"
                    titleTv?.setTextColor(0xFF9B6EFF.toInt())
                }
                timeScheduler.isDailyLimitReached() -> {
                    titleTv?.text = "⏰ זמן המסך נגמר להיום"
                    msgTv?.text = "נתראה מחר עם זמן חדש. תפנה לפעילות אחרת בינתיים."
                    titleTv?.setTextColor(0xFFFFA502.toInt())
                }
                timeScheduler.isInstantLocked() -> {
                    titleTv?.text = "🔒 ההגנה הופעלה"
                    msgTv?.text = "ההורה הפעיל נעילה זמנית"
                }
                else -> {
                    titleTv?.text = "🛡️ אפליקציה חסומה"
                    msgTv?.text = "ההורים בחרו אילו אפליקציות מותרות. ניתן לבקש מהם גישה."
                }
            }
        } catch (_: Exception) {}
        
        pinIndicatorContainer = overlayView?.findViewById(R.id.pinIndicatorContainer)
        pinKeypad = overlayView?.findViewById(R.id.pinKeypad)
        tvParentUnlock = overlayView?.findViewById(R.id.tvParentUnlock)
        tvNudgeMessage = overlayView?.findViewById(R.id.tvNudgeMessage)
        ivLockIcon = overlayView?.findViewById(R.id.ivLockIcon)
        tvEmergencyRemoval = overlayView?.findViewById(R.id.tvEmergencyRemoval)
        llDurationSelector = overlayView?.findViewById(R.id.llDurationSelector)

        setupPinPad()
        setupEmergencyButtons()
        showAdaptiveNudge()

        tvParentUnlock?.setOnClickListener {
            isEmergencyAction = false // Standard flow includes duration
            pinIndicatorContainer?.visibility = View.VISIBLE
            pinKeypad?.visibility = View.VISIBLE
            llDurationSelector?.visibility = View.VISIBLE
            tvParentUnlock?.visibility = View.GONE
            tvNudgeMessage?.visibility = View.GONE
            
            val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            pinIndicatorContainer?.startAnimation(fadeIn)
            pinKeypad?.startAnimation(fadeIn)
            llDurationSelector?.startAnimation(fadeIn)
        }
        
        tvEmergencyRemoval?.setOnClickListener {
            showEmergencyMode()
        }

        overlayView?.findViewById<View>(R.id.tvGoBack)?.setOnClickListener {
            goHome()
            Handler(Looper.getMainLooper()).postDelayed({ hide() }, 300)
        }

        // Parent Access is now just the llParentAccess button

        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w("OverlayService", "CRITICAL: Cannot draw overlays — permission not granted. Requesting user to fix.")
                return
            }
            windowManager?.addView(overlayView, params)
            // SAFETY: only set the flag AFTER addView returns without exception
            isOverlayCurrentlyShown = true
            Log.i("OverlayService", "Successfully added overlay view for $currentPackage. Reason: ${getBlockingReason()}")
        } catch (e: Exception) {
            Log.e("OverlayService", "CRITICAL ERROR adding overlay view", e)
            overlayView = null
            isOverlayCurrentlyShown = false
            // SAFETY: addView failed — show a fallback Toast so the user at least sees SOMETHING.
            // Without this, the user sees apps closing silently with no explanation (driving-incident).
            try {
                val pkgLabel = try {
                    val pm = packageManager
                    val info = pm.getApplicationInfo(currentPackage ?: "", 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Throwable) { currentPackage ?: "האפליקציה" }
                android.widget.Toast.makeText(
                    this,
                    "🔒 KiddoLock חסם את \"$pkgLabel\" (overlay נכשל)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {}
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
        // Redesigned to SOS icon
    }

    private fun showEmergencyMode() {
        Log.w("OverlayService", "SOS Triggered. Prompting for Remote Emergency Bypass.")
        isEmergencyAction = true
        
        pinIndicatorContainer?.visibility = View.VISIBLE
        pinKeypad?.visibility = View.VISIBLE
        llDurationSelector?.visibility = View.GONE
        tvParentUnlock?.visibility = View.GONE
        tvNudgeMessage?.visibility = View.GONE
        tvEmergencyRemoval?.visibility = View.GONE
        ivLockIcon?.visibility = View.GONE
        
        pinBuffer = ""
        updatePinIndicators()

        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        pinIndicatorContainer?.startAnimation(fadeIn)
        pinKeypad?.startAnimation(fadeIn)
        
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Vibration failed", e)
        }
    }

    private fun showAdaptiveNudge() {
        val nudgeTv = tvNudgeMessage ?: return
        
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
        val result = AdminPinManager.verifyPin(this, pinBuffer)
        
        if (isEmergencyAction) {
            // Special case for emergency override (using parent PIN)
            if (result is AdminPinManager.PinResult.Success || AdminPinManager.isEmergencyPin(pinBuffer)) {
                Log.w("OverlayService", "Emergency override activated via PIN.")
                
                val kidsModeManager = com.kiddolock.app.management.KidsModeManager(this)
                kidsModeManager.isEnabled = false
                // PERMANENT bypass (until reset)
                com.kiddolock.app.management.AppBlockManager.setGlobalSuppression(this, true, true)
                
                Toast.makeText(this, "מצב חירום הופעל: כל החסימות הוסרו וניתן להסיר את האפליקציה.", Toast.LENGTH_LONG).show()
                hide()
                return
            }
        }

        // Standard logic for 8888 failsafe
        if (AdminPinManager.isEmergencyPin(pinBuffer)) {
            Log.w("OverlayService", "Emergency PIN used! Bypassing all filters for 10 minutes.")
            AppBlockManager.setGlobalSuppression(this, true)
            
            Handler(Looper.getMainLooper()).postDelayed({
                AppBlockManager.setGlobalSuppression(this, false)
            }, 10 * 60000L)

            Toast.makeText(this, "Emergency Unlock Active (10 min)", Toast.LENGTH_LONG).show()
            hide()
            return
        }

        handlePinResult(result)
    }

    private fun handlePinResult(result: AdminPinManager.PinResult) {
        when (result) {
            is AdminPinManager.PinResult.Success -> {
                currentPackage?.let { pkg ->
                    val durationMs = selectedDurationMinutes * 60000L
                    AppBlockManager.temporaryUnlock(this, pkg, durationMs)

                    // CRITICAL FIX (v1.5.53): we used to set emergency_bypass_until here for Settings
                    // packages, but that flag is GLOBAL — it disabled BypassGuard for every app, not just
                    // Settings. Users who unlocked Settings ended up with the entire device unrestricted.
                    // The correct fix is in BypassGuard.checkNavigation: it now respects per-package
                    // temporaryUnlock state, so Settings unlocks here remain scoped to Settings only.

                    Toast.makeText(this, "גישה אושרה ל-$selectedDurationMinutes דקות", Toast.LENGTH_SHORT).show()
                    hide()
                    
                    try {
                        packageManager.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("OverlayService", "Could not relaunch $pkg", e)
                    }
                } ?: hide()
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
        // SAFETY: cancel pending auto-hide whenever we hide manually
        safetyHandler.removeCallbacks(autoHideRunnable)
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
            isEmergencyAction = false
        }
        // SAFETY: always clear the global flag — even if overlayView was already null
        isOverlayCurrentlyShown = false
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
    }
}
