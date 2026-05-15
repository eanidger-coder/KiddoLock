package com.kiddolock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiddolock.app.management.SettingsSyncManager
import com.kiddolock.app.management.KidsModeManager
import com.kiddolock.app.management.PerAppTimeTracker
import com.kiddolock.app.services.TimeScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class SettingsSyncTest {

    @Test
    fun testSettingsSyncApplyAndGather() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val syncManager = SettingsSyncManager(context)

        // Use reflection to access private methods for testing
        val applyMethod: Method = SettingsSyncManager::class.java.getDeclaredMethod("applySettingsLocally", SettingsSyncManager.CloudSettings::class.java)
        applyMethod.isAccessible = true

        val gatherMethod: Method = SettingsSyncManager::class.java.getDeclaredMethod("gatherLocalSettings")
        gatherMethod.isAccessible = true

        // Create a simulated CloudSettings object from Cloudflare
        val simulatedSettings = SettingsSyncManager.CloudSettings(
            kidsModeEnabled = true,
            strictYoutubeEnabled = false,
            strictModeEnabled = true,
            antiUninstallEnabled = true,
            bedtimeStart = 20,
            bedtimeStartMin = 30,
            bedtimeEnd = 7,
            bedtimeEndMin = 0,
            dailyLimitMinutes = 120,
            perAppLimits = mapOf("com.google.android.youtube" to 60)
        )

        // Apply settings locally
        applyMethod.invoke(syncManager, simulatedSettings)

        // Verify locally via managers
        val kidsModeManager = KidsModeManager(context)
        val timeScheduler = TimeScheduler(context)
        val perAppTracker = PerAppTimeTracker(context)

        assertTrue("Kids Mode should be enabled", kidsModeManager.isEnabled)
        assertFalse("Strict Youtube should be disabled", kidsModeManager.isStrictWhitelist)

        val config = timeScheduler.getConfig()
        assertEquals(20, config.kidsQuietHoursStart)
        assertEquals(30, config.kidsQuietHoursStartMin)
        assertEquals(120, config.kidsDailyTimeLimitMinutes)

        val youtubeLimit = perAppTracker.getAllLimitedApps().find { it.packageName == "com.google.android.youtube" }?.limitMinutes
        assertEquals(60, youtubeLimit)

        // Now test gathering settings
        val gatheredSettings = gatherMethod.invoke(syncManager) as SettingsSyncManager.CloudSettings

        assertEquals(true, gatheredSettings.kidsModeEnabled)
        assertEquals(false, gatheredSettings.strictYoutubeEnabled)
        assertEquals(true, gatheredSettings.strictModeEnabled)
        assertEquals(true, gatheredSettings.antiUninstallEnabled)
        assertEquals(20, gatheredSettings.bedtimeStart)
        assertEquals(30, gatheredSettings.bedtimeStartMin)
        assertEquals(7, gatheredSettings.bedtimeEnd)
        assertEquals(0, gatheredSettings.bedtimeEndMin)
        assertEquals(120, gatheredSettings.dailyLimitMinutes)
        assertEquals(60, gatheredSettings.perAppLimits?.get("com.google.android.youtube"))
        
        // Test modifying a setting and gathering again
        // Let's modify antiUninstallEnabled
        val prefs = context.getSharedPreferences("kiddolock_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("uninstall_protection_enabled", false).commit()
        
        val gatheredSettings2 = gatherMethod.invoke(syncManager) as SettingsSyncManager.CloudSettings
        assertEquals(false, gatheredSettings2.antiUninstallEnabled)
    }
}
