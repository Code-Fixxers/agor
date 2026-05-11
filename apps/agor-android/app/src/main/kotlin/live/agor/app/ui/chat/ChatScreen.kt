package live.agor.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import live.agor.app.LocalAppContainer
import live.agor.app.models.ContentBlock
import live.agor.app.ui.common.AgentIcon
import live.agor.app.ui.common.AttachmentPickerDialog
import live.agor.app.ui.common.StatusBadge
import live.agor.app.ui.filebrowser.FileBrowserSheet
import live.agor.app.ui.messageblocks.ImageBlockView
import live.agor.app.ui.messageblocks.InputRequestCardView
import live.agor.app.ui.messageblocks.PermissionCardView
import live.agor.app.ui.messageblocks.ThinkingBlockView
import live.agor.app.ui.messageblocks.ToolResultBlockView
import live.agor.app.ui.messageblocks.ToolTraceBlockView
import live.agor.app.ui.messageblocks.ToolUseBlockView
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.ChatViewModel
import live.agor.app.voice.PromptVoicePhase
import java.io.File

private enum class ChatScrollDirection {
    TowardTop,
    TowardBottom,
}

private const val END_ALIGNMENT_SCROLL_OFFSET = 100_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onOpenDrawer: () -> Unit,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = simpleViewModelFactory { ChatViewModel(container, sessionId) },
    )
    val rows by vm.rows.collectAsState()
    val messageCount by vm.messageCount.collectAsState()
    val ui by vm.uiState.collectAsState()
    val promptVoice by vm.promptVoiceState.collectAsState()
    val attachments by vm.attachments.collectAsState()
    var showFiles by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember(sessionId) { mutableStateOf("") }

    val promptVoiceActive = promptVoice.phase == PromptVoicePhase.LoadingModels ||
        promptVoice.phase == PromptVoicePhase.Listening ||
        promptVoice.phase == PromptVoicePhase.Recording ||
        promptVoice.phase == PromptVoicePhase.Transcribing

    LaunchedEffect(sessionId) { vm.load() }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startVoiceInput() }
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.addAttachmentFromUri(uri) }
    val pictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.addAttachmentFromUri(uri) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.addAttachmentFromUri(uri)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createChatCameraUri(context)
            cameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    if (promptVoice.needsWhisperDownload) {
        AlertDialog(
            onDismissRequest = vm::dismissVoiceWhisperDownloadPrompt,
            title = { Text("Download local Whisper model?") },
            text = {
                Text(
                    "Remote Whisper is unavailable. Download the base English model once for on-device fallback. " +
                        "It is stored in app data and survives APK updates.",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::downloadVoiceWhisperModel) {
                    Text(if (promptVoice.modelDownloadInProgress) "Downloading..." else "Download")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissVoiceWhisperDownloadPrompt) {
                    Text("Not now")
                }
            },
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    modifier = Modifier.testTag("chat-rename-input"),
                    placeholder = { Text("Session title") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameSession(renameDraft)
                        showRenameDialog = false
                    },
                    enabled = renameDraft.trim().isNotEmpty(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showAttachDialog) {
        AttachmentPickerDialog(
            onDismiss = { showAttachDialog = false },
            onFile = {
                showAttachDialog = false
                fileLauncher.launch("*/*")
            },
            onPicture = {
                showAttachDialog = false
                pictureLauncher.launch("image/*")
            },
            onCamera = {
                showAttachDialog = false
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    val uri = createChatCameraUri(context)
                    cameraUri = uri
                    takePictureLauncher.launch(uri)
                } else {
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onLogs = {
                showAttachDialog = false
                vm.addLogsAttachment()
            },
        )
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
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("chat-open-drawer")) {
                        Icon(Icons.Default.Menu, contentDescription = "Open drawer")
                    }
                },
                actions = {
                    ui.session?.let { s ->
                        StatusBadge(s.status)
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                renameDraft = s.title?.takeIf { it.isNotBlank() } ?: s.displayTitle
                                showRenameDialog = true
                            },
                            modifier = Modifier.testTag("chat-rename"),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename session")
                        }
                        if (s.status.isActive) {
                            IconButton(onClick = vm::stop, modifier = Modifier.testTag("chat-stop")) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                        }
                    }
                    IconButton(onClick = { showFiles = true }, modifier = Modifier.testTag("chat-files")) {
                        Icon(Icons.Default.Folder, contentDescription = "Files")
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("chat-close")) {
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
                enabled = ui.session?.canQueuePrompt == true,
                attachments = attachments,
                onAttachClick = { showAttachDialog = true },
                onRemoveAttachment = vm::removeAttachment,
                voiceState = promptVoice,
                onVoiceInputClick = {
                    if (promptVoiceActive) {
                        vm.stopVoiceInput()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startVoiceInput()
                        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatMessagesPane(
                sessionId = sessionId,
                rows = rows,
                messageCount = messageCount,
                isLoading = ui.isLoading,
                errorMessage = ui.errorMessage,
                onLoadEarlier = vm::loadEarlier,
                onOpenSession = onOpenSession,
                onApprovePermission = { vm.decidePermission(it, true) },
                onDenyPermission = { vm.decidePermission(it, false) },
                onAnswerInputRequest = vm::answerInputRequest,
            )
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

@Composable
private fun ChatMessagesPane(
    sessionId: String,
    rows: List<ChatRow>,
    messageCount: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadEarlier: () -> Unit,
    onOpenSession: (String) -> Unit,
    onApprovePermission: (String) -> Unit,
    onDenyPermission: (String) -> Unit,
    onAnswerInputRequest: (String, List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    var lastScrollDirection by remember { mutableStateOf(ChatScrollDirection.TowardBottom) }
    var previousLastMessageIndex by remember(sessionId) { mutableIntStateOf(-1) }

    val lastMessageIndex = rows.lastIndex
    val isNearTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    val isNearBottom by remember(lastMessageIndex) {
        derivedStateOf {
            if (lastMessageIndex < 0) return@derivedStateOf true
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= lastMessageIndex - 1
        }
    }
    val fastScrollTarget by remember(isNearTop, isNearBottom, lastScrollDirection) {
        derivedStateOf {
            when {
                !isNearBottom && lastScrollDirection == ChatScrollDirection.TowardTop ->
                    ChatScrollDirection.TowardBottom
                !isNearTop && lastScrollDirection == ChatScrollDirection.TowardBottom ->
                    ChatScrollDirection.TowardTop
                !isNearBottom -> ChatScrollDirection.TowardBottom
                !isNearTop -> ChatScrollDirection.TowardTop
                else -> null
            }
        }
    }

    LaunchedEffect(lastMessageIndex, messageCount, initialScrollDone) {
        if (initialScrollDone || messageCount == 0 || lastMessageIndex < 0) return@LaunchedEffect
        listState.snapToHistoryEnd(lastMessageIndex)
        initialScrollDone = true
    }
    LaunchedEffect(lastMessageIndex, messageCount, initialScrollDone) {
        if (!initialScrollDone || messageCount == 0 || lastMessageIndex < 0) return@LaunchedEffect
        if (isNearBottom) {
            listState.snapToHistoryEnd(lastMessageIndex)
        }
    }
    LaunchedEffect(lastMessageIndex, initialScrollDone) {
        if (!initialScrollDone || lastMessageIndex < 0) {
            previousLastMessageIndex = lastMessageIndex
            return@LaunchedEffect
        }
        if (lastMessageIndex < previousLastMessageIndex && listState.firstVisibleItemIndex >= lastMessageIndex) {
            listState.snapToHistoryEnd(lastMessageIndex)
        }
        previousLastMessageIndex = lastMessageIndex
    }
    LaunchedEffect(listState) {
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .map { (index, offset) ->
                when {
                    index > previousIndex || (index == previousIndex && offset > previousOffset) ->
                        ChatScrollDirection.TowardBottom
                    index < previousIndex || (index == previousIndex && offset < previousOffset) ->
                        ChatScrollDirection.TowardTop
                    else -> null
                }.also {
                    previousIndex = index
                    previousOffset = offset
                }
            }
            .filter { it != null }
            .map { it!! }
            .distinctUntilChanged()
            .collectLatest { direction ->
                lastScrollDirection = direction
            }
    }

    if (isLoading && messageCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }
    if (errorMessage != null && messageCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("chat-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 16.dp),
        ) {
            items(
                rows,
                key = { it.key },
                contentType = { it::class },
            ) { row ->
                when (row) {
                    is ChatRow.LoadEarlier -> {
                        TextButton(onClick = onLoadEarlier) { Text("Load earlier messages") }
                    }
                    is ChatRow.TaskHeaderRow -> TaskHeader(row.task)
                    is ChatRow.TextBubbleRow -> TextBubble(row, onSessionClick = onOpenSession)
                    is ChatRow.ToolUseRow -> ToolUseBlockView(row)
                    is ChatRow.ToolResultRow -> ToolResultBlockView(row)
                    is ChatRow.ToolTraceRow -> ToolTraceBlockView(row)
                    is ChatRow.ThinkingRow -> ThinkingBlockView(row)
                    is ChatRow.ImageRow -> ImageBlockView(ContentBlock.Image(row.source))
                    is ChatRow.PermissionRow -> PermissionCardView(
                        request = row.request,
                        onApprove = { onApprovePermission(row.request.permissionId) },
                        onDeny = { onDenyPermission(row.request.permissionId) },
                    )
                    is ChatRow.InputRequestRow -> InputRequestCardView(
                        request = row.request,
                        onAnswer = { answers ->
                            onAnswerInputRequest(row.request.inputRequestId, answers)
                        },
                    )
                    is ChatRow.LiveOrphanRow -> LiveOrphanBubble(row)
                }
            }
        }

        fastScrollTarget?.let { target ->
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        when (target) {
                            ChatScrollDirection.TowardTop -> listState.animateScrollToItem(0)
                            ChatScrollDirection.TowardBottom -> {
                                if (lastMessageIndex >= 0) {
                                    listState.snapToHistoryEnd(lastMessageIndex)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("chat-fast-scroll"),
            ) {
                Icon(
                    imageVector = if (target == ChatScrollDirection.TowardTop) {
                        Icons.Default.ArrowUpward
                    } else {
                        Icons.Default.ArrowDownward
                    },
                    contentDescription = if (target == ChatScrollDirection.TowardTop) {
                        "Scroll to top"
                    } else {
                        "Scroll to latest message"
                    },
                )
            }
        }
    }
}

private fun createChatCameraUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "chat_camera").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.update.fileprovider",
        file,
    )
}

private suspend fun LazyListState.snapToHistoryEnd(lastMessageIndex: Int) {
    if (lastMessageIndex < 0) return
    scrollToItem(lastMessageIndex, END_ALIGNMENT_SCROLL_OFFSET)
}
