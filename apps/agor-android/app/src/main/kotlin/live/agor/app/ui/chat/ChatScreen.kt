package live.agor.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import live.agor.app.models.InputRequestContent
import live.agor.app.models.PermissionMode
import live.agor.app.models.Session
import live.agor.app.models.displaySummary
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
import live.agor.app.ui.mcp.McpSessionSheet
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
    refreshNonce: Int = 0,
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
    val sessionVoice by vm.sessionVoiceState.collectAsState()
    val sessionVoiceSettings by vm.sessionVoiceSettings.collectAsState()
    val attachments by vm.attachments.collectAsState()
    var showFiles by remember { mutableStateOf(false) }
    var fileBrowserInitialPath by remember(sessionId) { mutableStateOf<String?>(null) }
    var showAttachDialog by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showMcpServers by remember { mutableStateOf(false) }
    var showSessionSettings by remember { mutableStateOf(false) }
    var renameDraft by remember(sessionId) { mutableStateOf("") }

    val promptVoiceActive = promptVoice.phase == PromptVoicePhase.LoadingModels ||
        promptVoice.phase == PromptVoicePhase.Listening ||
        promptVoice.phase == PromptVoicePhase.Recording ||
        promptVoice.phase == PromptVoicePhase.Transcribing
    val ownsSessionVoice = sessionVoice.enabled && sessionVoice.activeSessionId == sessionId

    LaunchedEffect(sessionId, refreshNonce) { vm.load() }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startSessionVoiceMode() }
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

    if (sessionVoice.needsWhisperDownload && ownsSessionVoice) {
        AlertDialog(
            onDismissRequest = vm::dismissSessionVoiceWhisperDownloadPrompt,
            title = { Text("Download local Whisper model?") },
            text = {
                Text(
                    "Remote Whisper is unavailable. Download the base English model once for on-device fallback. " +
                        "It is stored in app data and survives APK updates.",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::downloadSessionVoiceWhisperModel) {
                    Text(if (sessionVoice.modelDownloadInProgress) "Downloading..." else "Download")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSessionVoiceWhisperDownloadPrompt) {
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

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text("Archive session?") },
            text = { Text("The session will leave normal drawer views but remain available in archived history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.archiveSession()
                        showArchiveDialog = false
                        onClose()
                    },
                ) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset session?") },
            text = {
                Text("The current session will be archived and a fresh idle session will open on the same worktree.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        vm.resetSession(onOpenSession)
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
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
                        if (ui.session?.isPlanMode == true) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    "PLAN",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
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
                    Box {
                        IconButton(onClick = { showOverflow = true }, modifier = Modifier.testTag("chat-overflow")) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Session Settings") },
                                onClick = {
                                    showOverflow = false
                                    showSessionSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("MCP Servers") },
                                onClick = {
                                    showOverflow = false
                                    showMcpServers = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    showArchiveDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reset Session") },
                                onClick = {
                                    showOverflow = false
                                    showResetDialog = true
                                },
                            )
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("chat-close")) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
        bottomBar = {
            if (ownsSessionVoice) {
                SessionVoiceControlBar(
                    state = sessionVoice,
                    settings = sessionVoiceSettings,
                    onPendingTranscriptChange = vm::updateSessionVoiceTranscript,
                    onCancelPendingTranscript = vm::cancelSessionVoiceTranscript,
                    onSendPendingTranscript = vm::sendSessionVoiceTranscriptNow,
                    onSkipTts = vm::skipSessionVoiceTts,
                    onStopVoice = vm::stopSessionVoiceMode,
                    onSettingsChange = vm::saveSessionVoiceSettings,
                    onResetSettings = vm::resetSessionVoiceSettings,
                )
            } else {
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
                            if (granted) vm.startSessionVoiceMode()
                            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.session?.isPlanMode == true) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Plan mode: read-only planning session. Tool execution stays disabled.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            ui.session?.modelConfig?.displaySummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ChatMessagesPane(
                    sessionId = sessionId,
                    rows = rows,
                    messageCount = messageCount,
                    isLoading = ui.isLoading,
                    errorMessage = ui.errorMessage,
                    onLoadEarlier = vm::loadEarlier,
                    onShowOlderTasks = vm::showOlderTasks,
                    onToggleTask = vm::toggleTask,
                    onOpenSession = onOpenSession,
                    onOpenWorktreePath = { path ->
                        fileBrowserInitialPath = path
                        showFiles = true
                    },
                    onApprovePermission = { requestId, taskId -> vm.decidePermission(requestId, taskId, true) },
                    onDenyPermission = { requestId, taskId -> vm.decidePermission(requestId, taskId, false) },
                    onAnswerInputRequest = vm::answerInputRequest,
                    modifier = Modifier.fillMaxSize(),
                )
                val activeVoiceSessionId = sessionVoice.activeSessionId
                if (sessionVoice.enabled && activeVoiceSessionId != null && activeVoiceSessionId != sessionId) {
                    SmallFloatingActionButton(
                        onClick = { onOpenSession(activeVoiceSessionId) },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .testTag("chat-return-to-voice-session"),
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Return to voice session")
                    }
                }
            }
        }

        if (showFiles) {
            ui.session?.let {
                FileBrowserSheet(
                    worktreeId = it.worktreeId,
                    initialPath = fileBrowserInitialPath,
                    onDismiss = {
                        showFiles = false
                        fileBrowserInitialPath = null
                    },
                )
            }
        }
        if (showMcpServers) {
            McpSessionSheet(
                sessionId = sessionId,
                onDismiss = { showMcpServers = false },
            )
        }
        if (showSessionSettings) {
            ui.session?.let { session ->
                SessionSettingsSheet(
                    session = session,
                    onChangePermissionMode = vm::changePermissionMode,
                    onDismiss = { showSessionSettings = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSettingsSheet(
    session: Session,
    onChangePermissionMode: (PermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag("session-settings-sheet"),
        ) {
            Text("Session Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            SessionSettingRow("Status", session.status.displayLabel)
            SessionSettingRow("Agent", session.agenticTool.displayName)
            PermissionModeSettingRow(
                current = session.permissionConfig?.mode,
                onChange = onChangePermissionMode,
            )
            session.modelConfig?.displaySummary?.takeIf { it.isNotBlank() }?.let {
                SessionSettingRow("Model", it)
            }
            SessionSettingRow("Worktree", session.worktreeId)
            SessionSettingRow("Session ID", session.sessionId)
        }
    }
}

@Composable
private fun PermissionModeSettingRow(
    current: PermissionMode?,
    onChange: (PermissionMode) -> Unit,
) {
    val options = listOf(
        PermissionMode.DEFAULT,
        PermissionMode.PLAN,
        PermissionMode.ASK,
        PermissionMode.AUTO,
        PermissionMode.ACCEPT_EDITS,
        PermissionMode.BYPASS,
    )
    var showMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "Permission mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            TextButton(
                onClick = { showMenu = true },
                modifier = Modifier.testTag("session-permission-mode"),
            ) {
                Text(current.permissionModeLabel())
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                options.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.permissionModeLabel()) },
                        onClick = {
                            showMenu = false
                            onChange(mode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSettingRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun PermissionMode?.permissionModeLabel(): String = when (this) {
    PermissionMode.DEFAULT -> "Default"
    PermissionMode.ACCEPT_EDITS -> "Accept edits"
    PermissionMode.BYPASS -> "Bypass permissions"
    PermissionMode.PLAN -> "Plan"
    PermissionMode.DONT_ASK -> "Don't ask"
    PermissionMode.AUTO_EDIT -> "Auto edit"
    PermissionMode.YOLO -> "Yolo"
    PermissionMode.ASK -> "Ask"
    PermissionMode.AUTO -> "Auto"
    PermissionMode.ON_FAILURE -> "On failure"
    PermissionMode.ALLOW_ALL -> "Allow all"
    null -> "Default"
}

@Composable
private fun ChatMessagesPane(
    sessionId: String,
    rows: List<ChatRow>,
    messageCount: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadEarlier: () -> Unit,
    onShowOlderTasks: () -> Unit,
    onToggleTask: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenWorktreePath: (String) -> Unit,
    onApprovePermission: (String, String?) -> Unit,
    onDenyPermission: (String, String?) -> Unit,
    onAnswerInputRequest: (InputRequestContent, String?, List<String>) -> Unit,
    modifier: Modifier = Modifier,
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
    val attentionIndex = remember(rows) { newestAttentionRowIndex(rows) }

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
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }
    if (errorMessage != null && messageCount == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (attentionIndex != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("chat-attention-banner"),
            ) {
                TextButton(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(attentionIndex) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Pending response needed",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
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
                    is ChatRow.ShowOlderTasks -> {
                        TextButton(onClick = onShowOlderTasks) { Text("Show ${row.count} older tasks") }
                    }
                    is ChatRow.TaskHeaderRow -> TaskHeader(
                        task = row.task,
                        expanded = row.expanded,
                        loadedMessageCount = row.loadedMessageCount,
                        onToggle = { onToggleTask(row.task.taskId) },
                    )
                    is ChatRow.TextBubbleRow -> TextBubble(
                        row,
                        onSessionClick = onOpenSession,
                        onWorktreePathClick = onOpenWorktreePath,
                    )
                    is ChatRow.ToolUseRow -> ToolUseBlockView(row)
                    is ChatRow.ToolResultRow -> ToolResultBlockView(row)
                    is ChatRow.ToolTraceRow -> ToolTraceBlockView(row)
                    is ChatRow.ThinkingRow -> ThinkingBlockView(row)
                    is ChatRow.ImageRow -> ImageBlockView(ContentBlock.Image(row.source))
                    is ChatRow.PermissionRow -> PermissionCardView(
                        request = row.request,
                        onApprove = { onApprovePermission(row.request.permissionId, row.taskId) },
                        onDeny = { onDenyPermission(row.request.permissionId, row.taskId) },
                    )
                    is ChatRow.InputRequestRow -> InputRequestCardView(
                        request = row.request,
                        onAnswer = { answers ->
                            onAnswerInputRequest(row.request, row.taskId, answers)
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
    scrollToItem(lastMessageIndex)
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastMessageIndex } ?: return
    val gapBelow = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding - (lastVisibleItem.offset + lastVisibleItem.size)
    if (gapBelow > 0) {
        scrollBy(-gapBelow.toFloat())
    }
}
