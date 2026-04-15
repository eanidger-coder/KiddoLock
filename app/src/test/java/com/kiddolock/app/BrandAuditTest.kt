package com.kiddolock.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * QA guardrail: the user-facing brand MUST be "SafeLock" everywhere.
 *
 * Background: during the SafeKids + KiddoLock merge, a previous build still
 * said "KiddoLock" on several screens (Hebrew strings + hardcoded layout
 * strings). The user reported it; we fixed it; this test prevents a
 * regression from creeping back in.
 *
 * Rules:
 *  - strings.xml (he and default) must contain ZERO occurrences of KiddoLock.
 *  - layouts/ must contain ZERO user-visible "KiddoLock" strings
 *    (android:text= / android:hint= / tools:text=).
 *  - `Theme.KiddoLock` in themes.xml is allowed (style alias, never shown).
 */
class BrandAuditTest {

    private val resDir: File by lazy {
        // Gradle runs unit tests from the module dir (app/)
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate res/ directory. cwd=${File(".").absolutePath}")
    }

    @Test
    fun strings_xml_has_no_KiddoLock_references() {
        val stringsFiles = listOf(
            File(resDir, "values/strings.xml"),
            File(resDir, "values/safelock_strings.xml"),
            File(resDir, "values-he/strings.xml")
        ).filter { it.exists() }

        assertTrue("Expected at least one strings.xml", stringsFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        for (f in stringsFiles) {
            f.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    // Case-insensitive — catches "KiddoLock", "KIDDOLOCK", "kiddolock" etc.
                    // but allows the package name `com.kiddolock.app` (only in code, not strings).
                    if (line.contains("KiddoLock", ignoreCase = true)) {
                        violations += "${f.path}:${idx + 1}: $line"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} stray KiddoLock references in user-visible strings:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    @Test
    fun layouts_have_no_hardcoded_KiddoLock_text() {
        val layoutsDir = File(resDir, "layout")
        if (!layoutsDir.isDirectory) return // nothing to check

        val violations = mutableListOf<String>()
        // Match android:text / android:hint / android:title / tools:text / contentDescription
        // with a KiddoLock substring.
        val textAttr = Regex(
            """(android|tools):(text|hint|title|contentDescription)\s*=\s*"[^"]*KiddoLock[^"]*"""",
            RegexOption.IGNORE_CASE
        )
        layoutsDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (textAttr.containsMatchIn(line)) {
                            violations += "${f.path}:${idx + 1}: ${line.trim()}"
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Found hardcoded KiddoLock text in layouts (must be SafeLock):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    @Test
    fun kotlin_source_has_no_userVisible_KiddoLock_literals() {
        // Scan all Kotlin source for string literals containing "KiddoLock".
        // User-visible strings typically contain Hebrew characters, a space,
        // a colon, or an emoji — flag those. Pure-ASCII identifiers used for
        // internal WorkManager unique names + Log tags are OK and stay on
        // the allow-list (changing them would orphan scheduled work on
        // upgraded installs).
        //
        // This guards the leaks we fixed in NotificationUtils, HelpActivity,
        // KiddoDeviceAdminReceiver and AdminPinActivity email subject — all
        // were displayed to the user every day.
        val srcDir = File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java").takeIf { it.isDirectory }
            ?: error("Could not locate src/main/java. cwd=${File(".").absolutePath}")

        // Anything with Hebrew char, whitespace, colon, or bang is user-visible.
        // Pure ASCII identifiers (KiddoLock_WeeklyReport, KiddoLockLogger, KiddoLockHeart)
        // are internal and allowed.
        val hebrewOrDisplay = Regex("""[\u0590-\u05FF\s:!]""")
        // Match anything that looks like a Kotlin/Java string literal with KiddoLock inside.
        val literal = Regex(""""([^"\n]*KiddoLock[^"\n]*)"""")

        val violations = mutableListOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        // Skip comments and log statements (internal, not user-visible)
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (Regex("""\bLog\.[diewv]\b""").containsMatchIn(line)) return@forEachIndexed
                        literal.findAll(line).forEach { m ->
                            val inner = m.groupValues[1]
                            if (hebrewOrDisplay.containsMatchIn(inner)) {
                                violations += "${f.path}:${idx + 1}: $inner"
                            }
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} user-visible KiddoLock string literal(s) in Kotlin source. " +
                    "Rename to SafeLock:\n" + violations.joinToString("\n")
            )
        }
    }

    @Test
    fun app_name_is_SafeLock() {
        val main = File(resDir, "values/strings.xml")
        val he = File(resDir, "values-he/strings.xml")
        val appNameRegex = Regex("""<string\s+name="app_name"\s*>\s*SafeLock\s*</string>""")

        for (f in listOf(main, he).filter { it.exists() }) {
            assertTrue(
                "app_name must be 'SafeLock' in ${f.path}",
                appNameRegex.containsMatchIn(f.readText())
            )
        }
    }
}
