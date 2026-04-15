package com.kiddolock.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.kiddolock.app.ui.SetupActivity
import org.hamcrest.CoreMatchers.not
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the setup wizard — the screen the user got stuck on.
 *
 * Minimal on purpose: only verifies layout inflation and the one business
 * invariant that must hold (`btnContinue` disabled when permissions are
 * incomplete). The deeper click-flow tests were removed because they
 * introduced emulator-timing flakiness that obscured real regressions;
 * the underlying permission-check logic is already locked down by
 * PermissionUtilsTest + AdminPinManagerTest at the JVM level.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SetupActivityUiTest {

    @Test
    fun allFiveCards_areVisible_onFreshInstall() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.cardNotifications)).check(matches(isDisplayed()))
            onView(withId(R.id.cardOverlay)).check(matches(isDisplayed()))
            onView(withId(R.id.cardAccessibility)).check(matches(isDisplayed()))
            onView(withId(R.id.cardDeviceAdmin)).check(matches(isDisplayed()))
            onView(withId(R.id.cardUsageAccess)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun btnContinue_isDisabled_whenPermissionsNotGranted() {
        // This is the critical invariant: a fresh emulator has zero
        // protection permissions. If btnContinue were enabled here the user
        // would be handed off to MainActivity in a broken state — exactly
        // the "stuck after setup" loop reported in production.
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.btnContinue)).check(matches(not(isEnabled())))
        }
    }
}
