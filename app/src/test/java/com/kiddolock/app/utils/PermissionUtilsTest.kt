package com.kiddolock.app.utils

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.kiddolock.app.services.SafeLockAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val expected = ComponentName(context, SafeLockAccessibilityService::class.java)

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
        val short = "${expected.packageName}/${expected.className.removePrefix(expected.packageName)}"
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
        // This is the old bug case: a different service whose name *contains*
        // our full component string as a substring should NOT match.
        // (Unlikely in practice but guards against regressions.)
        setEnabledServices("com.evil.app/com.evil.app.prefix${expected.flattenToString()}Suffix")
        assertFalse(PermissionUtils.hasAccessibilityService(context))
    }

    @Test
    fun hasAccessibility_ignoresMalformedEntries() {
        setEnabledServices("::garbage::${expected.flattenToString()}::more_garbage::")
        // With empty parts between colons, unflattenFromString returns null — safely skipped.
        // The real component still wins.
        assertTrue(PermissionUtils.hasAccessibilityService(context))
    }
}
