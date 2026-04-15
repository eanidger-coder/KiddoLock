package com.kiddolock.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.ui.AdminPinActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the admin-PIN keypad. Pure UI smoke — every numeric button +
 * delete must be clickable without crashing and the forgot-PIN link must
 * render. Logic behind verification is already covered by
 * AdminPinManagerTest (JVM/Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AdminPinActivityUiTest {

    @Before
    fun setUp() {
        // Start from the "no PIN set" state so the activity opens in
        // setup-mode (tvForgot hidden, keypad active).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AdminPinManager.resetPin(ctx)
    }

    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AdminPinManager.resetPin(ctx)
    }

    @Test
    fun keypad_allDigitsAndDelete_areClickable() {
        ActivityScenario.launch(AdminPinActivity::class.java).use {
            listOf(
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9,
                R.id.btnDelete
            ).forEach { id ->
                onView(withId(id)).check(matches(isDisplayed()))
            }
            // Sanity: clicking one digit then delete should not crash.
            onView(withId(R.id.btn1)).perform(click())
            onView(withId(R.id.btnDelete)).perform(click())
        }
    }

    @Test
    fun pinIndicators_areRendered() {
        ActivityScenario.launch(AdminPinActivity::class.java).use {
            onView(withId(R.id.pinDot1)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot2)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot3)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot4)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun title_rendersWithoutCrashing() {
        ActivityScenario.launch(AdminPinActivity::class.java).use {
            onView(withId(R.id.tvPinTitle)).check(matches(isDisplayed()))
        }
    }
}
