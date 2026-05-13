package live.agor.jetbrains.toolwindow

import live.agor.jetbrains.client.AgorBoard
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSessionStatus
import live.agor.jetbrains.client.AgorWorktree
import org.junit.Assert.assertEquals
import org.junit.Test

class AgorTreeModelBuilderTest {
    @Test
    fun `groups worktrees under boards and sessions under worktrees`() {
        val rows = AgorTreeModelBuilder().build(
            boards = listOf(AgorBoard("board-1", "Platform")),
            worktrees = listOf(AgorWorktree("wt-1", "repo-1", "board-1", "agor-jetbrains", "feature/jetbrains", "/tmp/agor")),
            sessions = listOf(
                AgorSession("session-1", "wt-1", "Agent bridge", "claude-code", AgorSessionStatus.RUNNING),
                AgorSession("session-2", "wt-1", "Navigator", "codex", AgorSessionStatus.IDLE),
            ),
        )

        assertEquals("Platform", rows[0].label)
        assertEquals("agor-jetbrains", rows[0].children[0].label)
        assertEquals(listOf("Agent bridge", "Navigator"), rows[0].children[0].children.map { it.label })
    }

    @Test
    fun `places orphan worktrees under unassigned board`() {
        val rows = AgorTreeModelBuilder().build(
            boards = emptyList(),
            worktrees = listOf(AgorWorktree("wt-1", "repo-1", null, "scratch", "main", "/tmp/scratch")),
            sessions = emptyList(),
        )

        assertEquals("Unassigned", rows.single().label)
        assertEquals("scratch", rows.single().children.single().label)
    }
}
