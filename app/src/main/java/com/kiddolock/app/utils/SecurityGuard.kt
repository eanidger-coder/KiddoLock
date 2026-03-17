package com.kiddolock.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Detects device tampering, root access, and potential security bypasses.
 */
object SecurityGuard {

    private const val TAG = "SecurityGuard"

    /**
     * Comprehensive check for device security status.
     */
    fun isDeviceSecure(context: Context): Boolean {
        if (isRooted()) {
            Log.e(TAG, "Unsecured device: Root access detected!")
            return false
        }
        
        if (isHookingFrameworkDetected()) {
            Log.e(TAG, "Unsecured device: Hooking framework (Frida/Xposed) detected!")
            return false
        }

        return true
    }

    /**
     * Checks for Frida, Xposed, and other hooking frameworks.
     */
    private fun isHookingFrameworkDetected(): Boolean {
        try {
            // Check for Xposed
            val xposedDetected = try {
                val stackTrace = Throwable().stackTrace
                stackTrace.any { it.className.lowercase().contains("xposed") }
            } catch (e: Exception) { false }

            if (xposedDetected) return true

            // Check for Frida (common ports and files)
            val fridaPaths = arrayOf("/data/local/tmp/frida-server", "/usr/bin/frida-server")
            if (fridaPaths.any { File(it).exists() }) return true

            // Check for maps containing 'frida'
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val content = mapsFile.readText()
                if (content.contains("frida", ignoreCase = true)) return true
            }

        } catch (e: Exception) {
            // Log error but don't crash the scanning loop
            Log.e(TAG, "Hooking detection error", e)
        }
        return false
    }

    /**
     * Checks if the device is rooted by looking for common root indicators.
     */
    private fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        for (path in paths) {
            if (File(path).exists()) return true
        }

        // Try executing 'su' to be sure
        return try {
            Runtime.getRuntime().exec("su").destroy()
            true
        } catch (e: Exception) {
            false
        }
    }
}
