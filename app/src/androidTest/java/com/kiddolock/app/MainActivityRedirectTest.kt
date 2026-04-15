package com.kiddolock.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.ui.SetupActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso UI regression test for the "stuck after setup" bug.
 *
 * Flow:
 *  - Fresh install ⇒ permissions are not granted ⇒ MainActivity.onResume
 *    must redirect to SetupActivity.
 *  - This is the exact flow the user hit in production: after completing
 *    the 5-step wizard the user clicked Continue, MainActivity opened,
 *    immediately decided setup wasn't complete, and bounced them back.
 *  - Locking this down prevents a future regression where the permission
 *    checks disagree between the two screens.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityRedirectTest {

    @Before
    fun setUp() {
        // Clear any lingering PIN state from a previous run so we stay in the
        // "not yet configured" state for these tests.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AdminPinManager.resetPin(ctx)
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun mainActivity_whenSetupIncomplete_redirectsToSetupActivity() {
        // Launching MainActivity from a fresh install (no permissions granted
        // in the emulator) must redirect to SetupActivity.
        ActivityScenario.launch(MainActivity::class.java).use {
            intended(hasComponent(SetupActivity::class.java.name))
        }
    }

    @Test
    fun setupActivity_rendersWithoutCrashing() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.btnContinue)).check(matches(isDisplayed()))
        }
    }
}
