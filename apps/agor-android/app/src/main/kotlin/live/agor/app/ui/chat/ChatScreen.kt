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
import androidx.compose.foundation.lazy.LazyColumn
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
import live.agor.app.ui.messageblocks.ToolUseBlockView
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.ChatViewModel
import live.agor.app.voice.PromptVoicePhase
import java.io.File

private enum class ChatScrollDirection {
    TowardTop,
    TowardBottom,
}

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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showFiles by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember(sessionId) { mutableStateOf("") }
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    var lastScrollDirection by remember { mutableStateOf(ChatScrollDirection.TowardBottom) }

    val promptVoiceActive = promptVoice.phase == PromptVoicePhase.LoadingModels ||
        promptVoice.phase == PromptVoicePhase.Listening ||
        promptVoice.phase == PromptVoicePhase.Recording ||
        promptVoice.phase == PromptVoicePhase.Transcribing

    val lastMessageIndex = remember(rows) {
        rows.indexOfLast { it !is ChatRow.BottomSpacer }
    }
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

    // On first open, land on the latest real message rather than the top of a
    // potentially long session. After that, preserve user position.
    LaunchedEffect(lastMessageIndex, messageCount, initialScrollDone) {
        if (initialScrollDone || messageCount == 0 || lastMessageIndex < 0) return@LaunchedEffect
        listState.scrollToItem(lastMessageIndex)
        initialScrollDone = true
    }

    // Only auto-scroll when the user is already near the bottom — yanking them
    // out of mid-scroll-up reading is what makes chats feel janky on long sessions.
    LaunchedEffect(lastMessageIndex, messageCount, initialScrollDone) {
        if (!initialScrollDone || messageCount == 0 || lastMessageIndex < 0) return@LaunchedEffect
        if (isNearBottom) {
            listState.animateScrollToItem(lastMessageIndex)
        }
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
                enabled = ui.session?.isPromptable == true,
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
                    modifier = Modifier.fillMaxSize().testTag("chat-list"),
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
                            is ChatRow.TextBubbleRow -> TextBubble(row, onSessionClick = onOpenSession)
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

                fastScrollTarget?.let { target ->
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                when (target) {
                                    ChatScrollDirection.TowardTop -> listState.animateScrollToItem(0)
                                    ChatScrollDirection.TowardBottom -> {
                                        if (lastMessageIndex >= 0) {
                                            listState.animateScrollToItem(lastMessageIndex)
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

private fun createChatCameraUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "chat_camera").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.update.fileprovider",
        file,
    )
}
