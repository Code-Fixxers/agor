package live.agor.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.LocalAppContainer
import live.agor.app.models.ContentBlock
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.StatusBadge
import live.agor.app.ui.filebrowser.FileBrowserSheet
import live.agor.app.ui.messageblocks.ImageBlockView
import live.agor.app.ui.messageblocks.InputRequestCardView
import live.agor.app.ui.messageblocks.PermissionCardView
import live.agor.app.ui.messageblocks.ThinkingBlockView
import live.agor.app.ui.messageblocks.ToolResultBlockView
import live.agor.app.ui.messageblocks.ToolUseBlockView
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onOpenDrawer: () -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = simpleViewModelFactory { ChatViewModel(container, sessionId) },
    )
    val rows by vm.rows.collectAsState()
    val messageCount by vm.messageCount.collectAsState()
    val ui by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showFiles by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { vm.load() }

    // Only auto-scroll when the user is already near the bottom — yanking them out
    // of mid-scroll-up reading is what makes chats feel janky on long sessions.
    LaunchedEffect(rows.size) {
        if (messageCount == 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val total = info.totalItemsCount
        if (total > 0 && lastVisible >= total - 3) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ui.session?.let {
                            AgentIcon(it.agenticTool)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            ui.session?.displayTitle ?: "Loading…",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open drawer")
                    }
                },
                actions = {
                    ui.session?.let { s ->
                        StatusBadge(s.status)
                        Spacer(Modifier.width(4.dp))
                        if (s.status.isActive) {
                            IconButton(onClick = vm::stop) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                        }
                    }
                    IconButton(onClick = { showFiles = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Files")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
        bottomBar = {
            PromptInputBar(
                draft = ui.draft,
                onDraftChange = vm::updateDraft,
                onSend = vm::sendPrompt,
                enabled = ui.session?.isPromptable == true,
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.isLoading && messageCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            } else if (ui.errorMessage != null && messageCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(ui.errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                // `rows` is pre-flattened on Dispatchers.Default in the ViewModel.
                // Heavy strings (JSON, joined tool-result text, merged streaming
                // text) are precomputed and cached in @Immutable row records, so
                // recomposition during scroll reads them as plain Strings.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(
                        rows,
                        key = { it.key },
                        // Distinct contentType per row variant lets LazyColumn
                        // pool composition slots across messages — a tool-use row
                        // scrolling into view reuses the slot of an earlier
                        // tool-use row that just left.
                        contentType = { it::class },
                    ) { row ->
                        when (row) {
                            is ChatRow.LoadEarlier -> {
                                TextButton(onClick = vm::loadEarlier) { Text("Load earlier messages") }
                            }
                            is ChatRow.TaskHeaderRow -> TaskHeader(row.task)
                            is ChatRow.TextBubbleRow -> TextBubble(row)
                            is ChatRow.ToolUseRow -> ToolUseBlockView(row)
                            is ChatRow.ToolResultRow -> ToolResultBlockView(row)
                            is ChatRow.ThinkingRow -> ThinkingBlockView(row)
                            is ChatRow.ImageRow -> ImageBlockView(ContentBlock.Image(row.source))
                            is ChatRow.PermissionRow -> PermissionCardView(
                                request = row.request,
                                onApprove = { vm.decidePermission(row.request.permissionId, true) },
                                onDeny = { vm.decidePermission(row.request.permissionId, false) },
                            )
                            is ChatRow.InputRequestRow -> InputRequestCardView(
                                request = row.request,
                                onAnswer = { answers ->
                                    vm.answerInputRequest(row.request.inputRequestId, answers)
                                },
                            )
                            is ChatRow.LiveOrphanRow -> LiveOrphanBubble(row)
                            is ChatRow.BottomSpacer -> Spacer(Modifier.height(60.dp))
                        }
                    }
                }
            }
        }

        if (showFiles) {
            ui.session?.let {
                FileBrowserSheet(
                    worktreeId = it.worktreeId,
                    onDismiss = { showFiles = false },
                )
            }
        }
    }
}
