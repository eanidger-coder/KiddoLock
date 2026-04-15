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
