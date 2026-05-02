package live.agor.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import live.agor.app.models.Board
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.models.Worktree
import live.agor.app.viewmodels.NavigationViewModel

/**
 * Pre-flattened sidebar row. Built once per state-change in the ViewModel
 * (off-Main, see [NavigationViewModel.rows]) and consumed by `LazyColumn` in
 * [SidebarScreen]. A flat row list is critical for scroll perf — nested
 * `forEach` inside one `items` slot collapses the entire subtree into a single
 * composition unit and defeats LazyColumn's lazy layout.
 */
@Immutable
sealed class SidebarRow(val key: String) {
    @Immutable
    class Header(val label: String, val icon: ImageVector, key: String) : SidebarRow(key)

    @Immutable
    class HermesShortcut(val configured: Boolean) : SidebarRow("hermes-shortcut")

    @Immutable
    class DividerRow(suffix: String) : SidebarRow("div-$suffix")

    @Immutable
    class SessionItem(val session: Session, val depth: Int, keyPrefix: String) :
        SidebarRow("$keyPrefix-${session.sessionId}")

    @Immutable
    class BoardItem(val board: Board, val isOpen: Boolean) : SidebarRow("board-${board.boardId}")

    @Immutable
    class WorktreeItem(val worktree: Worktree, val isOpen: Boolean) :
        SidebarRow("worktree-${worktree.worktreeId}")
}

/**
 * Flatten a navigation snapshot into a stable, render-ready row list.
 *
 * Computes "Important" (favorites + running + ready-for-prompt) and "Needs
 * Attention" (awaiting permission/input) inline from `state` so the caller
 * doesn't have to pre-compute them. Active sessions float to the top of each
 * worktree group; idle/finished follow.
 */
fun flattenSidebarRows(
    state: NavigationViewModel.State,
    expandedBoards: Set<String>,
    expandedWorktrees: Set<String>,
    hermesConfigured: Boolean,
): List<SidebarRow> {
    val out = ArrayList<SidebarRow>(64)

    out += SidebarRow.HermesShortcut(configured = hermesConfigured)
    out += SidebarRow.DividerRow("after-hermes")

    val attention = state.sessions
        .asSequence()
        .filter { !it.isScheduled && it.status.needsAttention }
        .sortedByDescending { it.lastUpdated }
        .toList()
    if (attention.isNotEmpty()) {
        out += SidebarRow.Header("Needs Attention", Icons.Default.Notifications, "h-attention")
        for (s in attention) out += SidebarRow.SessionItem(s, depth = 1, keyPrefix = "att")
        out += SidebarRow.DividerRow("after-attention")
    }

    val important = state.sessions
        .asSequence()
        .filter {
            state.favorites.contains(it.sessionId) ||
                it.status == SessionStatus.RUNNING ||
                it.readyForPrompt == true
        }
        .sortedByDescending { it.lastUpdated }
        .take(20)
        .toList()
    if (important.isNotEmpty()) {
        out += SidebarRow.Header("Important", Icons.Default.Star, "h-important")
        for (s in important) out += SidebarRow.SessionItem(s, depth = 1, keyPrefix = "imp")
        out += SidebarRow.DividerRow("after-important")
    }

    out += SidebarRow.Header("Boards", Icons.Default.AccountTree, "h-boards")
    for (board in state.boards) {
        val boardOpen = expandedBoards.contains(board.boardId)
        out += SidebarRow.BoardItem(board, boardOpen)
        if (!boardOpen) continue

        val worktrees = state.worktreesByBoard[board.boardId].orEmpty()
        for (wt in worktrees) {
            val wtOpen = expandedWorktrees.contains(wt.worktreeId)
            out += SidebarRow.WorktreeItem(wt, wtOpen)
            if (!wtOpen) continue

            val sessions = state.sessionsByWorktree[wt.worktreeId].orEmpty()
                .filter { it.archived != true && !it.isScheduled }
                .sortedWith(
                    compareByDescending<Session> { it.status.isActive }
                        .thenByDescending { it.lastUpdated },
                )
            for (s in sessions) {
                out += SidebarRow.SessionItem(s, depth = 3, keyPrefix = "sess-${wt.worktreeId}")
            }
        }
    }

    return out
}
