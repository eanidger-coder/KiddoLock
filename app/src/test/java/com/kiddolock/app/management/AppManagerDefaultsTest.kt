package com.kiddolock.app.management

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the sensitive-app default blocklist against regressions.
 *
 * The original bug: migrations V5..V10 wrote partial lists to prefs on
 * fresh install BEFORE the DEFAULT_BLACKLIST seeder ran, causing social,
 * dating, AI-chat, and payment apps to silently stay unblocked for new
 * users. This test proves the fresh-install path now seeds the full list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppManagerDefaultsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Scrub any prior test's prefs so every test gets a fresh install.
        context.getSharedPreferences("kiddolock_app_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("kiddolock_app_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun freshInstall_blocksKeyCategories_thatUserAskedAbout() {
        val mgr = AppManager(context)
        mgr.initialize()

        // The three categories the parent explicitly flagged: Google app,
        // Google Search (same package), and device Settings.
        assertTrue(
            "Google Search / Google app must be blocked by default",
            mgr.isBlacklisted("com.google.android.googlequicksearchbox"),
        )
        assertTrue(
            "Android Settings must be blocked by default",
            mgr.isBlacklisted("com.android.settings"),
        )
        assertTrue(
            "Samsung Settings must be blocked by default",
            mgr.isBlacklisted("com.samsung.android.settings"),
        )
        assertTrue(
            "Google Play Store must be blocked by default",
            mgr.isBlacklisted("com.android.vending"),
        )
    }

    @Test
    fun freshInstall_blocksSocialAndDating_whichMigrationsAloneMissed() {
        // These packages are in DEFAULT_BLACKLIST but NOT in any of the
        // V5-V10 migration lists. Before the fresh-install fix they would
        // silently fail to be blocked on new installs.
        val mgr = AppManager(context)
        mgr.initialize()

        listOf(
            "com.facebook.katana",        // Facebook
            "com.instagram.android",       // Instagram
            "com.zhiliaoapp.musically",    // TikTok
            "com.snapchat.android",        // Snapchat
            "com.reddit.frontpage",        // Reddit
            "com.tinder",                  // Tinder
            "com.bumble.app",              // Bumble
            "com.grindr.android",          // Grindr
        ).forEach { pkg ->
            assertTrue("$pkg must be blocked by default on fresh install",
                mgr.isBlacklisted(pkg))
        }
    }

    @Test
    fun freshInstall_blocksAIandPaymentsAndFileManagers() {
        // V11 additions — must be present on fresh install via
        // DEFAULT_BLACKLIST seed, and via V11 migration for upgraders.
        val mgr = AppManager(context)
        mgr.initialize()

        listOf(
            "com.openai.chatgpt",                 // ChatGPT
            "com.google.android.apps.bard",        // Gemini
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.coinbase.android",                // Coinbase
            "com.discord",                         // Discord
            "com.android.documentsui",             // Files (sideload risk)
        ).forEach { pkg ->
            assertTrue("$pkg must be blocked by default on fresh install",
                mgr.isBlacklisted(pkg))
        }
    }

    @Test
    fun freshInstall_doesNotBlockCoreSystem() {
        // Guard against over-blocking. Dialer, SystemUI, the app itself must
        // never be blocked by default or parents + children alike will get
        // locked out.
        val mgr = AppManager(context)
        mgr.initialize()

        listOf(
            "com.android.systemui",
            "com.android.phone",
            "com.google.android.dialer",
            "com.kiddolock.app",
        ).forEach { pkg ->
            assertFalse("$pkg must never be blocked by default",
                mgr.isBlacklisted(pkg))
        }
    }

    @Test
    fun upgradingUser_gainsV11Additions_withoutLosingCustomizations() {
        // Simulate an existing V10 install with a parent customization:
        // parent un-blocked Chrome (unlikely but exercises the preservation
        // path) and added a custom block.
        val prefs = context.getSharedPreferences("kiddolock_app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("blacklist_version", 10)
            .putStringSet(
                "blacklisted_apps",
                setOf(
                    "com.instagram.android",    // default kept
                    "com.parents.custom.block", // parent addition preserved
                    // NB: chrome deliberately absent to simulate an unblock
                ),
            )
            .apply()

        val mgr = AppManager(context)
        mgr.initialize()

        // Parent's choices preserved
        assertTrue("parent custom block kept", mgr.isBlacklisted("com.parents.custom.block"))
        assertFalse("parent's un-block of Chrome kept",
            mgr.isBlacklisted("com.android.chrome"))

        // V11 additions picked up
        assertTrue("ChatGPT picked up by V11",
            mgr.isBlacklisted("com.openai.chatgpt"))
        assertTrue("Google Wallet picked up by V11",
            mgr.isBlacklisted("com.google.android.apps.walletnfcrel"))
    }
}
