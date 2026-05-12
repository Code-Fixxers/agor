package live.agor.app.ui.nav

import live.agor.app.models.AgenticTool
import live.agor.app.models.Board
import live.agor.app.models.Repo
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.models.Worktree
import live.agor.app.viewmodels.NavigationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SidebarRowFlattenerTest {
    @Test
    fun importantSectionIncludesFavoritesRunningReadyAndRecentSessions() {
        val favorite = session("favorite", SessionStatus.IDLE, readyForPrompt = false, lastUpdated = "2026-05-07T10:00:00Z")
        val running = session("running", SessionStatus.RUNNING, readyForPrompt = false, lastUpdated = "2026-05-07T11:00:00Z")
        val ready = session("ready", SessionStatus.IDLE, readyForPrompt = true, lastUpdated = "2026-05-07T12:00:00Z")
        val recent1 = session("recent-1", SessionStatus.IDLE, readyForPrompt = false, lastUpdated = "2026-05-07T09:00:00Z")
        val recent2 = session("recent-2", SessionStatus.COMPLETED, readyForPrompt = false, lastUpdated = "2026-05-07T08:00:00Z")
        val recent3 = session("recent-3", SessionStatus.FAILED, readyForPrompt = false, lastUpdated = "2026-05-07T07:00:00Z")
        val older = session("older", SessionStatus.IDLE, readyForPrompt = false, lastUpdated = "2026-05-07T06:00:00Z")
        val state = NavigationViewModel.State(
            sessions = listOf(favorite, running, ready, recent1, recent2, recent3, older),
            favorites = setOf(favorite.sessionId),
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

        assertEquals(
            listOf(
                ready.sessionId,
                running.sessionId,
                favorite.sessionId,
                recent1.sessionId,
                recent2.sessionId,
                recent3.sessionId,
            ),
            importantRows.map { it.sessionId },
        )
        assertFalse(importantRows.any { it.sessionId == older.sessionId })
    }

    @Test
    fun importantSectionExcludesAttentionAndUntitledRecentSessionsUnlessFavorite() {
        val attention = session(
            "attention",
            SessionStatus.AWAITING_PERMISSION,
            readyForPrompt = false,
            lastUpdated = "2026-05-07T12:00:00Z",
        )
        val untitledRecent = session(
            "untitled-recent",
            SessionStatus.IDLE,
            readyForPrompt = false,
            lastUpdated = "2026-05-07T11:00:00Z",
            title = null,
        )
        val untitledFavorite = session(
            "untitled-favorite",
            SessionStatus.IDLE,
            readyForPrompt = false,
            lastUpdated = "2026-05-07T10:00:00Z",
            title = null,
        )
        val recent = session("recent", SessionStatus.IDLE, readyForPrompt = false, lastUpdated = "2026-05-07T09:00:00Z")
        val state = NavigationViewModel.State(
            sessions = listOf(attention, untitledRecent, untitledFavorite, recent),
            favorites = setOf(untitledFavorite.sessionId),
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

        assertEquals(
            listOf(untitledFavorite.sessionId, recent.sessionId),
            importantRows.map { it.sessionId },
        )
        assertFalse(importantRows.any { it.sessionId == attention.sessionId })
        assertFalse(importantRows.any { it.sessionId == untitledRecent.sessionId })
    }

    @Test
    fun searchQueryShowsMatchingSessionsWithoutNeedingExpandedBoards() {
        val matching = session(
            "matching",
            SessionStatus.IDLE,
            readyForPrompt = false,
            lastUpdated = "2026-05-07T12:00:00Z",
            title = "Implement Android drawer search",
        )
        val other = session(
            "other",
            SessionStatus.IDLE,
            readyForPrompt = false,
            lastUpdated = "2026-05-07T11:00:00Z",
            title = "Hermes voice polishing",
        )
        val state = NavigationViewModel.State(
            sessions = listOf(matching, other),
            searchQuery = "drawer",
        )

        val rows = SidebarRowFlattener().flatten(
            state = state,
            expandedBoards = emptySet(),
            expandedWorktrees = emptySet(),
            hermesConfigured = false,
        )
        val searchRows = rows
            .dropWhile { it.key != "h-search" }
            .drop(1)
            .takeWhile { it.key != "div-after-search" }
            .filterIsInstance<SidebarRow.SessionItem>()

        assertEquals(listOf(matching.sessionId), searchRows.map { it.sessionId })
    }

    @Test
    fun worktreeRowsIncludeResolvedRepoNameWhenAvailable() {
        val state = NavigationViewModel.State(
            boards = listOf(Board(boardId = "board", name = "Main")),
            worktreesByBoard = mapOf(
                "board" to listOf(
                    Worktree(
                        worktreeId = "wt-1",
                        repoId = "repo-1",
                        boardId = "board",
                        name = "Android client",
                        branch = "cfx/android-hermes-client",
                    ),
                ),
            ),
            reposById = mapOf("repo-1" to Repo(repoId = "repo-1", name = "agor")),
        )

        val rows = SidebarRowFlattener().flatten(
            state = state,
            expandedBoards = setOf("board"),
            expandedWorktrees = emptySet(),
            hermesConfigured = false,
        )
        val worktree = rows.filterIsInstance<SidebarRow.WorktreeItem>().single()

        assertEquals("agor", worktree.repoName)
        assertEquals("cfx/android-hermes-client", worktree.branch)
    }

    private fun session(
        id: String,
        status: SessionStatus,
        readyForPrompt: Boolean,
        lastUpdated: String,
        title: String? = id,
    ): Session = Session(
        sessionId = id,
        agenticTool = AgenticTool.CODEX,
        status = status,
        createdAt = "2026-05-07T09:00:00Z",
        lastUpdated = lastUpdated,
        createdBy = "user",
        worktreeId = "worktree",
        readyForPrompt = readyForPrompt,
        title = title,
    )
}
