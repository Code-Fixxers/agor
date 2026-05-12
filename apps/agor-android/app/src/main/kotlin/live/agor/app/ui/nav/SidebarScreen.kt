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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.models.AgenticTool
import live.agor.app.models.ServerProfile
import live.agor.app.models.SessionStatus
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.StatusBadge
import live.agor.app.viewmodels.NavigationViewModel

@Composable
fun SidebarScreen(
    nav: NavigationViewModel,
    onSelectSession: (String) -> Unit,
    onSelectHermesSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: (ServerProfile) -> Unit,
    onCreateSession: (String) -> Unit,
    onOpenHermes: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    // Rows are pre-flattened on Dispatchers.Default in the ViewModel — no
    // Main-thread groupBy/sort/filter on socket patches.
    val rows by nav.rows.collectAsState()
    val navState by nav.state.collectAsState()
    val profiles by container.serverProfiles.profiles.collectAsState(initial = emptyList())
    val activeUrl = container.tokenStore.serverUrl?.trimEnd('/').orEmpty()
    val scope = rememberCoroutineScope()

    LazyColumn(modifier = Modifier.fillMaxWidth().testTag("sidebar-list")) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { scope.launch { nav.refresh() } },
                    modifier = Modifier.testTag("sidebar-refresh"),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("sidebar-settings")) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            ServerProfileRow(
                profiles = profiles,
                activeUrl = activeUrl,
                onSwitchProfile = onSwitchProfile,
            )
            OutlinedTextField(
                value = navState.searchQuery,
                onValueChange = nav::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag("sidebar-search"),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (navState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { nav.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search sessions") },
            )
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
                    title = row.title,
                    agenticTool = row.agenticTool,
                    status = row.status,
                    depth = row.depth,
                    favorite = row.favorite,
                    onClick = { onSelectSession(row.sessionId) },
                    onToggleFavorite = { nav.toggleFavorite(row.sessionId) },
                    onOpen = { onSelectSession(row.sessionId) },
                )
                is SidebarRow.HermesSessionItem -> HermesSessionRow(
                    title = row.title,
                    active = row.active,
                    onClick = { onSelectHermesSession(row.sessionId) },
                )
                is SidebarRow.BoardItem -> BoardRow(
                    name = row.name,
                    emoji = row.emoji,
                    isOpen = row.isOpen,
                    onClick = { nav.toggleBoard(row.boardId) },
                )
                is SidebarRow.WorktreeItem -> WorktreeRow(
                    name = row.name,
                    repoName = row.repoName,
                    branch = row.branch,
                    isOpen = row.isOpen,
                    onClick = { nav.toggleWorktree(row.worktreeId) },
                    onCreateSession = { onCreateSession(row.worktreeId) },
                )
            }
        }
    }
}

@Composable
private fun ServerProfileRow(
    profiles: List<ServerProfile>,
    activeUrl: String,
    onSwitchProfile: (ServerProfile) -> Unit,
) {
    if (profiles.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .testTag("sidebar-server-profiles"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        profiles.forEach { profile ->
            val selected = profile.url.trimEnd('/') == activeUrl
            TextButton(
                onClick = { if (!selected) onSwitchProfile(profile) },
                enabled = !selected,
                modifier = Modifier.testTag("sidebar-profile-${profile.id.hashCode()}"),
            ) {
                Text(
                    if (selected) "${profile.label} (current)" else profile.label,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HermesSessionRow(title: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sidebar-hermes-session-row")
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (active) {
            Text(
                "Running",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
private fun HermesShortcutRow(configured: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sidebar-hermes")
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
            .testTag("sidebar-board-row")
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
private fun WorktreeRow(
    name: String,
    repoName: String?,
    branch: String?,
    isOpen: Boolean,
    onClick: () -> Unit,
    onCreateSession: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sidebar-worktree-row")
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            val detail = listOfNotNull(
                repoName?.takeIf { it.isNotBlank() },
                branch?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onCreateSession,
            modifier = Modifier.testTag("sidebar-create-session"),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create session")
        }
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.testTag("sidebar-worktree-actions"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Worktree actions")
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Create session") },
                onClick = {
                    showMenu = false
                    onCreateSession()
                },
            )
            DropdownMenuItem(
                text = { Text(if (isOpen) "Collapse worktree" else "Expand worktree") },
                onClick = {
                    showMenu = false
                    onClick()
                },
            )
        }
        Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun SessionRow(
    title: String,
    agenticTool: AgenticTool,
    status: SessionStatus,
    depth: Int,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sidebar-session-row")
            .clickable(onClick = onClick)
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIcon(agenticTool)
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        StatusBadge(status)
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Favorite",
            )
        }
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.testTag("sidebar-session-actions"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Open session") },
                onClick = {
                    showMenu = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text(if (favorite) "Remove favorite" else "Add favorite") },
                onClick = {
                    showMenu = false
                    onToggleFavorite()
                },
            )
        }
    }
}
