package com.kiddolock.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
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
 * Pure-render smoke for the admin-PIN keypad. Deliberately no click
 * interactions — those were flaky on the emulator and added no coverage
 * above what AdminPinManagerTest (JVM/Robolectric) already locks down.
 * We only assert that the critical views inflate so a layout regression
 * (renamed ID, missing include, etc.) is caught quickly.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AdminPinActivityUiTest {

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AdminPinManager.resetPin(ctx)
    }

    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AdminPinManager.resetPin(ctx)
    }

    @Test
    fun keypadAndDots_areRendered() {
        ActivityScenario.launch(AdminPinActivity::class.java).use {
            // Keypad digits + delete
            listOf(
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9,
                R.id.btnDelete
            ).forEach { id ->
                onView(withId(id)).check(matches(isDisplayed()))
            }
            // PIN indicator dots
            onView(withId(R.id.pinDot1)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot2)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot3)).check(matches(isDisplayed()))
            onView(withId(R.id.pinDot4)).check(matches(isDisplayed()))
            // Title
            onView(withId(R.id.tvPinTitle)).check(matches(isDisplayed()))
        }
    }
}
