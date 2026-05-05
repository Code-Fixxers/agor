package live.agor.app.ui.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.data.HermesImageInput
import live.agor.app.data.HermesSession
import live.agor.app.data.HermesTurn
import live.agor.app.ui.chat.PromptInputBar
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.HermesViewModel
import live.agor.app.voice.HermesVoiceController
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScreen(
    initialSessionId: String? = null,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val vm: HermesViewModel = viewModel(factory = simpleViewModelFactory { HermesViewModel(container) })
    val state by vm.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<HermesImageInput>>(emptyList()) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(initialSessionId) { vm.openSession(initialSessionId) }

    // Voice controller — instantiated lazily; released when the screen leaves composition.
    val voice = remember { HermesVoiceController(context.applicationContext) }
    val voicePhase by voice.phase.collectAsState()
    val voiceActive = voicePhase != HermesVoiceController.Phase.Idle

    DisposableEffect(voice) {
        voice.onTranscribed = { text -> vm.send(text) }
        onDispose { voice.release() }
    }

    // TTS the assistant's final reply when voice mode is active.
    LaunchedEffect(voice, vm) {
        vm.replies.collect { reply ->
            if (voicePhase != HermesVoiceController.Phase.Idle) voice.speakReply(reply)
        }
    }

    // RECORD_AUDIO permission gate — request on first toggle, not at app launch.
    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voice.start() }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            pendingImages = pendingImages + container.hermesImages.importUri(uri)
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch {
            pendingImages = pendingImages + container.hermesImages.importUri(uri)
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
                    IconButton(
                        onClick = {
                            if (voiceActive) {
                                voice.stop()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) voice.start()
                                else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    ) {
                        if (voiceActive) {
                            Icon(Icons.Default.MicOff, contentDescription = "Stop voice mode",
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = "Start voice mode")
                        }
                    }
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.Image, contentDescription = "Attach image")
                    }
                    IconButton(
                        onClick = {
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
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take photo")
                    }
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
            if (voiceActive) {
                VoiceStatusBar(voicePhase)
            }
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
                        items(state.turns, key = { it.id }) { turn -> TurnBubble(turn) }
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
            PromptInputBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val text = draft
                    val images = pendingImages
                    draft = ""
                    pendingImages = emptyList()
                    vm.send(text, images)
                },
                enabled = !state.isSending,
            )
        }
    }
}

@Composable
private fun VoiceStatusBar(phase: HermesVoiceController.Phase) {
    val label = when (phase) {
        HermesVoiceController.Phase.Idle -> ""
        HermesVoiceController.Phase.Calibrating -> "Calibrating microphone…"
        HermesVoiceController.Phase.Listening -> "Listening — speak your prompt"
        HermesVoiceController.Phase.Recording -> "Recording…"
        HermesVoiceController.Phase.Transcribing -> "Transcribing…"
        HermesVoiceController.Phase.Speaking -> "Hermes is speaking"
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
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
private fun TurnBubble(turn: HermesTurn) {
    val isUser = turn.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
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
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
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
                val displayContent = when {
                    turn.content.isNotEmpty() -> turn.content
                    turn.streaming -> "..."
                    else -> ""
                }
                Text(
                    displayContent,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
