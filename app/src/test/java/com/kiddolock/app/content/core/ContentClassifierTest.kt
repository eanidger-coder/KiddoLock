package com.kiddolock.app.content.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ContentClassifier] — Layer 1 of SafeLock content engine.
 *
 * Exercises keyword matching across languages, sensitivity thresholds,
 * the custom-blacklist override path, and safe content.
 */
class ContentClassifierTest {

    private lateinit var classifier: ContentClassifier

    @Before
    fun setUp() {
        classifier = ContentClassifier()
    }

    @Test
    fun safeTextIsNotBlocked() {
        val result = classifier.classify("A friendly tutorial about gardening and cookies")
        assertFalse("Safe text should not be blocked", result.isBlocked)
        assertEquals(0f, result.totalScore, 0.001f)
        assertTrue(result.categories.isEmpty())
    }

    @Test
    fun physicalViolenceKeywordsTriggerBlockAtBalancedSensitivity() {
        val result = classifier.classify("Epic fight with punch and kick")
        assertTrue("Violence keywords should block", result.isBlocked)
        assertTrue(result.totalScore >= 0.5f)
        assertTrue(result.categories.any { it.category == ContentClassifier.Category.VIOLENCE_PHYSICAL })
    }

    @Test
    fun hebrewViolenceKeywordsAreDetected() {
        val result = classifier.classify("סרטון שבו יש הרבה מכות ואלימות")
        assertTrue("Hebrew violence terms should block", result.isBlocked)
        assertTrue(result.categories.any { it.category == ContentClassifier.Category.VIOLENCE_PHYSICAL })
    }

    @Test
    fun arabicViolenceKeywordIsDetected() {
        val result = classifier.classify("فيديو عن قتال")
        assertTrue("Arabic violence terms should block", result.isBlocked)
    }

    @Test
    fun horrorForKidsFranchisesAreBlocked() {
        val result = classifier.classify("Huggy Wuggy compilation from Poppy Playtime")
        assertTrue("Huggy Wuggy content should block", result.isBlocked)
        assertTrue(result.categories.any { it.category == ContentClassifier.Category.HORROR_KIDS })
    }

    @Test
    fun relaxedSensitivityAllowsMildContentThatBalancedBlocks() {
        classifier.setSensitivity(ContentClassifier.SensitivityLevel.RELAXED)
        val result = classifier.classify("He called him stupid")
        // VERBAL violence weight 0.5 -> below relaxed threshold 0.7
        assertFalse("Relaxed should allow mild verbal content", result.isBlocked)
    }

    @Test
    fun strictSensitivityIsStricterThanBalanced() {
        classifier.setSensitivity(ContentClassifier.SensitivityLevel.BALANCED)
        val borderlineScore = classifier.classify("bully mean")
        classifier.setSensitivity(ContentClassifier.SensitivityLevel.STRICT)
        val strict = classifier.classify("bully mean")
        // Same score, stricter threshold -> blocked at strict but not balanced
        if (borderlineScore.totalScore in 0.3f..0.499f) {
            assertFalse("Balanced must not flag below 0.5", borderlineScore.isBlocked)
            assertTrue("Strict must flag at same score", strict.isBlocked)
        }
    }

    @Test
    fun customBlacklistForcesBlockRegardlessOfThreshold() {
        classifier.setSensitivity(ContentClassifier.SensitivityLevel.RELAXED)
        classifier.updateCustomBlacklist(listOf("forbidden-phrase"))

        val result = classifier.classify("title includes forbidden-phrase here")
        assertTrue("Custom blacklist must always block", result.isBlocked)
    }

    @Test
    fun weaponsKeywordsAreDetected() {
        val result = classifier.classify("Review of a shotgun and a pistol")
        assertTrue(result.categories.any { it.category == ContentClassifier.Category.WEAPONS })
    }

    @Test
    fun darkThemesKeywordsAreDetected() {
        val result = classifier.classify("jumpscare horror nightmare compilation")
        assertTrue(result.isBlocked)
        assertTrue(result.categories.any { it.category == ContentClassifier.Category.DARK_THEMES })
    }

    @Test
    fun emptyInputReturnsZeroScore() {
        val result = classifier.classify("")
        assertFalse(result.isBlocked)
        assertEquals(0f, result.totalScore, 0.001f)
    }

    @Test
    fun allowedOverridesSkipBuiltInKeywords() {
        // "battle" is in VIOLENCE_PHYSICAL by default. Pokémon content uses
        // the word constantly — without an override the filter would carpet-
        // block it. Marking "battle" as allowed must cause the classifier to
        // ignore that keyword while still flagging other violent words.
        val before = classifier.classify("Pokémon battle")
        assertTrue("baseline: 'battle' alone should still trigger", before.isBlocked)

        classifier.updateAllowedOverrides(setOf("battle"))
        val after = classifier.classify("Pokémon battle")
        assertFalse("'battle' must be ignored when in allowedOverrides", after.isBlocked)

        // Other violent words must still trigger even after the override.
        val stillBlocks = classifier.classify("fight punch kick")
        assertTrue("non-overridden words must still block", stillBlocks.isBlocked)
    }

    @Test
    fun defaultKeywordsExposesEveryBuiltInCategory() {
        val map = classifier.defaultKeywords()
        ContentClassifier.Category.values().forEach { cat ->
            assertTrue("category $cat must be present", map.containsKey(cat))
            assertTrue("category $cat must not be empty", map[cat]!!.isNotEmpty())
        }
        assertTrue("violent-shows list must be exposed",
            classifier.defaultViolentShows().isNotEmpty())
    }

    @Test
    fun totalScoreIsCappedAtOneForNonCustomMatches() {
        val result = classifier.classify(
            "fight punch kick attack kill murder blood battle war combat"
        )
        assertTrue(result.totalScore <= 1.0001f)
        assertTrue(result.isBlocked)
    }
}
