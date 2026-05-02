package live.agor.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.models.Board
import live.agor.app.models.Session
import live.agor.app.models.Worktree
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.StatusBadge
import live.agor.app.viewmodels.NavigationViewModel

/**
 * Flattened sidebar rows. Building a flat List<Row> upstream of the LazyColumn is
 * critical for scroll performance: nested `forEach` inside a single `items` slot
 * collapses the entire subtree into one composition unit and defeats LazyColumn's
 * lazy layout. With ~100 sessions and a busy board this stutters badly.
 */
private sealed class SidebarRow(val key: String) {
    class Header(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, key: String) : SidebarRow(key)
    class HermesShortcut(val configured: Boolean) : SidebarRow("hermes-shortcut")
    class DividerRow(suffix: String) : SidebarRow("div-$suffix")
    class SessionItem(val session: Session, val depth: Int, keyPrefix: String) :
        SidebarRow("$keyPrefix-${session.sessionId}")
    class BoardItem(val board: Board, val isOpen: Boolean) : SidebarRow("board-${board.boardId}")
    class WorktreeItem(val worktree: Worktree, val isOpen: Boolean) :
        SidebarRow("worktree-${worktree.worktreeId}")
}

@Composable
fun SidebarScreen(
    nav: NavigationViewModel,
    onSelectSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHermes: (() -> Unit)? = null,
) {
    val state by nav.state.collectAsState()
    val expandedBoards by nav.expandedBoards.collectAsState()
    val expandedWorktrees by nav.expandedWorktrees.collectAsState()
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current

    // Memoize derived lists. importantSessions() / needsAttentionSessions() were
    // O(n) over all sessions — running them on every recomposition was a stutter
    // source by itself.
    val important = remember(state.sessions, state.favorites) {
        nav.importantSessions()
    }
    val attention = remember(state.sessions) {
        nav.needsAttentionSessions()
    }

    // Build a flat row list once per state change.
    val rows = remember(state, expandedBoards, expandedWorktrees, important, attention) {
        flatten(state, expandedBoards, expandedWorktrees, important, attention,
            container.hermesClient.isConfigured)
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { nav.refresh() } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            Divider()
        }

        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is SidebarRow.Header -> SectionHeader(row.icon, row.label)
                is SidebarRow.HermesShortcut -> HermesShortcutRow(
                    configured = row.configured,
                    onClick = { onOpenHermes?.invoke() },
                )
                is SidebarRow.DividerRow -> Divider()
                is SidebarRow.SessionItem -> SessionRow(
                    session = row.session,
                    depth = row.depth,
                    favorite = state.favorites.contains(row.session.sessionId),
                    onClick = { onSelectSession(row.session.sessionId) },
                    onToggleFavorite = { nav.toggleFavorite(row.session.sessionId) },
                )
                is SidebarRow.BoardItem -> BoardRow(
                    name = row.board.name,
                    emoji = row.board.emoji,
                    isOpen = row.isOpen,
                    onClick = { nav.toggleBoard(row.board.boardId) },
                )
                is SidebarRow.WorktreeItem -> WorktreeRow(
                    name = row.worktree.name,
                    branch = row.worktree.branch,
                    isOpen = row.isOpen,
                    onClick = { nav.toggleWorktree(row.worktree.worktreeId) },
                )
            }
        }
    }
}

private fun flatten(
    state: NavigationViewModel.State,
    expandedBoards: Set<String>,
    expandedWorktrees: Set<String>,
    important: List<Session>,
    attention: List<Session>,
    hermesConfigured: Boolean,
): List<SidebarRow> {
    val out = ArrayList<SidebarRow>(64)

    // Hermes shortcut at the top — works whether configured or not so the user can
    // discover the entry point post-install.
    out += SidebarRow.HermesShortcut(configured = hermesConfigured)
    out += dividerRow("after-hermes")

    if (attention.isNotEmpty()) {
        out += SidebarRow.Header("Needs Attention", Icons.Default.Notifications, "h-attention")
        for (s in attention) out += SidebarRow.SessionItem(s, depth = 1, keyPrefix = "att")
        out += dividerRow("after-attention")
    }

    if (important.isNotEmpty()) {
        out += SidebarRow.Header("Important", Icons.Default.Star, "h-important")
        for (s in important) out += SidebarRow.SessionItem(s, depth = 1, keyPrefix = "imp")
        out += dividerRow("after-important")
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
                // Active sessions float to the top; idle/finished follow.
                .sortedWith(compareByDescending<Session> { it.status.isActive }
                    .thenByDescending { it.lastUpdated })
            for (s in sessions) {
                out += SidebarRow.SessionItem(s, depth = 3, keyPrefix = "sess-${wt.worktreeId}")
            }
        }
    }

    return out
}

private fun dividerRow(suffix: String): SidebarRow = SidebarRow.DividerRow(suffix)

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HermesShortcutRow(configured: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Hermes", style = MaterialTheme.typography.titleMedium)
            Text(
                if (configured) "Open chat" else "Tap to connect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoardRow(name: String, emoji: String?, isOpen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji ?: "📋", modifier = Modifier.width(24.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun WorktreeRow(name: String, branch: String?, isOpen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (!branch.isNullOrEmpty()) {
                Text(
                    branch,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun SessionRow(
    session: Session,
    depth: Int,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIcon(session.agenticTool)
        Spacer(Modifier.width(8.dp))
        Text(
            session.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        StatusBadge(session.status)
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Favorite",
            )
        }
    }
}

