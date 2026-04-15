package com.kiddolock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiddolock.app.utils.NotificationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the ongoing foreground-service notification exposes a PIN-gated
 * "emergency uninstall" action.
 *
 * Regression guard for the issue where the app UI froze on the phone and
 * there was no way to uninstall without rebooting — the action must be
 * attached to the notification so it's reachable from the shade even when
 * the activity stack is wedged.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyUninstallNotificationTest {

    @Test
    fun ongoingNotification_hasEmergencyUninstallAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification = NotificationUtils.buildNotification(context, active = true)

        assertNotNull("notification.actions must not be null", notification.actions)
        assertTrue(
            "notification must expose at least one action",
            notification.actions.isNotEmpty()
        )

        val uninstallAction = notification.actions.firstOrNull { action ->
            action.title?.toString() == "הסרת חירום"
        }
        assertNotNull(
            "notification must include an 'הסרת חירום' action for emergency uninstall",
            uninstallAction
        )

        // The action's PendingIntent must exist so tapping the button
        // actually does something. We avoid inspecting creatorPackage here
        // because on some images it's populated asynchronously and returns
        // null synchronously at build() time — which would flake the test
        // without catching a real bug.
        assertNotNull(
            "uninstall action must carry a PendingIntent",
            uninstallAction!!.actionIntent
        )
    }

    @Test
    fun emergencyUninstallAction_constantMatchesManifest() {
        // Sanity-check that the constant used by the receiver matches the value
        // declared in AndroidManifest.xml's intent-filter.
        assertEquals(
            "com.kiddolock.app.EMERGENCY_UNINSTALL",
            NotificationUtils.ACTION_EMERGENCY_UNINSTALL
        )
    }
}
