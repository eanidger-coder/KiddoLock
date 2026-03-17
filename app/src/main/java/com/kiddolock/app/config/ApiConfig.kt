package com.kiddolock.app.config

import android.content.Context

/**
 * Central API configuration for KiddoLock.
 * All network calls must reference BASE_URL from here.
 */
object ApiConfig {

    /**
     * Development local endpoint for AI debugging.
     */
    const val DEBUG_URL = "192.168.1.67:8787"

    /**
     * Production Cloudflare Workers endpoint for KiddoLock.
     * Note: This is a dedicated endpoint for KiddoLock parental controls.
     */
    const val BASE_URL = "https://kiddolock-api.eanidger.workers.dev"

    /** Admin API key header name */
    const val ADMIN_KEY_HEADER = "X-Admin-Key"

    /** Request timeout constants (seconds) */
    const val CONNECT_TIMEOUT_SEC = 15L
    const val READ_TIMEOUT_SEC = 30L

    /**
     * Returns the appropriate base URL based on build type.
     * In DEBUG builds, allows override via SharedPreferences for local testing.
     */
    fun getBaseUrl(context: Context): String {
        // In DEBUG builds, priority to local dev server
        if (com.kiddolock.app.BuildConfig.DEBUG) {
            val devUrl = context
                .getSharedPreferences("dev_config", Context.MODE_PRIVATE)
                .getString("api_base_url", null)
            
            if (!devUrl.isNullOrBlank()) return devUrl
        }
        return BASE_URL
    }
}
