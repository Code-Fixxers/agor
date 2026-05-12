package live.agor.app.notifications

import live.agor.app.models.AgenticTool
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdleNotificationTrackerTest {
    @Test
    fun firstIdleObservationDoesNotNotify() {
        val tracker = SessionIdleNotificationTracker()

        val notifications = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = setOf("session-1"))

        assertTrue(notifications.isEmpty())
    }

    @Test
    fun runningToIdleNotifiesExactlyOnceAcrossRepeatedIdleSnapshots() {
        val tracker = SessionIdleNotificationTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING)), favorites = setOf("session-1"))

        val firstIdle = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = setOf("session-1"))
        val repeatedIdle = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = setOf("session-1"))

        assertEquals(listOf("session-1"), firstIdle.map { it.session.sessionId })
        assertTrue(repeatedIdle.isEmpty())
    }

    @Test
    fun staleRunningAfterNotifiedIdleDoesNotArmDuplicateNotification() {
        val tracker = SessionIdleNotificationTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z")), favorites = setOf("session-1"))
        tracker.observe(listOf(session("session-1", SessionStatus.IDLE, lastUpdated = "2026-05-11T10:00:01Z")), favorites = setOf("session-1"))

        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z")), favorites = setOf("session-1"))
        val duplicateIdle = tracker.observe(
            listOf(session("session-1", SessionStatus.IDLE, lastUpdated = "2026-05-11T10:00:01Z")),
            favorites = setOf("session-1"),
        )

        assertTrue(duplicateIdle.isEmpty())
    }

    @Test
    fun newerRunningAfterNotifiedIdleRearmsNextIdleNotification() {
        val tracker = SessionIdleNotificationTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z")), favorites = setOf("session-1"))
        tracker.observe(listOf(session("session-1", SessionStatus.IDLE, lastUpdated = "2026-05-11T10:00:01Z")), favorites = setOf("session-1"))

        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:02Z")), favorites = setOf("session-1"))
        val nextIdle = tracker.observe(
            listOf(session("session-1", SessionStatus.IDLE, lastUpdated = "2026-05-11T10:00:03Z")),
            favorites = setOf("session-1"),
        )

        assertEquals(listOf("session-1"), nextIdle.map { it.session.sessionId })
    }

    @Test
    fun nonFavoriteTransitionIsObservedWithoutNotifying() {
        val tracker = SessionIdleNotificationTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING)), favorites = emptySet())

        val idleWhileNotFavorite = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = emptySet())
        val repeatedIdleAfterFavorite = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = setOf("session-1"))

        assertTrue(idleWhileNotFavorite.isEmpty())
        assertTrue(repeatedIdleAfterFavorite.isEmpty())
    }

    private fun session(
        id: String,
        status: SessionStatus,
        lastUpdated: String = "2026-05-11T09:00:00Z",
    ): Session = Session(
        sessionId = id,
        agenticTool = AgenticTool.CODEX,
        status = status,
        createdAt = "2026-05-11T08:00:00Z",
        lastUpdated = lastUpdated,
        createdBy = "user",
        worktreeId = "worktree",
        title = id,
    )
}
