package live.agor.app.notifications

import live.agor.app.models.AgenticTool
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTransitionTrackerTest {
    @Test
    fun firstSnapshotDoesNotEmitAttentionOrTerminalEvents() {
        val tracker = SessionTransitionTracker()

        val events = tracker.observe(
            listOf(
                session("permission", SessionStatus.AWAITING_PERMISSION),
                session("failed", SessionStatus.FAILED),
            ),
            favorites = emptySet(),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun emitsCrossSessionEventsWhenSessionsEnterAttentionOrTerminalStates() {
        val tracker = SessionTransitionTracker()
        tracker.observe(
            listOf(
                session("permission", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z"),
                session("input", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z"),
                session("completed", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z"),
                session("failed", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z"),
            ),
            favorites = emptySet(),
        )

        val events = tracker.observe(
            listOf(
                session("permission", SessionStatus.AWAITING_PERMISSION, lastUpdated = "2026-05-11T10:00:01Z"),
                session("input", SessionStatus.AWAITING_INPUT, lastUpdated = "2026-05-11T10:00:02Z"),
                session("completed", SessionStatus.COMPLETED, lastUpdated = "2026-05-11T10:00:03Z"),
                session("failed", SessionStatus.FAILED, lastUpdated = "2026-05-11T10:00:04Z"),
            ),
            favorites = emptySet(),
        )

        assertEquals(
            listOf(
                SessionTransitionTracker.EventKind.AWAITING_PERMISSION,
                SessionTransitionTracker.EventKind.AWAITING_INPUT,
                SessionTransitionTracker.EventKind.COMPLETED,
                SessionTransitionTracker.EventKind.FAILED,
            ),
            events.map { it.kind },
        )
    }

    @Test
    fun repeatedSnapshotsDoNotDuplicateCrossSessionEvents() {
        val tracker = SessionTransitionTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING)), favorites = emptySet())

        val first = tracker.observe(listOf(session("session-1", SessionStatus.FAILED)), favorites = emptySet())
        val repeated = tracker.observe(listOf(session("session-1", SessionStatus.FAILED)), favorites = emptySet())

        assertEquals(listOf(SessionTransitionTracker.EventKind.FAILED), first.map { it.kind })
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun staleSnapshotsDoNotRearmDuplicateEvents() {
        val tracker = SessionTransitionTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:00Z")), favorites = emptySet())
        tracker.observe(listOf(session("session-1", SessionStatus.FAILED, lastUpdated = "2026-05-11T10:00:02Z")), favorites = emptySet())

        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING, lastUpdated = "2026-05-11T10:00:01Z")), favorites = emptySet())
        val duplicate = tracker.observe(listOf(session("session-1", SessionStatus.FAILED, lastUpdated = "2026-05-11T10:00:02Z")), favorites = emptySet())

        assertTrue(duplicate.isEmpty())
    }

    @Test
    fun stillEmitsFavoriteRunningToIdleNotifications() {
        val tracker = SessionTransitionTracker()
        tracker.observe(listOf(session("session-1", SessionStatus.RUNNING)), favorites = setOf("session-1"))

        val events = tracker.observe(listOf(session("session-1", SessionStatus.IDLE)), favorites = setOf("session-1"))

        assertEquals(listOf(SessionTransitionTracker.EventKind.FAVORITE_IDLE), events.map { it.kind })
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
