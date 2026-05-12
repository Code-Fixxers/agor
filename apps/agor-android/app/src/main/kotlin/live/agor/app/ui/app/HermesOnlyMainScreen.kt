package live.agor.app.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import live.agor.app.BuildConfig
import live.agor.app.LocalAppContainer
import live.agor.app.auth.AuthState
import live.agor.app.network.ConnectionState
import live.agor.app.ui.hermes.HermesScreen
import live.agor.app.ui.hermes.HermesSetupScreen
import live.agor.app.ui.settings.DiagnosticsRow
import live.agor.app.ui.settings.GithubTokenRow
import live.agor.app.ui.settings.UpdateRow
import live.agor.app.ui.settings.WhisperServerRow
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.UpdateViewModel

private sealed class HermesOnlyRoute {
    data class Hermes(val sessionId: String? = null) : HermesOnlyRoute()
    data object HermesSetup : HermesOnlyRoute()
    data object Settings : HermesOnlyRoute()
}

@Composable
fun HermesOnlyMainScreen() {
    val container = LocalAppContainer.current
    val updateVm: UpdateViewModel = viewModel(
        key = "hermes-only-update",
        factory = simpleViewModelFactory { UpdateViewModel(container) },
    )
    val updateState by updateVm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val processLifecycle = ProcessLifecycleOwner.get().lifecycle
    val initialRoute: HermesOnlyRoute = if (container.hermesClient.isConfigured) {
        HermesOnlyRoute.Hermes()
    } else {
        HermesOnlyRoute.HermesSetup
    }
    var route by remember { mutableStateOf(initialRoute) }

    LaunchedEffect(Unit) { updateVm.checkSilently() }
    DisposableEffect(processLifecycle) {
        var returnedFromBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (returnedFromBackground) {
                        returnedFromBackground = false
                        container.hermesVoice.resumeForForeground()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    returnedFromBackground = true
                    container.hermesVoice.stopForBackground()
                }
                else -> Unit
            }
        }
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        container.pendingHermesSessionId.collectLatest { id ->
            if (!id.isNullOrBlank()) {
                route = HermesOnlyRoute.Hermes(id)
                container.consumePendingHermesSessionId()
            }
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("hermes-only-root")) {
        when (val r = route) {
            is HermesOnlyRoute.Hermes -> HermesScreen(
                initialSessionId = r.sessionId,
                onOpenDrawer = { route = HermesOnlyRoute.Settings },
                onOpenSettings = { route = HermesOnlyRoute.HermesSetup },
                onOpenSession = {},
            )
            HermesOnlyRoute.HermesSetup -> HermesSetupScreen(
                onClose = { route = HermesOnlyRoute.Hermes() },
                onSaved = { route = HermesOnlyRoute.Hermes() },
            )
            HermesOnlyRoute.Settings -> HermesOnlySettingsScreen(
                onClose = { route = HermesOnlyRoute.Hermes() },
                onOpenHermesSetup = { route = HermesOnlyRoute.HermesSetup },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
        UpdatePrompt(state = updateState, vm = updateVm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HermesOnlySettingsScreen(
    onClose: () -> Unit,
    onOpenHermesSetup: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("hermes-settings-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHermesSetup, modifier = Modifier.testTag("hermes-settings-connection")) {
                        Icon(Icons.Default.Settings, contentDescription = "Hermes connection")
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
            Text("Hermes connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                container.tokenStore.hermesUrl?.takeIf { it.isNotBlank() } ?: "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onOpenHermesSetup,
                modifier = Modifier.testTag("hermes-settings-edit-connection"),
            ) {
                Text(if (container.hermesClient.isConfigured) "Edit Hermes connection" else "Connect Hermes")
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            WhisperServerRow()

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            DiagnosticsRow(
                currentSessionId = null,
                authState = AuthState.Unknown,
                connectionState = ConnectionState.Disconnected,
                baseUrl = container.tokenStore.hermesUrl.orEmpty(),
                allowSendToSession = false,
            )

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GithubTokenRow()
            Spacer(Modifier.height(8.dp))
            UpdateRow()
        }
    }
}
