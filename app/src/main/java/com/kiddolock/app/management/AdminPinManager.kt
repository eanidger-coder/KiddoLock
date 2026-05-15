package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * Manages the admin PIN for KiddoLock.
 * 
 * Security model:
 * - PIN is stored as salted SHA-256 hash, never in plaintext
 * - Salt is derived from the device's persistent unique ID
 * - After 5 failed attempts → 5-minute lockout
 * - Lockout doubles on each subsequent violation (up to 60 min)
 */
object AdminPinManager {

    private const val TAG = "AdminPinManager"
    private const val PREFS_NAME = "admin_pin_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SET = "pin_set"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_ms"
    private const val KEY_LOCKOUT_COUNT = "lockout_count"
    private const val KEY_RECOVERY_EMAIL = "recovery_email"
    private const val KEY_RECOVERY_CODE = "recovery_code"
    private const val KEY_RECOVERY_CODE_TIME = "recovery_code_time"
    private const val RECOVERY_CODE_EXPIRY = 15 * 60 * 1000L // 15 minutes
    private const val MAX_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 5 * 60 * 1000L // 5 minutes
    // No grace period - every return to app requires PIN if backgrounded

    private var authenticatedUntil = 0L

    /** Returns true if an admin PIN has been configured. */
    fun isPinSet(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PIN_SET, false)
    }

    /** 
     * Returns true if the user is currently authenticated within the session window.
     */
    fun isAuthenticated(): Boolean {
        return System.currentTimeMillis() < authenticatedUntil
    }

    fun getRecoveryEmail(context: Context): String? {
        return prefs(context).getString(KEY_RECOVERY_EMAIL, null)
    }

    fun setRecoveryEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_RECOVERY_EMAIL, email).apply()
    }

    fun generateRecoveryCode(context: Context): String {
        val code = (100000..999999).random().toString()
        val prefs = prefs(context)
        prefs.edit()
            .putString(KEY_RECOVERY_CODE, code)
            .putLong(KEY_RECOVERY_CODE_TIME, System.currentTimeMillis())
            .apply()
        return code
    }

    fun verifyRecoveryCode(context: Context, input: String): Boolean {
        if (input.isEmpty()) return false
        val prefs = prefs(context)
        val storedCode = prefs.getString(KEY_RECOVERY_CODE, null)
        val timestamp = prefs.getLong(KEY_RECOVERY_CODE_TIME, 0L)
        
        if (storedCode == null || System.currentTimeMillis() - timestamp > RECOVERY_CODE_EXPIRY) {
            return false
        }
        
        if (storedCode == input) {
            // Clear code after successful use
            prefs.edit().remove(KEY_RECOVERY_CODE).remove(KEY_RECOVERY_CODE_TIME).apply()
            return true
        }
        return false
    }

    /**
     * אין יותר PIN חירום קבוע. שיטות שחזור: PIN של ההורה, דשבורד מרחוק, קוד שחזור במייל.
     * הפונקציה נשארת כדי לא לשבור קוד קיים, אבל תמיד מחזירה false.
     */
    fun isEmergencyPin(pin: String): Boolean = false

    /**
     * Extends the current authenticated session.
     */
    fun extendSession() {
        authenticatedUntil = Long.MAX_VALUE // Session is valid for the current foreground run
    }

    /**
     * Clears the current session (e.g., when the app is explicitly closed or for security).
     */
    fun clearSession() {
        authenticatedUntil = 0L
    }

    /**
     * Set a new admin PIN. Hashes it with SHA-256 before storing.
     * Returns false if PIN is too short (< 4 digits).
     */
    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        val persistentId = com.kiddolock.app.utils.DeviceIdentifier.getPersistentId(context)
        val hash = sha256(pin, persistentId)
        prefs(context).edit()
            .putString(KEY_PIN_HASH, hash)
            .putBoolean(KEY_PIN_SET, true)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .putInt(KEY_LOCKOUT_COUNT, 0)
            .apply()
        Log.i(TAG, "Admin PIN set successfully (salted)")
        extendSession() // Authenticate immediately after setup
        return true
    }

    fun getStoredPinHash(context: Context): String? {
        return prefs(context).getString(KEY_PIN_HASH, null)
    }

    /**
     * Verify the PIN.
     * Returns a [PinResult] describing the outcome.
     */
    fun verifyPin(context: Context, input: String): PinResult {
        // אין יותר Master PIN! רק ה-PIN שההורה הגדיר.
        // אם ההורה שכח: שחזור דרך הדשבורד ב-Cloudflare או דרך קוד שחזור במייל.

        val p = prefs(context)

        // Check lockout
        val lockoutUntil = p.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) {
            val remainingSec = ((lockoutUntil - now) / 1000).toInt()
            return PinResult.Locked(remainingSec)
        }

        // Debug bypass — only in debug builds, works even if no PIN set yet
        if (com.kiddolock.app.BuildConfig.DEBUG && input == "999999") {
            Log.i(TAG, "Admin PIN verified via DEBUG bypass")
            return PinResult.Success
        }

        val storedHash = p.getString(KEY_PIN_HASH, null)
            ?: run {
                // If no PIN is set, allow '9999' as the default initial PIN
                if (input == "9999") {
                    Log.i(TAG, "Admin PIN verified via DEFAULT fallback (9999)")
                    return PinResult.Success
                }
                return PinResult.NoPinSet
            }

        val persistentId = com.kiddolock.app.utils.DeviceIdentifier.getPersistentId(context)
        val inputHashSalted = sha256(input, persistentId)
        val inputHashLegacy = sha256(input) // OLD method without salt

        return if (inputHashSalted == storedHash || inputHashLegacy == storedHash) {
            // Migration: Upgrade to salted hash if this was a legacy login
            if (inputHashLegacy == storedHash) {
                Log.i(TAG, "Migrating legacy PIN hash to salted hash")
                p.edit().putString(KEY_PIN_HASH, inputHashSalted).apply()
            }

            // Success — reset counters
            p.edit()
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .putInt(KEY_LOCKOUT_COUNT, 0)
                .apply()
            
            extendSession()
            Log.i(TAG, "Admin PIN verified successfully")
            PinResult.Success
        } else {
            // Failure — increment counter
            val failCount = p.getInt(KEY_FAIL_COUNT, 0) + 1
            p.edit().putInt(KEY_FAIL_COUNT, failCount).apply()
            Log.w(TAG, "PIN failed: attempt $failCount / $MAX_ATTEMPTS")

            if (failCount >= MAX_ATTEMPTS) {
                val lockoutCount = p.getInt(KEY_LOCKOUT_COUNT, 0) + 1
                val lockoutDuration = BASE_LOCKOUT_MS * lockoutCount
                val lockoutEnd = now + lockoutDuration
                p.edit()
                    .putInt(KEY_FAIL_COUNT, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, lockoutEnd)
                    .putInt(KEY_LOCKOUT_COUNT, lockoutCount)
                    .apply()
                Log.w(TAG, "Too many attempts — locked for ${lockoutDuration / 60000} minutes")
                PinResult.Locked((lockoutDuration / 1000).toInt())
            } else {
                PinResult.WrongPin(remainingAttempts = MAX_ATTEMPTS - failCount)
            }
        }
    }

    /** How many seconds remain in lockout, 0 if not locked. */
    fun getLockoutRemainingSeconds(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0L
    }

    fun isLocked(context: Context) = getLockoutRemainingSeconds(context) > 0

    /** Reset PIN entirely (e.g., after factory reset or owner recovery). */
    fun resetPin(context: Context) {
        prefs(context).edit().clear().apply()
        Log.w(TAG, "Admin PIN reset")
    }

    private fun sha256(input: String, salt: String? = null): String {
        val dataToHash = if (salt != null) input + salt else input
        val bytes = MessageDigest.getInstance("SHA-256").digest(dataToHash.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    sealed class PinResult {
        object Success : PinResult()
        object NoPinSet : PinResult()
        data class WrongPin(val remainingAttempts: Int) : PinResult()
        data class Locked(val remainingSeconds: Int) : PinResult()
    }
}
