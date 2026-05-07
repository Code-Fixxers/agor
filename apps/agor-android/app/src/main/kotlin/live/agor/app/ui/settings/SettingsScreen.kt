package live.agor.app.ui.settings

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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.BuildConfig
import live.agor.app.LocalAppContainer
import live.agor.app.network.ConnectionState
import live.agor.app.ui.common.ConnectionIndicator
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.AppViewModel
import live.agor.app.viewmodels.UpdateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: AppViewModel,
    onOpenDrawer: () -> Unit,
    onClose: () -> Unit,
    onOpenHermesSetup: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val user by app.user.collectAsState()
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
private fun BiometricLoginRow(defaultEmail: String?) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(container.biometricStore.canUnlock) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEnroll = container.biometricStore.canEnrollBiometrics()
    val serverUrl = container.tokenStore.serverUrl.orEmpty().ifBlank { container.client.baseUrl }
    val email = container.tokenStore.lastEmail.orEmpty().ifBlank { defaultEmail.orEmpty() }

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
        if (serverUrl.isBlank() || email.isBlank() || password.isBlank()) {
            message = "Enter your current Agor password to enable biometric login."
            return
        }
        busy = true
        message = null
        scope.launch {
            runCatching {
                container.authService.login(serverUrl, email, password)
            }.onSuccess {
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
                    enabled -> "Enabled for ${container.tokenStore.biometricEmail ?: email.ifBlank { "saved login" }}"
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
    if (email.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Email: $email",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun GithubTokenRow() {
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
private fun UpdateRow() {
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
