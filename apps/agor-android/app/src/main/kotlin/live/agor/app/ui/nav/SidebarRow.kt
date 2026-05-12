package live.agor.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import live.agor.app.models.AgenticTool
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.viewmodels.NavigationViewModel

/**
 * Pre-flattened sidebar row. Built once per state-change in the ViewModel
 * (off-Main, see [NavigationViewModel.rows]) and consumed by `LazyColumn` in
 * [SidebarScreen]. A flat row list is critical for scroll perf — nested
 * `forEach` inside one `items` slot collapses the entire subtree into a single
 * composition unit and defeats LazyColumn's lazy layout.
 *
 * Every variant is a `data class` so Compose smart-skip can compare rows by
 * value and skip recompositions when nothing changed. With plain `class`,
 * `equals` falls back to identity and the entire visible sidebar would
 * recompose on every socket patch — see ChatRow's docstring for the same
 * issue we hit there.
 */
@Immutable
sealed interface SidebarRow {
    val key: String

    @Immutable
    data class Header(val label: String, val icon: ImageVector, override val key: String) : SidebarRow

    @Immutable
    data class HermesShortcut(val configured: Boolean) : SidebarRow {
        override val key: String = "hermes-shortcut"
    }

    @Immutable
    data class DividerRow(val suffix: String) : SidebarRow {
        override val key: String get() = "div-$suffix"
    }

    @Immutable
    data class SessionItem(
        val sessionId: String,
        val title: String,
        val agenticTool: AgenticTool,
        val status: SessionStatus,
        val favorite: Boolean,
        val depth: Int,
        val keyPrefix: String,
    ) : SidebarRow {
        override val key: String get() = "$keyPrefix-$sessionId"
    }

    @Immutable
    data class HermesSessionItem(
        val sessionId: String,
        val title: String,
        val active: Boolean,
    ) : SidebarRow {
        override val key: String get() = "hermes-session-$sessionId"
    }

    @Immutable
    data class BoardItem(
        val boardId: String,
        val name: String,
        val emoji: String?,
        val isOpen: Boolean,
    ) : SidebarRow {
        override val key: String get() = "board-$boardId"
    }

    @Immutable
    data class WorktreeItem(
        val worktreeId: String,
        val name: String,
        val branch: String?,
        val repoName: String?,
        val isOpen: Boolean,
    ) : SidebarRow {
        override val key: String get() = "worktree-$worktreeId"
    }
}

/**
 * Flatten a navigation snapshot into a stable, render-ready row list.
 *
 * Computes "Important" (favorites, running, ready-for-prompt, and recent
 * titled sessions) and "Needs Attention" (awaiting permission/input) inline
 * from `state` so the caller doesn't have to
 * pre-compute them. Active sessions float to the top of each worktree group;
 * idle/finished follow.
 */
fun flattenSidebarRows(
    state: NavigationViewModel.State,
    expandedBoards: Set<String>,
    expandedWorktrees: Set<String>,
    hermesConfigured: Boolean,
): List<SidebarRow> {
    return SidebarRowFlattener().flatten(state, expandedBoards, expandedWorktrees, hermesConfigured)
}

/**
 * Stateful sidebar row builder. NavigationViewModel owns one instance so rows
 * whose rendered fields did not change keep the same object identity across
 * socket patches, polling refreshes, and expand/collapse toggles.
 */
class SidebarRowFlattener {
    private val rowsByKey = HashMap<String, SidebarRow>()

    fun flatten(
        state: NavigationViewModel.State,
        expandedBoards: Set<String>,
        expandedWorktrees: Set<String>,
        hermesConfigured: Boolean,
    ): List<SidebarRow> {
        val out = ArrayList<SidebarRow>(64)
        val seenKeys = HashSet<String>(64)

        fun add(row: SidebarRow) {
            val stable = rowsByKey[row.key]?.takeIf { it == row } ?: row
            rowsByKey[stable.key] = stable
            seenKeys += stable.key
            out += stable
        }

        add(SidebarRow.HermesShortcut(configured = hermesConfigured))
        if (state.hermesSessions.isNotEmpty()) {
            add(SidebarRow.Header("Hermes Chats", Icons.Default.AutoAwesome, "h-hermes-chats"))
            for (session in state.hermesSessions) {
                add(
                    SidebarRow.HermesSessionItem(
                        sessionId = session.id,
                        title = session.title,
                        active = session.active,
                    ),
                )
            }
        }
        add(SidebarRow.DividerRow("after-hermes"))

        val searchQuery = state.searchQuery.trim()
        if (searchQuery.isNotEmpty()) {
            val searchResults = searchSessions(state.sessions, searchQuery)
            add(SidebarRow.Header("Search Results", Icons.Default.Search, "h-search"))
            for (s in searchResults) add(s.toSessionRow(state.favorites, depth = 1, keyPrefix = "search"))
            add(SidebarRow.DividerRow("after-search"))
            rowsByKey.keys.removeAll { it !in seenKeys }
            return out
        }

        val attention = state.sessions
            .asSequence()
            .filter { !it.isScheduled && it.status.needsAttention }
            .sortedByDescending { it.lastUpdated }
            .toList()
        if (attention.isNotEmpty()) {
            add(SidebarRow.Header("Needs Attention", Icons.Default.Notifications, "h-attention"))
            for (s in attention) add(s.toSessionRow(state.favorites, depth = 1, keyPrefix = "att"))
            add(SidebarRow.DividerRow("after-attention"))
        }

        val attentionIds = attention.asSequence()
            .map { it.sessionId }
            .toHashSet()
        val important = importantSessions(state.sessions, state.favorites, attentionIds)
        if (important.isNotEmpty()) {
            add(SidebarRow.Header("Important", Icons.Default.Star, "h-important"))
            for (s in important) add(s.toSessionRow(state.favorites, depth = 1, keyPrefix = "imp"))
            add(SidebarRow.DividerRow("after-important"))
        }

        add(SidebarRow.Header("Boards", Icons.Default.AccountTree, "h-boards"))
        for (board in state.boards) {
            val boardOpen = expandedBoards.contains(board.boardId)
            add(
                SidebarRow.BoardItem(
                    boardId = board.boardId,
                    name = board.name,
                    emoji = board.emoji,
                    isOpen = boardOpen,
                ),
            )
            if (!boardOpen) continue

            val worktrees = state.worktreesByBoard[board.boardId].orEmpty()
            for (wt in worktrees) {
                val wtOpen = expandedWorktrees.contains(wt.worktreeId)
                add(
                    SidebarRow.WorktreeItem(
                        worktreeId = wt.worktreeId,
                        name = wt.name,
                        branch = wt.branch,
                        repoName = state.reposById[wt.repoId]?.name,
                        isOpen = wtOpen,
                    ),
                )
                if (!wtOpen) continue

                val sessions = state.sessionsByWorktree[wt.worktreeId].orEmpty()
                    .filter { (state.showArchived || it.archived != true) && !it.isScheduled }
                    .sortedWith(
                        compareByDescending<Session> { it.status.isActive }
                            .thenByDescending { it.lastUpdated },
                    )
                for (s in sessions) {
                    add(
                        s.toSessionRow(
                            favorites = state.favorites,
                            depth = 3,
                            keyPrefix = "sess-${wt.worktreeId}",
                        ),
                    )
                }
            }
        }

        rowsByKey.keys.removeAll { it !in seenKeys }
        return out
    }

    private fun importantSessions(
        sessions: List<Session>,
        favorites: Set<String>,
        attentionIds: Set<String>,
    ): List<Session> {
        val sessionsById = sessions.associateBy { it.sessionId }
        val selectedIds = LinkedHashSet<String>()

        sessions
            .asSequence()
            .filter { it.sessionId in favorites }
            .filter { it.sessionId !in attentionIds }
            .filter { !it.isScheduled }
            .sortedByDescending { it.lastUpdated }
            .forEach { selectedIds += it.sessionId }

        sessions
            .asSequence()
            .filter { it.status == SessionStatus.RUNNING || it.readyForPrompt == true }
            .filter { it.sessionId !in attentionIds }
            .filter { !it.isScheduled }
            .filter { it.hasExplicitTitle || it.sessionId in favorites }
            .sortedByDescending { it.lastUpdated }
            .forEach { selectedIds += it.sessionId }

        sessions
            .asSequence()
            .filter { it.sessionId !in attentionIds }
            .filter { it.sessionId !in selectedIds }
            .filter { !it.isScheduled }
            .filter { it.hasExplicitTitle || it.sessionId in favorites }
            .sortedByDescending { it.lastUpdated }
            .take(3)
            .forEach { selectedIds += it.sessionId }

        return selectedIds
            .asSequence()
            .mapNotNull { sessionsById[it] }
            .sortedByDescending { it.lastUpdated }
            .take(20)
            .toList()
    }

    private fun searchSessions(sessions: List<Session>, query: String): List<Session> {
        return sessions
            .asSequence()
            .filter { !it.isScheduled }
            .filter {
                it.displayTitle.contains(query, ignoreCase = true) ||
                    it.sessionId.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.lastUpdated }
            .take(30)
            .toList()
    }
}

private fun Session.toSessionRow(
    favorites: Set<String>,
    depth: Int,
    keyPrefix: String,
): SidebarRow.SessionItem = SidebarRow.SessionItem(
    sessionId = sessionId,
    title = displayTitle,
    agenticTool = agenticTool,
    status = status,
    favorite = favorites.contains(sessionId),
    depth = depth,
    keyPrefix = keyPrefix,
)
