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
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.StatusBadge
import live.agor.app.ui.filebrowser.FileBrowserSheet
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
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var showFiles by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { vm.load() }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.session?.let {
                            AgentIcon(it.agenticTool)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            state.session?.displayTitle ?: "Loading…",
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
                    state.session?.let { s ->
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
                draft = state.draft,
                onDraftChange = vm::updateDraft,
                onSend = vm::sendPrompt,
                enabled = state.session?.isPromptable == true,
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading && state.messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            } else if (state.errorMessage != null && state.messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                // Memoize the three derivations so they don't re-run on every
                // scroll-triggered recomposition. With long task histories and
                // active streaming, recomputing tasksById/grouped/orphans for
                // each frame was a measurable stutter source.
                val tasksById = remember(state.tasks) {
                    state.tasks.associateBy { it.taskId }
                }
                val grouped = remember(state.messages) {
                    groupMessagesByTask(state.messages)
                }
                val orphans = remember(state.live, state.messages) {
                    val seen = state.messages.mapTo(HashSet(state.messages.size)) { it.messageId }
                    state.live.entries.filter { it.key !in seen }.toList()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    if (state.messages.size >= 100) {
                        item {
                            TextButton(onClick = vm::loadEarlier) { Text("Load earlier messages") }
                        }
                    }
                    grouped.forEach { (taskId, messages) ->
                        val task = taskId?.let { tasksById[it] }
                        if (task != null) {
                            item(key = "task-${task.taskId}") { TaskHeader(task) }
                        }
                        items(messages, key = { it.messageId }) { message ->
                            MessageBubble(
                                message = message,
                                liveSnapshot = state.live[message.messageId],
                                onDecidePermission = vm::decidePermission,
                                onAnswerInputRequest = vm::answerInputRequest,
                            )
                        }
                    }
                    items(orphans, key = { "live-${it.key}" }) { (_, snap) ->
                        StreamingPlaceholder(snap)
                    }
                    item { Spacer(Modifier.height(60.dp)) }
                }
            }
        }

        if (showFiles) {
            state.session?.let {
                FileBrowserSheet(
                    worktreeId = it.worktreeId,
                    onDismiss = { showFiles = false },
                )
            }
        }
    }
}

private fun groupMessagesByTask(
    messages: List<live.agor.app.models.Message>,
): List<Pair<String?, List<live.agor.app.models.Message>>> {
    val out = mutableListOf<Pair<String?, MutableList<live.agor.app.models.Message>>>()
    var currentTask: String? = null
    var currentList: MutableList<live.agor.app.models.Message>? = null
    for (m in messages) {
        if (m.taskId != currentTask || currentList == null) {
            currentTask = m.taskId
            currentList = mutableListOf()
            out.add(currentTask to currentList)
        }
        currentList.add(m)
    }
    return out
}
