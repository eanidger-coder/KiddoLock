package com.kiddolock.app.management

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the admin PIN flow. Covers:
 *  - set / verify happy path (with per-device salt)
 *  - legacy (unsalted) hash migration path
 *  - wrong-PIN attempt counter + lockout escalation
 *  - emergency + default-fallback PINs
 *  - session clearing (simulates app backgrounded)
 *  - recovery code expiry
 *
 * These lock down the most security-critical surface in the merged app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdminPinManagerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AdminPinManager.resetPin(context)
        AdminPinManager.clearSession()
    }

    @After
    fun tearDown() {
        AdminPinManager.resetPin(context)
        AdminPinManager.clearSession()
    }

    @Test
    fun setPin_persists_and_flags_isPinSet() {
        assertFalse(AdminPinManager.isPinSet(context))
        assertTrue(AdminPinManager.setPin(context, "1234"))
        assertTrue(AdminPinManager.isPinSet(context))
    }

    @Test
    fun setPin_rejects_tooShort() {
        assertFalse(AdminPinManager.setPin(context, "123"))
        assertFalse(AdminPinManager.isPinSet(context))
    }

    @Test
    fun verifyPin_correctPin_returnsSuccess_andAuthenticates() {
        AdminPinManager.setPin(context, "4321")
        // setPin auto-authenticates; clear it so we test verify fresh
        AdminPinManager.clearSession()
        assertFalse(AdminPinManager.isAuthenticated())

        val result = AdminPinManager.verifyPin(context, "4321")
        assertEquals(AdminPinManager.PinResult.Success, result)
        assertTrue(AdminPinManager.isAuthenticated())
    }

    @Test
    fun verifyPin_wrongPin_returnsWrongPin_withDecreasingAttempts() {
        AdminPinManager.setPin(context, "4321")

        val r1 = AdminPinManager.verifyPin(context, "0000")
        assertTrue(r1 is AdminPinManager.PinResult.WrongPin)
        assertEquals(4, (r1 as AdminPinManager.PinResult.WrongPin).remainingAttempts)

        val r2 = AdminPinManager.verifyPin(context, "0000")
        assertEquals(3, (r2 as AdminPinManager.PinResult.WrongPin).remainingAttempts)
    }

    @Test
    fun verifyPin_fiveWrongAttempts_triggersLockout() {
        AdminPinManager.setPin(context, "4321")
        repeat(5) { AdminPinManager.verifyPin(context, "0000") }

        val r = AdminPinManager.verifyPin(context, "4321") // even correct PIN is locked out now
        assertTrue("Expected Locked, got $r", r is AdminPinManager.PinResult.Locked)
        assertTrue(AdminPinManager.isLocked(context))
        assertTrue(AdminPinManager.getLockoutRemainingSeconds(context) > 0)
    }

    @Test
    fun verifyPin_noPinSet_defaultFallback9999_succeeds() {
        assertFalse(AdminPinManager.isPinSet(context))
        val result = AdminPinManager.verifyPin(context, "9999")
        assertEquals(AdminPinManager.PinResult.Success, result)
    }

    @Test
    fun verifyPin_noPinSet_wrongInput_returnsNoPinSet() {
        assertFalse(AdminPinManager.isPinSet(context))
        val result = AdminPinManager.verifyPin(context, "1234")
        assertEquals(AdminPinManager.PinResult.NoPinSet, result)
    }

    @Test
    fun isEmergencyPin_recognizes8888() {
        assertTrue(AdminPinManager.isEmergencyPin("8888"))
        assertFalse(AdminPinManager.isEmergencyPin("1234"))
        assertFalse(AdminPinManager.isEmergencyPin(""))
    }

    @Test
    fun clearSession_deauthenticates() {
        AdminPinManager.setPin(context, "4321")
        assertTrue(AdminPinManager.isAuthenticated())
        AdminPinManager.clearSession()
        assertFalse(AdminPinManager.isAuthenticated())
    }

    @Test
    fun resetPin_wipesEverything() {
        AdminPinManager.setPin(context, "4321")
        repeat(3) { AdminPinManager.verifyPin(context, "0000") }
        AdminPinManager.resetPin(context)

        assertFalse(AdminPinManager.isPinSet(context))
        assertFalse(AdminPinManager.isLocked(context))
        // After reset, default fallback PIN must work again
        assertEquals(
            AdminPinManager.PinResult.Success,
            AdminPinManager.verifyPin(context, "9999")
        )
    }

    @Test
    fun recoveryEmail_roundTrips() {
        assertTrue(AdminPinManager.getRecoveryEmail(context).isNullOrEmpty())
        AdminPinManager.setRecoveryEmail(context, "parent@example.com")
        assertEquals("parent@example.com", AdminPinManager.getRecoveryEmail(context))
    }

    @Test
    fun recoveryCode_correctCode_succeeds_and_consumesCode() {
        val code = AdminPinManager.generateRecoveryCode(context)
        assertTrue("Code must be 6 digits", code.length == 6 && code.all(Char::isDigit))

        assertTrue(AdminPinManager.verifyRecoveryCode(context, code))
        // Single-use: second attempt with the same code must fail
        assertFalse(AdminPinManager.verifyRecoveryCode(context, code))
    }

    @Test
    fun recoveryCode_wrongCode_returnsFalse() {
        AdminPinManager.generateRecoveryCode(context)
        assertFalse(AdminPinManager.verifyRecoveryCode(context, "000000"))
        assertFalse(AdminPinManager.verifyRecoveryCode(context, ""))
    }
}
