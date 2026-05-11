package live.agor.app.ui.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.agor.app.LocalAppContainer
import live.agor.app.data.HermesImageInput
import live.agor.app.data.HermesSession
import live.agor.app.data.HermesTurn
import live.agor.app.ui.common.copyOnDoubleTap
import live.agor.app.ui.common.AttachmentPickerDialog
import live.agor.app.ui.messageblocks.MarkdownText
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.viewmodels.HermesViewModel
import live.agor.app.voice.HermesVoicePhase
import live.agor.app.voice.HermesVoiceState
import java.io.File
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScreen(
    initialSessionId: String? = null,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val vm: HermesViewModel = viewModel(factory = simpleViewModelFactory { HermesViewModel(container) })
    val state by vm.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<HermesImageInput>>(emptyList()) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialSessionId) { vm.openSession(initialSessionId) }

    val voice = container.hermesVoice
    val voiceState by voice.state.collectAsState()

    LaunchedEffect(state.selectedSessionId) {
        voice.setActiveSession(state.selectedSessionId)
    }
    LaunchedEffect(state.isSending) {
        voice.setHermesRunning(state.isSending)
    }

    if (voiceState.needsWhisperDownload) {
        AlertDialog(
            onDismissRequest = voice::dismissWhisperDownloadPrompt,
            title = { Text("Download local Whisper model?") },
            text = {
                Text(
                    "Remote Whisper is unavailable. Download the base English model once for on-device fallback. " +
                        "It is stored in app data and survives APK updates.",
                )
            },
            confirmButton = {
                TextButton(onClick = voice::downloadWhisperModel) {
                    Text(if (voiceState.modelDownloadInProgress) "Downloading..." else "Download")
                }
            },
            dismissButton = {
                TextButton(onClick = voice::dismissWhisperDownloadPrompt) {
                    Text("Not now")
                }
            },
        )
    }

    // RECORD_AUDIO permission gate — request on first toggle, not at app launch.
    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voice.start() }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { container.hermesImages.importUri(uri) }
                .onSuccess { pendingImages = pendingImages + it }
                .onFailure {
                    AppLogger.log("Hermes image attach failed: ${it.message}", LogLevel.ERROR, "Hermes")
                }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            if (mimeType.startsWith("image/")) {
                runCatching { container.hermesImages.importUri(uri) }
                    .onSuccess { pendingImages = pendingImages + it }
                    .onFailure {
                        AppLogger.log("Hermes image file attach failed: ${it.message}", LogLevel.ERROR, "Hermes")
                    }
            } else {
                runCatching { readHermesTextAttachment(context, uri) }
                    .onSuccess { draft = appendPromptAttachment(draft, it) }
                    .onFailure {
                        AppLogger.log("Hermes text file attach failed: ${it.message}", LogLevel.ERROR, "Hermes")
                    }
            }
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch {
            runCatching { container.hermesImages.importUri(uri) }
                .onSuccess { pendingImages = pendingImages + it }
                .onFailure {
                    AppLogger.log("Hermes camera image attach failed: ${it.message}", LogLevel.ERROR, "Hermes")
                }
        }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createHermesCameraUri(context)
            cameraUri = uri
            takePictureLauncher.launch(uri)
        }
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
                galleryLauncher.launch("image/*")
            },
            onCamera = {
                showAttachDialog = false
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    val uri = createHermesCameraUri(context)
                    cameraUri = uri
                    takePictureLauncher.launch(uri)
                } else {
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onLogs = {
                showAttachDialog = false
                draft = appendPromptAttachment(
                    draft,
                    "Application logs:\n```text\n${AppLogger.exportText()}\n```",
                )
            },
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.content) {
        // Auto-scroll to the latest turn whenever a new chunk arrives.
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open drawer")
                    }
                },
                actions = {
                    IconButton(onClick = vm::newSession) {
                        Icon(Icons.Default.Add, contentDescription = "New Hermes session")
                    }
                    IconButton(onClick = vm::cancel, enabled = state.isSending) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop Hermes")
                    }
                    IconButton(onClick = vm::deleteSelectedSession, enabled = state.sessions.size > 1) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Hermes session")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            SessionStrip(
                sessions = state.sessions,
                selectedSessionId = state.selectedSessionId,
                onSelect = vm::selectSession,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.turns.isEmpty() && state.errorMessage == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Talk to Hermes — ask it to plan, dispatch, or summarize.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.turns, key = { it.id }) { turn ->
                            TurnBubble(turn, onSessionClick = onOpenSession)
                        }
                        state.errorMessage?.let { error ->
                            item { ErrorBubble(error, onDismiss = vm::cancel, onOpenSettings = onOpenSettings) }
                        }
                    }
                }
            }
            if (pendingImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pendingImages, key = { it.attachment.id }) { image ->
                        AssistChip(
                            onClick = { pendingImages = pendingImages.filterNot { it.attachment.id == image.attachment.id } },
                            label = { Text("Image") },
                            leadingIcon = {
                                AsyncImage(
                                    model = File(image.attachment.localPath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                        )
                    }
                }
            }
            HermesInputBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val text = draft
                    val images = pendingImages
                    draft = ""
                    pendingImages = emptyList()
                    vm.send(text, images)
                },
                enabled = true,
                onAttachClick = { showAttachDialog = true },
                voiceState = voiceState,
                onToggleVoice = {
                    if (voiceState.enabled) {
                        voice.stop()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) voice.start()
                        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onPendingTranscriptChange = voice::updatePendingTranscript,
                onCancelPendingTranscript = voice::cancelPendingTranscript,
                onSendPendingTranscript = voice::sendPendingNow,
                onSkipTts = voice::skipTts,
            )
        }
    }
}

@Composable
private fun HermesInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    onAttachClick: () -> Unit,
    voiceState: HermesVoiceState,
    onToggleVoice: () -> Unit,
    onPendingTranscriptChange: (String) -> Unit,
    onCancelPendingTranscript: () -> Unit,
    onSendPendingTranscript: () -> Unit,
    onSkipTts: () -> Unit,
) {
    val pending = voiceState.pendingTranscript
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        if (voiceState.enabled || voiceState.phase == HermesVoicePhase.Error) {
            VoiceStatusBar(
                state = voiceState,
                onTranscriptChange = onPendingTranscriptChange,
                onCancel = onCancelPendingTranscript,
                onSend = onSendPendingTranscript,
                onSkipTts = onSkipTts,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttachClick, enabled = enabled && pending == null) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = enabled && pending == null,
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("Message Hermes") },
            )
            FilledTonalIconButton(onClick = onToggleVoice) {
                if (voiceState.enabled) {
                    Icon(Icons.Default.MicOff, contentDescription = "Disable auto-listening")
                } else {
                    Icon(Icons.Default.Mic, contentDescription = "Enable auto-listening")
                }
            }
            Switch(
                checked = voiceState.enabled,
                onCheckedChange = { onToggleVoice() },
                enabled = pending == null,
            )
            IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank() && pending == null) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun VoiceStatusBar(
    state: HermesVoiceState,
    onTranscriptChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onSkipTts: () -> Unit,
) {
    val label = when (state.phase) {
        HermesVoicePhase.Idle -> "Auto-listening off"
        HermesVoicePhase.LoadingModels -> "Loading voice models..."
        HermesVoicePhase.Listening -> "Auto-listening"
        HermesVoicePhase.Recording -> "Recording..."
        HermesVoicePhase.Transcribing -> "Transcribing..."
        HermesVoicePhase.Reviewing -> "Reviewing transcript"
        HermesVoicePhase.Sending -> "Sending to Hermes..."
        HermesVoicePhase.Speaking -> "Hermes is speaking"
        HermesVoicePhase.Error -> state.errorMessage ?: "Voice mode failed"
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (state.phase == HermesVoicePhase.Speaking) Icons.Default.Forward else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (state.phase == HermesVoicePhase.Speaking) {
                    TextButton(onClick = onSkipTts) { Text("Skip") }
                }
            }
            if (state.phase == HermesVoicePhase.Listening || state.phase == HermesVoicePhase.Recording) {
                LinearProgressIndicator(
                    progress = { (state.audioLevel / state.threshold.coerceAtLeast(0.01f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            state.pendingTranscript?.let { transcript ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = transcript,
                        onValueChange = onTranscriptChange,
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 3,
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel transcript")
                    }
                    Button(onClick = onSend, modifier = Modifier.widthIn(min = 72.dp)) {
                        Text("Send")
                    }
                }
            }
            state.lastDiagnostic?.let { diagnostic ->
                Text(
                    diagnostic,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionStrip(
    sessions: List<HermesSession>,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
) {
    if (sessions.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            AssistChip(
                onClick = { onSelect(session.id) },
                label = {
                    Text(
                        if (session.active) "${session.title}..." else session.title,
                        maxLines = 1,
                    )
                },
                leadingIcon = if (session.id == selectedSessionId) {
                    { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun TurnBubble(
    turn: HermesTurn,
    onSessionClick: (String) -> Unit,
) {
    val isUser = turn.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val displayContent = when {
        turn.content.isNotEmpty() -> turn.content
        turn.streaming -> "..."
        else -> ""
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            if (isUser) "You" else "Hermes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .padding(top = 2.dp, bottom = 4.dp)
                .copyOnDoubleTap(displayContent),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                for (attachment in turn.attachments) {
                    AsyncImage(
                        model = File(attachment.localPath),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                MarkdownText(markdown = displayContent, onSessionClick = onSessionClick)
                if (turn.progress.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (item in turn.progress.takeLast(4)) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(message: String, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Hermes call failed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onOpenSettings) { Text("Open settings") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun createHermesCameraUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "hermes_camera").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.update.fileprovider",
        file,
    )
}

private fun appendPromptAttachment(draft: String, attachmentText: String): String {
    val trimmed = draft.trimEnd()
    return if (trimmed.isBlank()) attachmentText else "$trimmed\n\n$attachmentText"
}

private suspend fun readHermesTextAttachment(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                name to size
            } else {
                null to -1L
            }
        } ?: (null to -1L)

        val name = metadata.first?.takeIf { it.isNotBlank() }
            ?: "attachment-${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri)?.substringBefore(';')?.lowercase()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext "Attached file: $name\n\nCould not read file contents."

        if (!isPromptTextAttachment(name, mimeType)) {
            return@withContext "Attached file: $name (${mimeType ?: "unknown"}, ${bytes.size} bytes)\n\nBinary file content is not inlined in Hermes chat."
        }

        val limit = 256 * 1024
        val used = bytes.copyOf(min(bytes.size, limit))
        val text = used.decodeToString()
        val truncated = bytes.size > limit
        buildString {
            appendLine("Attached file: $name (${mimeType ?: "text"}, ${bytes.size} bytes)")
            if (truncated) appendLine("Showing first ${limit / 1024} KB.")
            appendLine("```text")
            appendLine(text)
            appendLine("```")
        }
    }

private fun isPromptTextAttachment(filename: String, mimeType: String?): Boolean {
    if (mimeType != null) {
        if (mimeType.startsWith("text/")) return true
        if (mimeType == "application/json") return true
    }
    return when (filename.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md", "markdown", "csv", "json", "yaml", "yml", "xml" -> true
        else -> false
    }
}
