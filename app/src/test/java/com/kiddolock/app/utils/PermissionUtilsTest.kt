package com.kiddolock.app.utils

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.kiddolock.app.services.SafeLockAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the PermissionUtils.hasAccessibilityService parser.
 *
 * REGRESSION TEST for the "stuck after setup" bug:
 * Previously MainActivity used `String.contains(flattenToString())` while
 * SetupActivity used the proper ComponentName-splitter. When Android stored
 * the service with different casing / short form, the two checks disagreed
 * and the user bounced between MainActivity ↔ SetupActivity (perceived freeze).
 * These tests lock down the robust parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionUtilsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val expected: ComponentName
        get() = ComponentName(context, SafeLockAccessibilityService::class.java)

    private fun setEnabledServices(value: String?) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value
        )
    }

    @Test
    fun hasAccessibility_returnsFalse_whenSettingIsNull() {
        setEnabledServices(null)
        assertFalse(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsFalse_whenSettingIsEmpty() {
        setEnabledServices("")
        assertFalse(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsTrue_forFullyQualifiedName() {
        setEnabledServices(expected.flattenToString())
        assertTrue(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsTrue_forShortFormName() {
        // Android sometimes stores as "pkg/.Class" (relative class name)
        val relativeClass = expected.className.removePrefix(expected.packageName)
        val short = "${expected.packageName}/$relativeClass"
        setEnabledServices(short)
        assertTrue(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsTrue_whenAmongMultipleServices() {
        val services = listOf(
            "com.example.other/.SomeService",
            expected.flattenToString(),
            "com.another.app/com.another.app.AccessibilityService"
        ).joinToString(":")
        setEnabledServices(services)
        assertTrue(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsFalse_whenOnlyOtherServicesEnabled() {
        setEnabledServices("com.example.other/.SomeService:com.foo/.BarService")
        assertFalse(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_returnsFalse_forSubstringMatchOnlyBug() {
        // This is the old bug case: the previous implementation used
        // `String.contains(flattenToString())`. A crafted component whose
        // flattened form starts with our package as a substring must NOT match.
        setEnabledServices("com.kiddolock.app.evil/com.kiddolock.app.evil.FakeService")
        assertFalse(PermissionUtils.hasAccessibilityService(context))
    }
}
