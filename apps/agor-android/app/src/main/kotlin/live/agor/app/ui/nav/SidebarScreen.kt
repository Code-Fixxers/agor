package live.agor.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import live.agor.app.models.Session
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.StatusBadge
import live.agor.app.viewmodels.NavigationViewModel

@Composable
fun SidebarScreen(
    nav: NavigationViewModel,
    onSelectSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by nav.state.collectAsState()
    val expandedBoards by nav.expandedBoards.collectAsState()
    val expandedWorktrees by nav.expandedWorktrees.collectAsState()
    val scope = rememberCoroutineScope()

    val important = nav.importantSessions()
    val attention = nav.needsAttentionSessions()

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
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

        if (attention.isNotEmpty()) {
            item { SectionHeader(icon = Icons.Default.Notifications, label = "Needs Attention") }
            items(attention, key = { "att-${it.sessionId}" }) { s ->
                SessionRow(s, depth = 1, onClick = { onSelectSession(s.sessionId) }, favorite = state.favorites.contains(s.sessionId)) {
                    nav.toggleFavorite(s.sessionId)
                }
            }
            item { Divider() }
        }

        if (important.isNotEmpty()) {
            item { SectionHeader(icon = Icons.Default.Star, label = "Important") }
            items(important, key = { "imp-${it.sessionId}" }) { s ->
                SessionRow(s, depth = 1, onClick = { onSelectSession(s.sessionId) }, favorite = state.favorites.contains(s.sessionId)) {
                    nav.toggleFavorite(s.sessionId)
                }
            }
            item { Divider() }
        }

        item { SectionHeader(icon = Icons.Default.AccountTree, label = "Boards") }
        items(state.boards, key = { it.boardId }) { board ->
            val isOpen = expandedBoards.contains(board.boardId)
            BoardRow(name = board.name, emoji = board.emoji, isOpen = isOpen) {
                nav.toggleBoard(board.boardId)
            }
            if (isOpen) {
                val worktrees = state.worktreesByBoard[board.boardId].orEmpty()
                worktrees.forEach { wt ->
                    val wtOpen = expandedWorktrees.contains(wt.worktreeId)
                    WorktreeRow(name = wt.name, branch = wt.branch, isOpen = wtOpen) {
                        nav.toggleWorktree(wt.worktreeId)
                    }
                    if (wtOpen) {
                        val sessions = state.sessionsByWorktree[wt.worktreeId].orEmpty()
                            .filter { it.archived != true }
                            .sortedByDescending { it.lastUpdated }
                        sessions.forEach { s ->
                            SessionRow(
                                s,
                                depth = 3,
                                onClick = { onSelectSession(s.sessionId) },
                                favorite = state.favorites.contains(s.sessionId),
                            ) { nav.toggleFavorite(s.sessionId) }
                        }
                    }
                }
            }
        }
    }
}

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
    onClick: () -> Unit,
    favorite: Boolean,
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
