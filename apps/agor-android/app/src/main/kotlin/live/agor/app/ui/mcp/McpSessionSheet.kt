package live.agor.app.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.LocalAppContainer
import live.agor.app.ui.simpleViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSessionSheet(sessionId: String, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: McpSessionViewModel = viewModel(
        key = "mcp-$sessionId",
        factory = simpleViewModelFactory { McpSessionViewModel(container, sessionId) },
    )
    val state by vm.state.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(sessionId) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        McpSessionContentView(
            state = state,
            onAdd = vm::addServer,
            onRemove = vm::removeServer,
            onEnabledChange = vm::setServerEnabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag("mcp-session-sheet"),
        )
    }
}

@Composable
private fun McpSessionContentView(
    state: McpSessionContent,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("MCP Servers", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Attach workspace servers to this session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading MCP servers...")
            }
            state.errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            }
            state.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No MCP servers are configured yet. Configure workspace MCP servers in the web UI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    McpServerListRow(
                        row = row,
                        onAdd = { onAdd(row.id) },
                        onRemove = { onRemove(row.id) },
                        onEnabledChange = { enabled -> onEnabledChange(row.id, enabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerListRow(
    row: McpServerRow,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.needsOauth) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text("OAuth needed") })
            }
            Spacer(Modifier.width(8.dp))
            if (row.isMutating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (row.isAttached) {
                Switch(
                    checked = row.isEnabled,
                    onCheckedChange = onEnabledChange,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRemove) {
                    Text("Remove")
                }
            } else {
                Button(onClick = onAdd, enabled = !row.needsOauth) {
                    Text("Add")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            row.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
