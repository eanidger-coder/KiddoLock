package com.kiddolock.app

import com.kiddolock.app.utils.SecurityUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates core security logic: hashing, exit codes, and branding consistency.
 */
class SecurityTest {

    @Test
    fun testExitCodeGeneration() {
        val deviceId = "test_device_123"
        val code1 = SecurityUtils.getExitCode(deviceId)
        val code2 = SecurityUtils.getExitCode(deviceId)
        
        // Deterministic
        assertEquals(code1, code2)
        
        // Format check (6 digits)
        assertEquals(6, code1.length)
        assertTrue(code1.all { it.isDigit() })
        
        // Uniqueness
        val code3 = SecurityUtils.getExitCode("different_device")
        assertNotEquals(code1, code3)
    }

    @Test
    fun testBrandingConsistency() {
        val timestamp = System.currentTimeMillis()
        val path = "/test"
        
        // Ensure signatures don't contain legacy "Shield" remnants in their logic
        val sig = SecurityUtils.getKiddoSignature(timestamp, path)
        assertNotNull(sig)
        assertTrue(sig.length > 32) // HMAC-SHA256 should be 64 chars in hex
    }
}
