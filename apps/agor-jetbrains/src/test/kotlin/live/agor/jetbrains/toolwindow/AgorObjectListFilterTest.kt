package live.agor.jetbrains.toolwindow

import live.agor.jetbrains.client.AgorBoard
import live.agor.jetbrains.client.AgorPermissionRequest
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSessionStatus
import live.agor.jetbrains.client.AgorSnapshot
import live.agor.jetbrains.client.AgorWorktree
import org.junit.Assert.assertEquals
import org.junit.Test

class AgorObjectListFilterTest {
    private val snapshot = AgorSnapshot(
        boards = listOf(AgorBoard("board-1", "Platform")),
        worktrees = listOf(
            AgorWorktree("wt-1", "repo-1", "board-1", "agor-jetbrains", "feature/shell", "/tmp/agor"),
            AgorWorktree("wt-2", "repo-1", "board-1", "archived-ui", "main", "/tmp/old"),
        ),
        sessions = listOf(
            AgorSession("sess-1", "wt-1", "Connected shell", "codex", AgorSessionStatus.RUNNING),
            AgorSession("sess-2", "wt-2", "Quiet session", "claude-code", AgorSessionStatus.IDLE),
        ),
        permissionRequests = listOf(
            AgorPermissionRequest("msg-1", "sess-2", null, "req-1", "Edit", "{}"),
        ),
    )

    @Test
    fun `query keeps matching sessions and their parent worktrees`() {
        val filtered = AgorObjectListFilter.apply(snapshot, "connected", AgorObjectFilterMode.ALL)

        assertEquals(listOf("sess-1"), filtered.sessions.map { it.sessionId })
        assertEquals(listOf("wt-1"), filtered.worktrees.map { it.worktreeId })
    }

    @Test
    fun `query keeps matching boards and their child worktrees`() {
        val filtered = AgorObjectListFilter.apply(snapshot, "platform", AgorObjectFilterMode.ALL)

        assertEquals(listOf("board-1"), filtered.boards.map { it.boardId })
        assertEquals(listOf("wt-1", "wt-2"), filtered.worktrees.map { it.worktreeId })
    }

    @Test
    fun `active filter keeps running and queued sessions only`() {
        val filtered = AgorObjectListFilter.apply(snapshot, "", AgorObjectFilterMode.ACTIVE)

        assertEquals(listOf("sess-1"), filtered.sessions.map { it.sessionId })
        assertEquals(listOf("wt-1"), filtered.worktrees.map { it.worktreeId })
    }

    @Test
    fun `permission filter keeps sessions with pending permission requests`() {
        val filtered = AgorObjectListFilter.apply(snapshot, "", AgorObjectFilterMode.PERMISSIONS)

        assertEquals(listOf("sess-2"), filtered.sessions.map { it.sessionId })
        assertEquals(listOf("wt-2"), filtered.worktrees.map { it.worktreeId })
    }
}
