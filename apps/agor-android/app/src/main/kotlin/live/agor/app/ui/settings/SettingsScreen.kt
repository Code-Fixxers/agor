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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.BuildConfig
import live.agor.app.LocalAppContainer
import live.agor.app.network.ConnectionState
import live.agor.app.ui.common.ConnectionIndicator
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.AppViewModel
import live.agor.app.viewmodels.UpdateViewModel

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
