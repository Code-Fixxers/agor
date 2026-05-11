package live.agor.app.ui.nav

import live.agor.app.models.AgenticTool
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.viewmodels.NavigationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SidebarRowFlattenerTest {
    @Test
    fun importantSectionContainsOnlyStarredSessions() {
        val starred = session("starred", SessionStatus.IDLE, readyForPrompt = false, lastUpdated = "2026-05-07T10:00:00Z")
        val running = session("running", SessionStatus.RUNNING, readyForPrompt = false, lastUpdated = "2026-05-07T11:00:00Z")
        val promptable = session("promptable", SessionStatus.IDLE, readyForPrompt = true, lastUpdated = "2026-05-07T12:00:00Z")
        val state = NavigationViewModel.State(
            sessions = listOf(starred, running, promptable),
            favorites = setOf(starred.sessionId),
        )

        val rows = SidebarRowFlattener().flatten(
            state = state,
            expandedBoards = emptySet(),
            expandedWorktrees = emptySet(),
            hermesConfigured = false,
        )
        val importantRows = rows
            .dropWhile { it.key != "h-important" }
            .drop(1)
            .takeWhile { it.key != "div-after-important" }
            .filterIsInstance<SidebarRow.SessionItem>()

        assertEquals(listOf(starred.sessionId), importantRows.map { it.sessionId })
        assertFalse(importantRows.any { it.sessionId == running.sessionId })
        assertFalse(importantRows.any { it.sessionId == promptable.sessionId })
    }

    private fun session(
        id: String,
        status: SessionStatus,
        readyForPrompt: Boolean,
        lastUpdated: String,
    ): Session = Session(
        sessionId = id,
        agenticTool = AgenticTool.CODEX,
        status = status,
        createdAt = "2026-05-07T09:00:00Z",
        lastUpdated = lastUpdated,
        createdBy = "user",
        worktreeId = "worktree",
        readyForPrompt = readyForPrompt,
        title = id,
    )
}
