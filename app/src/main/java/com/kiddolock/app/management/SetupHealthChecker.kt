package com.kiddolock.app.management

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Checks if all required system permissions and services are properly configured.
 * Returns a non-blocking health report so the user can be notified without
 * interrupting their experience.
 */
class SetupHealthChecker(private val context: Context) {

    data class HealthReport(
        val status: HealthStatus,
        val missingPermissions: List<String>
    ) {
        val isFullyConfigured get() = status == HealthStatus.FULLY_CONFIGURED
    }

    enum class HealthStatus {
        FULLY_CONFIGURED,
        PARTIALLY_CONFIGURED,
        NOT_CONFIGURED
    }

    fun check(): HealthReport {
        val missing = mutableListOf<String>()

        if (!isAccessibilityEnabled()) missing.add("שירות נגישות")
        if (!isOverlayPermissionGranted()) missing.add("תצוגה מעל אפליקציות")

        val status = when (missing.size) {
            0 -> HealthStatus.FULLY_CONFIGURED
            in 1..1 -> HealthStatus.PARTIALLY_CONFIGURED
            else -> HealthStatus.NOT_CONFIGURED
        }

        return HealthReport(status, missing)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == context.packageName
        }
    }

    private fun isOverlayPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }
}
