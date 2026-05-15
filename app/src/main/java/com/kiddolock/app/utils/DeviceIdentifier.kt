package com.kiddolock.app.utils

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Handles unique device identification for sync and registration without requiring logins.
 */
object DeviceIdentifier {

    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_UUID = "persistent_device_uuid"

    /**
     * Gets or generates a persistent UUID for this device.
     * Unlike ANDROID_ID, this survives app re-installs if stored in Keystore (future enhancement),
     * but currently persists in SharedPreferences.
     */
    fun getPersistentId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_UUID, null)

        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, uuid).apply()
        }

        return uuid
    }

    /**
     * Legacy Android ID for backwards compatibility in existing security logic.
     */
    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
    }
}
