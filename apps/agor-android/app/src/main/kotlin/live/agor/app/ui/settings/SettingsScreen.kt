package live.agor.app.ui.settings

import android.content.Intent
import live.agor.app.auth.AuthState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.BuildConfig
import live.agor.app.LocalAppContainer
import live.agor.app.models.ServerProfile
import live.agor.app.models.DrawerSessionFilter
import live.agor.app.network.ConnectionState
import live.agor.app.network.UploadFileInput
import live.agor.app.ui.common.ConnectionIndicator
import live.agor.app.ui.common.findFragmentActivity
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.util.AppLogger
import live.agor.app.voice.DEFAULT_REMOTE_WHISPER_URL
import live.agor.app.voice.LEGACY_REMOTE_WHISPER_URL
import live.agor.app.viewmodels.AppViewModel
import live.agor.app.viewmodels.UpdateViewModel
import live.agor.app.auth.SecureTokenStore
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: AppViewModel,
    onOpenDrawer: () -> Unit,
    onClose: () -> Unit,
    onOpenHermesSetup: (() -> Unit)? = null,
    onDrawerSessionFilterChanged: () -> Unit = {},
    currentSessionId: String? = null,
) {
    val container = LocalAppContainer.current
    val user by app.user.collectAsState()
    val auth by app.authState.collectAsState()
    val conn by app.connectionState.collectAsState()
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("settings-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("settings-open-drawer")) {
                        Icon(Icons.Default.Menu, contentDescription = "Drawer")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(16.dp),
        ) {
            user?.let { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.emoji ?: "👤", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, style = MaterialTheme.typography.titleMedium)
                        u.email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
            }

            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionIndicator(conn)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (conn) {
                        ConnectionState.Connected -> "Connected"
                        ConnectionState.Connecting -> "Connecting…"
                        ConnectionState.Reconnecting -> "Reconnecting…"
                        ConnectionState.Disconnected -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                container.client.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            ServerProfilesRow()

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            DrawerSessionFilterRow(onChanged = onDrawerSessionFilterChanged)

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            BiometricLoginRow(defaultEmail = user?.email)

            onOpenHermesSetup?.let { open ->
                Spacer(Modifier.height(24.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                Text("Hermes orchestrator", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    container.tokenStore.hermesUrl?.takeIf { it.isNotBlank() }
                        ?: "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = open, modifier = Modifier.fillMaxWidth().testTag("settings-hermes")) {
                    Text(if (container.hermesClient.isConfigured) "Edit Hermes connection" else "Connect Hermes")
                }
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            WhisperServerRow()

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            DiagnosticsRow(
                currentSessionId = currentSessionId,
                authState = auth,
                connectionState = conn,
                baseUrl = container.client.baseUrl,
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GithubTokenRow()
            Spacer(Modifier.height(8.dp))
            UpdateRow()

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            TextButton(onClick = app::logout, modifier = Modifier.fillMaxWidth().testTag("settings-sign-out")) {
                Text("Sign out")
            }
        }
    }
}

@Composable
internal fun DiagnosticsRow(
    currentSessionId: String?,
    authState: AuthState,
    connectionState: ConnectionState,
    baseUrl: String,
    allowSendToSession: Boolean = true,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val crashReport = remember { container.crashLogs.read() }

    fun shareLogs() {
        runCatching {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "agor-logs-${System.currentTimeMillis()}.txt")
            file.writeText(AppLogger.exportText(container.crashLogs.read()))
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.update.fileprovider",
                file,
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Agor Android logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Agor logs"))
            message = "Log export ready."
        }.onFailure {
            message = "Could not export logs: ${it.message}"
        }
    }

    fun clearLogs() {
        val removed = AppLogger.clear()
        container.crashLogs.clear()
        message = if (removed == 1) {
            "Cleared 1 log entry and crash log."
        } else {
            "Cleared $removed log entries and crash log."
        }
    }

    fun sendLogsToSession() {
        val sessionId = currentSessionId ?: run {
            message = "Open an Agor session first, then return here to send logs."
            return
        }
        val text = AppLogger.exportText(container.crashLogs.read())
        val filename = "agor-android-logs-${System.currentTimeMillis()}.txt"
        message = "Sending logs to session..."
        scope.launch {
            runCatching {
                container.client.uploadSessionFiles(
                    sessionId = sessionId,
                    files = listOf(
                        UploadFileInput(
                            filename = filename,
                            mimeType = "text/plain",
                            bytes = text.toByteArray(),
                        ),
                    ),
                    notifyAgent = true,
                    message = diagnosticsUploadMessage(),
                )
            }.onSuccess {
                message = "Logs sent to current session."
            }.onFailure {
                message = "Could not send logs: ${it.message}"
            }
        }
    }

    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Export recent app logs and the latest crash report for voice, transcription, networking, and update debugging.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        diagnosticHealthSummary(
            authState = authState,
            connectionState = connectionState,
            baseUrl = baseUrl,
            logEntryCount = AppLogger.snapshot().size,
            hasCrashReport = !crashReport.isNullOrBlank(),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("settings-diagnostic-health"),
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = ::shareLogs, modifier = Modifier.fillMaxWidth().testTag("settings-export-logs")) {
        Text("Download logs")
    }
    if (allowSendToSession) {
        TextButton(onClick = ::sendLogsToSession, modifier = Modifier.fillMaxWidth().testTag("settings-send-logs")) {
            Text("Send logs to current session")
        }
    }
    TextButton(onClick = ::clearLogs, modifier = Modifier.fillMaxWidth().testTag("settings-clear-logs")) {
        Text("Clear logs")
    }
    message?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun diagnosticsUploadMessage(): String =
    "Attached file(s): {filepath}\n\nPlease review these Android diagnostics logs."

internal fun diagnosticHealthSummary(
    authState: AuthState,
    connectionState: ConnectionState,
    baseUrl: String,
    logEntryCount: Int,
    hasCrashReport: Boolean,
): String {
    val authLabel = when (authState) {
        AuthState.Unknown -> "auth unknown"
        AuthState.NeedsLogin -> "needs login"
        AuthState.NeedsBiometricUnlock -> "locked"
        AuthState.Authenticated -> "authenticated"
    }
    val socketLabel = when (connectionState) {
        ConnectionState.Connected -> "socket connected"
        ConnectionState.Connecting -> "socket connecting"
        ConnectionState.Reconnecting -> "socket reconnecting"
        ConnectionState.Disconnected -> "socket disconnected"
    }
    val httpLabel = if (baseUrl.isBlank()) "HTTP base URL unset" else "HTTP $baseUrl"
    val crashLabel = if (hasCrashReport) "crash report available" else "no crash report"
    return "$authLabel · $socketLabel · $httpLabel · $logEntryCount log entries · $crashLabel"
}

@Composable
private fun ServerProfilesRow() {
    val container = LocalAppContainer.current
    val profiles by container.serverProfiles.profiles.collectAsState(initial = emptyList())
    val activeUrl = container.tokenStore.serverUrl?.trimEnd('/').orEmpty()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    fun openEditor(profile: ServerProfile?) {
        editing = profile
        showEditor = true
    }

    Text("Servers", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Saved Agor servers are shown in the drawer. Credentials are stored per server when you sign in.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    profiles.forEach { profile ->
        val selected = profile.url.trimEnd('/') == activeUrl
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(
                        profile.label,
                        if (selected) "current" else null,
                        if (profile.isDefault) "default" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    profile.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { openEditor(profile) }) {
                Text("Edit")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        container.serverProfiles.setDefault(profile.id, profiles)
                    }
                },
                enabled = !profile.isDefault,
            ) {
                Text("Default")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        container.serverProfiles.remove(profile.id, profiles)
                        container.tokenStore.removeProfileCredentials(profile.id)
                    }
                },
                enabled = !selected,
            ) {
                Text("Delete")
            }
        }
    }
    TextButton(
        onClick = { openEditor(null) },
        modifier = Modifier.fillMaxWidth().testTag("settings-add-server-profile"),
    ) {
        Text("Add server")
    }

    if (showEditor) {
        ServerProfileEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { profile ->
                scope.launch {
                    container.serverProfiles.upsert(profile, profiles)
                    showEditor = false
                }
            },
        )
    }
}

@Composable
private fun ServerProfileEditorDialog(
    initial: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var email by remember(initial) { mutableStateOf(initial?.email.orEmpty()) }
    val trimmedUrl = url.trim().trimEnd('/')
    val canSave = label.trim().isNotBlank() && trimmedUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add server" else "Edit server") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Daemon URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = initial?.id ?: UUID.randomUUID().toString()
                    onSave(
                        ServerProfile(
                            id = id,
                            label = label.trim(),
                            url = trimmedUrl,
                            email = email.trim().ifBlank { null },
                            isDefault = initial?.isDefault == true,
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DrawerSessionFilterRow(onChanged: () -> Unit) {
    val container = LocalAppContainer.current
    var selected by remember {
        mutableStateOf(DrawerSessionFilter.fromToken(container.tokenStore.drawerSessionFilter))
    }

    fun select(option: DrawerSessionFilter) {
        if (selected == option) return
        selected = option
        container.tokenStore.drawerSessionFilter = option.token
        onChanged()
    }

    Text("Drawer sessions", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Choose how much session history appears in the drawer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    DrawerSessionFilter.entries.chunked(2).forEach { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.forEach { option ->
                val modifier = Modifier
                    .weight(1f)
                    .testTag("settings-drawer-${option.token}")
                if (selected == option) {
                    Button(onClick = { select(option) }, modifier = modifier) {
                        Text(option.label)
                    }
                } else {
                    TextButton(onClick = { select(option) }, modifier = modifier) {
                        Text(option.label)
                    }
                }
                if (option != row.last()) Spacer(Modifier.width(8.dp))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
    val description = when (selected) {
        DrawerSessionFilter.SevenDays -> "Showing non-archived sessions updated in the last 7 days."
        DrawerSessionFilter.ThirtyDays -> "Showing non-archived sessions updated in the last 30 days."
        DrawerSessionFilter.All -> "Showing all non-archived sessions."
        DrawerSessionFilter.Archived -> "Showing all sessions and worktrees, including archived items."
    }
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun WhisperServerRow() {
    val container = LocalAppContainer.current
    var whisperUrl by remember {
        mutableStateOf(container.tokenStore.remoteWhisperUrl ?: DEFAULT_REMOTE_WHISPER_URL)
    }
    var whisperToken by remember { mutableStateOf(container.tokenStore.remoteWhisperToken ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    val savedUrl = container.tokenStore.remoteWhisperUrl

    fun saveWhisperSettings() {
        container.tokenStore.remoteWhisperUrl = whisperUrl.trim().trimEnd('/').ifBlank { null }
        container.tokenStore.remoteWhisperToken = whisperToken.trim().ifBlank { null }
        message = container.tokenStore.remoteWhisperUrl?.let { "Remote Whisper saved: $it" }
            ?: "Remote Whisper disabled. Local Whisper will be used if available."
    }

    Text("Whisper transcription", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Voice mode uses your self-hosted WhisperLiveKit server for streaming transcription before falling back to local Whisper when available.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        savedUrl?.takeIf { it.isNotBlank() } ?: "Not configured",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                whisperUrl = DEFAULT_REMOTE_WHISPER_URL
                message = "WhisperLiveKit selected. Save to apply."
            },
            modifier = Modifier.weight(1f).testTag("settings-whisper-livekit"),
        ) {
            Text("Use LiveKit")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                whisperUrl = LEGACY_REMOTE_WHISPER_URL
                message = "Legacy whisper.cpp selected. Save to apply."
            },
            modifier = Modifier.weight(1f).testTag("settings-whisper-legacy"),
        ) {
            Text("Use Legacy")
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = whisperUrl,
        onValueChange = {
            whisperUrl = it
            message = null
        },
        label = { Text("Remote Whisper URL") },
        placeholder = { Text(DEFAULT_REMOTE_WHISPER_URL) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("settings-whisper-url"),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = whisperToken,
        onValueChange = {
            whisperToken = it
            message = null
        },
        label = { Text("Remote Whisper token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("settings-whisper-token"),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                whisperUrl = ""
                whisperToken = ""
                saveWhisperSettings()
            },
            modifier = Modifier.weight(1f).testTag("settings-whisper-clear"),
        ) {
            Text("Disable")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = ::saveWhisperSettings,
            modifier = Modifier.weight(1f).testTag("settings-whisper-save"),
        ) {
            Text("Save Whisper")
        }
    }
    message?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BiometricLoginRow(defaultEmail: String?) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(container.biometricStore.canUnlock) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEnroll = container.biometricStore.canEnrollBiometrics()
    val serverUrl = container.tokenStore.serverUrl.orEmpty().ifBlank { container.client.baseUrl }
    val email = container.tokenStore.lastEmail.orEmpty().ifBlank { defaultEmail.orEmpty() }
    val savedApiKey = container.tokenStore.savedApiKey.orEmpty()
    val apiKeyMode = savedApiKey.isNotBlank()

    fun enableBiometrics() {
        if (busy) return
        if (!canEnroll) {
            message = "Biometric authentication is not available on this device."
            return
        }
        val act = activity
        if (act == null) {
            message = "Biometric setup requires a valid screen."
            return
        }
        if (serverUrl.isBlank()) {
            message = "Server URL is missing."
            return
        }
        if (!apiKeyMode && (email.isBlank() || password.isBlank())) {
            message = "Enter your current Agor password to enable biometric login."
            return
        }
        busy = true
        message = null
        scope.launch {
            runCatching {
                if (apiKeyMode) {
                    container.authService.loginWithApiKey(serverUrl, savedApiKey)
                } else {
                    container.authService.login(serverUrl, email, password)
                }
            }.onSuccess {
                if (apiKeyMode) {
                    container.biometricStore.authenticateToSaveApiKeyCredentials(
                        activity = act,
                        serverUrl = container.tokenStore.serverUrl ?: serverUrl,
                        apiKey = savedApiKey,
                    ) { success, reason ->
                        busy = false
                        enabled = container.biometricStore.canUnlock
                        if (success) {
                            message = "Biometric login enabled for API key."
                        } else if (!reason.isNullOrBlank()) {
                            message = reason
                        }
                    }
                } else {
                    container.biometricStore.authenticateToSaveCredentials(
                        activity = act,
                        serverUrl = container.tokenStore.serverUrl ?: serverUrl,
                        email = container.tokenStore.lastEmail ?: email,
                        password = password,
                    ) { success, reason ->
                        busy = false
                        enabled = container.biometricStore.canUnlock
                        if (success) {
                            password = ""
                            message = "Biometric login enabled."
                        } else if (!reason.isNullOrBlank()) {
                            message = reason
                        }
                    }
                }
            }.onFailure { throwable ->
                busy = false
                enabled = container.biometricStore.canUnlock
                message = throwable.message ?: "Could not verify password."
            }
        }
    }

    Text("Security", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Biometric login", style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    enabled && container.biometricStore.prefersApiKeyLogin() -> "Enabled for saved API key."
                    enabled -> "Enabled for ${container.tokenStore.biometricEmail ?: email.ifBlank { "saved login" }}"
                    apiKeyMode && canEnroll -> "Enable biometrics for the API key you used to sign in."
                    canEnroll -> "Enter your password, then enable biometrics."
                    else -> "No strong biometric method is available."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    enableBiometrics()
                } else {
                    container.biometricStore.clearStoredCredentials()
                    enabled = false
                    password = ""
                    message = "Biometric login disabled."
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag("settings-biometric-toggle"),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Server: $serverUrl",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (apiKeyMode) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Credential: saved API key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (email.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Email: $email",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!apiKeyMode) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                if (message == "Enter your current Agor password to enable biometric login.") message = null
            },
            label = { Text("Current password") },
            singleLine = true,
            enabled = !busy && canEnroll,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("settings-biometric-password"),
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { enableBiometrics() },
            enabled = !busy && canEnroll,
            modifier = Modifier.testTag("settings-enable-biometric"),
        ) {
            Text(
                when {
                    busy -> "Verifying…"
                    enabled -> "Refresh biometric login"
                    else -> "Enable biometric login"
                },
            )
        }
        if (enabled) {
            TextButton(
                onClick = {
                    container.biometricStore.clearStoredCredentials()
                    enabled = false
                    password = ""
                    message = "Biometric login disabled."
                },
                enabled = !busy,
                modifier = Modifier.testTag("settings-clear-biometric"),
            ) {
                Text("Disable")
            }
        }
    }
    if (!message.isNullOrBlank()) {
        Text(
            message!!,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun GithubTokenRow() {
    val container = LocalAppContainer.current
    var token by remember { mutableStateOf(container.tokenStore.githubToken.orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    Text(
        "GitHub token for update checks",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Optional. Used only for GitHub release metadata/APK requests to avoid anonymous rate limits.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = token,
        onValueChange = {
            token = it
            saved = false
        },
        label = { Text("GitHub token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("settings-github-token"),
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                container.tokenStore.githubToken = token.trim()
                token = container.tokenStore.githubToken.orEmpty()
                saved = true
            },
            modifier = Modifier.testTag("settings-save-github-token"),
        ) {
            Text("Save token")
        }
        TextButton(
            onClick = {
                container.tokenStore.githubToken = null
                token = ""
                saved = true
            },
            modifier = Modifier.testTag("settings-clear-github-token"),
        ) {
            Text("Clear")
        }
        if (saved) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "Check for updates" row, including download/install flow. Lives in Settings
 * so it doesn't have to share state with the on-launch silent check elsewhere
 * — scoped to the screen, the ViewModel disappears when the user navigates back.
 */
@Composable
internal fun UpdateRow() {
    val container = LocalAppContainer.current
    val vm: UpdateViewModel = viewModel(
        key = "update",
        factory = simpleViewModelFactory { UpdateViewModel(container) },
    )
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        when (val s = state) {
            UpdateViewModel.State.Idle -> {
                TextButton(onClick = vm::checkExplicit, modifier = Modifier.fillMaxWidth().testTag("settings-check-updates")) {
                    Text("Check for updates")
                }
            }
            UpdateViewModel.State.Checking -> {
                Text("Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UpdateViewModel.State.UpToDate -> {
                Text("You're up to date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = vm::checkExplicit, modifier = Modifier.fillMaxWidth().testTag("settings-check-updates")) {
                    Text("Check again")
                }
            }
            is UpdateViewModel.State.Available -> {
                Text("New version available: ${s.info.versionName}",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = vm::download, modifier = Modifier.fillMaxWidth().testTag("settings-download-update")) {
                    Text("Download (${formatSize(s.info.sizeBytes)})")
                }
            }
            is UpdateViewModel.State.Downloading -> {
                val pct = if (s.total > 0) (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                Text("Downloading ${(pct * 100).toInt()}%…",
                    style = MaterialTheme.typography.bodySmall)
            }
            is UpdateViewModel.State.Ready -> {
                Text("Ready to install ${s.info.versionName}",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        if (!vm.install()) vm.requestInstallPermission()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings-install-update"),
                ) {
                    Text(if (vm.canRequestInstall()) "Install" else "Allow installs, then tap again")
                }
            }
            is UpdateViewModel.State.Failed -> {
                Text(s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = vm::dismiss, modifier = Modifier.fillMaxWidth().testTag("settings-dismiss-update")) {
                    Text("Dismiss")
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "?"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format("%.1f MB", mb)
}
