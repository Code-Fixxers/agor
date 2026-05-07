package live.agor.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.LocalAppContainer
import live.agor.app.auth.AuthState
import live.agor.app.ui.app.BiometricUnlockScreen
import live.agor.app.ui.app.ConnectionSetupScreen
import live.agor.app.ui.app.MainScreen
import live.agor.app.viewmodels.AppViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AgorRootScreen() {
    val container = LocalAppContainer.current
    val app: AppViewModel = viewModel(factory = simpleViewModelFactory { AppViewModel(container) })
    val auth by app.authState.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag("agor-root"),
    ) {
        Box(Modifier.fillMaxSize()) {
            when (auth) {
                AuthState.Unknown -> Unit
                AuthState.NeedsLogin -> ConnectionSetupScreen(onLoginSuccess = { app.onLoginSuccess() })
                AuthState.NeedsBiometricUnlock -> BiometricUnlockScreen(
                    onUnlockSuccess = { app.onLoginSuccess() },
                    onLogout = { app.logout() },
                )
                AuthState.Authenticated -> MainScreen(app = app)
            }
        }
    }
}
