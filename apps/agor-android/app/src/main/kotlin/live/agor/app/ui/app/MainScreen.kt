package live.agor.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.ui.chat.ChatScreen
import live.agor.app.ui.hermes.HermesScreen
import live.agor.app.ui.hermes.HermesSetupScreen
import live.agor.app.ui.nav.SidebarScreen
import live.agor.app.ui.settings.SettingsScreen
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.AppViewModel
import live.agor.app.viewmodels.NavigationViewModel

sealed class MainRoute {
    data object EmptyHome : MainRoute()
    data object Hermes : MainRoute()
    data object HermesSetup : MainRoute()
    data class Chat(val sessionId: String) : MainRoute()
    data object Settings : MainRoute()
}

@Composable
fun MainScreen(app: AppViewModel) {
    val container = LocalAppContainer.current
    val nav: NavigationViewModel = viewModel(factory = simpleViewModelFactory { NavigationViewModel(container) })

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Default to Hermes when configured — that's the orchestrator-first flow.
    // Otherwise fall back to the original empty-home + sidebar entry pattern.
    val initialRoute: MainRoute = if (container.hermesClient.isConfigured) MainRoute.Hermes
    else MainRoute.EmptyHome
    var route by remember { mutableStateOf(initialRoute) }

    LaunchedEffect(Unit) { nav.start() }
    DisposableEffect(Unit) { onDispose { nav.stop() } }

    // Route to a chat when an external entry point (notification tap, deep-link)
    // requests a specific session. Consume immediately so the same id won't re-fire
    // on recomposition or process restart.
    val pending by app.pendingSessionId.collectAsState()
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        route = MainRoute.Chat(id)
        drawerState.close()
        app.consumePendingSessionId()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                SidebarScreen(
                    nav = nav,
                    onSelectSession = { sessionId ->
                        route = MainRoute.Chat(sessionId)
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        route = MainRoute.Settings
                        scope.launch { drawerState.close() }
                    },
                    onOpenHermes = {
                        route = if (container.hermesClient.isConfigured) {
                            MainRoute.Hermes
                        } else {
                            MainRoute.HermesSetup
                        }
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        when (val r = route) {
            is MainRoute.EmptyHome -> EmptyHome(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                hermesConfigured = container.hermesClient.isConfigured,
                onOpenHermes = {
                    route = if (container.hermesClient.isConfigured) MainRoute.Hermes
                    else MainRoute.HermesSetup
                },
            )
            is MainRoute.Hermes -> HermesScreen(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSettings = { route = MainRoute.HermesSetup },
            )
            is MainRoute.HermesSetup -> HermesSetupScreen(
                onClose = { route = if (container.hermesClient.isConfigured) MainRoute.Hermes else MainRoute.EmptyHome },
                onSaved = { route = MainRoute.Hermes },
            )
            is MainRoute.Chat -> ChatScreen(
                sessionId = r.sessionId,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onClose = { route = if (container.hermesClient.isConfigured) MainRoute.Hermes else MainRoute.EmptyHome },
            )
            is MainRoute.Settings -> SettingsScreen(
                app = app,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onClose = { route = if (container.hermesClient.isConfigured) MainRoute.Hermes else MainRoute.EmptyHome },
                onOpenHermesSetup = { route = MainRoute.HermesSetup },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(
    onOpenDrawer: () -> Unit,
    hermesConfigured: Boolean,
    onOpenHermes: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agor") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open drawer")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (hermesConfigured) "Tap Hermes to open the chat, or pick a session from the drawer."
                else "Connect Hermes to start chatting with your orchestrator agent.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Button(onClick = onOpenHermes) {
                Text(if (hermesConfigured) "Open Hermes" else "Connect Hermes")
            }
        }
    }
}
