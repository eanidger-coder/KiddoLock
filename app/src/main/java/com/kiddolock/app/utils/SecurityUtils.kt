package com.kiddolock.app.utils

import java.security.MessageDigest

object SecurityUtils {
    private const val SECURE_SALT = "KIDDO_LOCK_SECURE_SALT_2026_V1"

    fun isValid(input: String, deviceId: String): Boolean {
        if (input.isBlank()) return false
        // Debug bypass — ONLY in debug builds
        if (com.kiddolock.app.BuildConfig.DEBUG && input == "999999") return true
        val expectedHash = getExitCode(deviceId)
        
        // Use a safe logging approach that won't crash in JVM tests
        try {
            android.util.Log.d("KiddoSecurity", "Verifying bypass code for device: $deviceId")
        } catch (e: Exception) {
            // Probably running in a unit test environment
        }
        
        return input == expectedHash
    }

    fun getExitCode(deviceId: String): String {
        // Generate a 6-digit numeric code from device ID using SHA-256 for better entropy
        val hash = sha256(deviceId + SECURE_SALT).replace(Regex("[^0-9]"), "")
        return if (hash.length >= 6) hash.substring(0, 6) else hash.padEnd(6, '0')
    }

    fun getKiddoSignature(timestamp: Long, path: String): String {
        val dataToSign = "$timestamp:$path"
        val secret = SECURE_SALT // In prod, this would be fetched from Keystore
        return hmacSha256(dataToSign, secret)
    }

    private fun hmacSha256(data: String, key: String): String {
        return try {
            val hmacKey = javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256")
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(hmacKey)
            val bytes = mac.doFinal(data.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun sha256(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
