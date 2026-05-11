package live.agor.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.ui.common.findFragmentActivity

@Composable
fun BiometricUnlockScreen(
    onUnlockSuccess: () -> Unit,
    onLogout: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }

    fun unlock(showError: Boolean) {
        val act = activity
        if (act == null) {
            if (showError) error = "Biometric login requires a valid screen."
            return
        }
        if (busy) return
        busy = true
        if (showError) error = null
        container.biometricStore.authenticateWithBiometrics(
            activity = act,
            negativeButtonText = "Cancel",
            onSuccess = { secret ->
                scope.launch {
                    runCatching {
                        container.authService.loginWithBiometricSecret(secret)
                    }.onSuccess {
                        busy = false
                        onUnlockSuccess()
                    }.onFailure { throwable ->
                        busy = false
                        if (showError) error = throwable.message ?: "Biometric login failed"
                    }
                }
            },
            onFailure = { reason ->
                busy = false
                if (showError && !reason.isNullOrBlank()) error = reason
            },
        )
    }

    LaunchedEffect(activity) {
        if (!attempted && activity != null) {
            attempted = true
            unlock(showError = false)
        }
    }

    val topInsets = WindowInsets.statusBars.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInsets.calculateTopPadding())
            .padding(24.dp)
            .testTag("biometric-unlock-screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Agor is locked",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Use biometrics to unlock your saved login.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { unlock(showError = true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("biometric-unlock-button"),
        ) {
            Text(if (busy) "Unlocking..." else "Unlock with biometrics")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onLogout,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("biometric-unlock-logout"),
        ) {
            Text("Log out")
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }
}
