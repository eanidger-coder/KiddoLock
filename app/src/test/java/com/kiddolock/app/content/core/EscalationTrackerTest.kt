package com.kiddolock.app.content.core

import com.kiddolock.app.content.dao.SessionDao
import com.kiddolock.app.content.entities.ViewingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EscalationTracker] — Layer 2 of SafeLock content engine.
 *
 * Uses an in-memory fake SessionDao so we can verify gradient and trend
 * logic without Room. The fake's signatures MUST track the real DAO.
 */
class EscalationTrackerTest {

    private lateinit var fakeDao: FakeSessionDao
    private lateinit var tracker: EscalationTracker

    private val safe = ContentClassifier.ContentScore(0.1f, emptyList(), false)
    private val mild = ContentClassifier.ContentScore(0.35f, emptyList(), false)
    private val bad  = ContentClassifier.ContentScore(0.75f, emptyList(), true)

    @Before
    fun setUp() {
        fakeDao = FakeSessionDao()
        tracker = EscalationTracker(fakeDao)
    }

    @Test
    fun firstVideoReturnsSafeTrend() = runTest {
        val result = tracker.recordAndAnalyze("v1", "c1", safe)
        assertEquals(EscalationTracker.Trend.SAFE, result.trend)
        assertFalse(result.isEscalating)
        assertEquals(1, result.videoCount)
    }

    @Test
    fun flatSafeScoresStaySafe() = runTest {
        repeat(5) { tracker.recordAndAnalyze("v$it", "c", safe) }
        val last = tracker.recordAndAnalyze("v-last", "c", safe)
        assertEquals(EscalationTracker.Trend.SAFE, last.trend)
        assertFalse(last.isEscalating)
    }

    @Test
    fun risingSequenceFlagsEscalatingOrCritical() = runTest {
        val rising = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        var last: EscalationTracker.EscalationResult? = null
        rising.forEach { s ->
            last = tracker.recordAndAnalyze(
                "v",
                "c",
                ContentClassifier.ContentScore(s, emptyList(), s >= 0.5f)
            )
        }
        val t = last!!.trend
        assertTrue(
            "Strongly rising scores must be ESCALATING or CRITICAL (got $t)",
            t == EscalationTracker.Trend.ESCALATING || t == EscalationTracker.Trend.CRITICAL
        )
        assertTrue(last!!.isEscalating)
        assertTrue("Gradient should be positive", last!!.gradient > 0f)
    }

    @Test
    fun gradualUnfinishedRiseIsNotEscalating() = runTest {
        // Slight upward drift but session average stays low.
        val drift = listOf(0.05f, 0.08f, 0.12f, 0.15f, 0.18f)
        var last: EscalationTracker.EscalationResult? = null
        drift.forEach { s ->
            last = tracker.recordAndAnalyze(
                "v",
                "c",
                ContentClassifier.ContentScore(s, emptyList(), false)
            )
        }
        assertFalse(last!!.isEscalating)
    }

    @Test
    fun daoReceivesOneRowPerRecordedVideo() = runTest {
        tracker.recordAndAnalyze("a", "chA", safe)
        tracker.recordAndAnalyze("b", "chB", mild)
        tracker.recordAndAnalyze("c", "chC", bad)
        assertEquals(3, fakeDao.rows.size)
        assertEquals("a", fakeDao.rows[0].videoTitle)
        assertEquals("chC", fakeDao.rows[2].channelName)
    }

    @Test
    fun startNewSessionResetsScoresAndSessionId() = runTest {
        val sessionA = tracker.getCurrentSessionId()
        tracker.recordAndAnalyze("v", "c", bad)
        tracker.recordAndAnalyze("v", "c", bad)

        tracker.startNewSession()
        val sessionB = tracker.getCurrentSessionId()
        assertNotEquals("Session id must change", sessionA, sessionB)

        val first = tracker.recordAndAnalyze("v", "c", safe)
        assertEquals("Score list must be reset", 1, first.videoCount)
        assertEquals(EscalationTracker.Trend.SAFE, first.trend)
    }

    /**
     * Fake DAO matching com.kiddolock.app.content.dao.SessionDao exactly.
     * If the real interface changes, this will fail to compile — that's
     * intentional: it forces a test review.
     */
    private class FakeSessionDao : SessionDao {
        val rows = mutableListOf<ViewingSession>()

        override suspend fun getSessionVideos(sessionId: String): List<ViewingSession> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }

        override suspend fun getRecentVideos(limit: Int): List<ViewingSession> =
            rows.sortedByDescending { it.timestamp }.take(limit)

        override fun getAllSessions(): Flow<List<ViewingSession>> =
            flowOf(rows.sortedByDescending { it.timestamp })

        override suspend fun insert(session: ViewingSession) {
            rows.add(session)
        }

        override suspend fun deleteOlderThan(before: Long) {
            rows.removeAll { it.timestamp < before }
        }
    }
}
