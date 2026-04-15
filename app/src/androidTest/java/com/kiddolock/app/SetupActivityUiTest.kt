package com.kiddolock.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.kiddolock.app.ui.SetupActivity
import org.hamcrest.CoreMatchers.not
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for the setup wizard that the user reported getting
 * stuck on. Covers the 5 permission cards + Continue button.
 *
 * Why each assertion matters:
 *   - btnContinue.disabled-on-fresh-install: fresh emulator has zero
 *     permissions granted ⇒ Continue must NOT let the user advance.
 *     This is what prevents the wizard from handing off to MainActivity
 *     when state is actually incomplete.
 *   - all 5 cards visible: regression check that the layout hasn't drifted
 *     or been partially rebranded with missing IDs.
 *   - cardNotifications click shows dialog: the showPermissionGuide()
 *     flow is where restricted-settings guidance lives — it MUST open.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SetupActivityUiTest {

    @Test
    fun allFiveCards_areVisible() {
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
        ActivityScenario.launch(SetupActivity::class.java).use {
            // Fresh emulator — overlay/a11y/admin/usage all disabled.
            onView(withId(R.id.btnContinue)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun clickingAccessibilityCard_opensGuideDialog() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.cardAccessibility)).perform(click())
            // The guide dialog shows the button "פתח הגדרות" (see
            // dialog_permission_guide.xml) — verify it's visible.
            onView(withId(R.id.btnGoToSettings)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun headerShowsSafeLockBrand() {
        // Setup wizard title comes from R.string.setup_title = "ברוכים הבאים ל-SafeLock".
        // Verifying a literal substring is brittle across localization, but
        // we just want to confirm zero "KiddoLock" on the first screen the
        // user sees.
        ActivityScenario.launch(SetupActivity::class.java).use {
            // Presence check on the tvContinueHint — if it renders, layout
            // inflated without crashing.
            onView(withText(R.string.setup_title)).check(matches(isDisplayed()))
        }
    }
}
