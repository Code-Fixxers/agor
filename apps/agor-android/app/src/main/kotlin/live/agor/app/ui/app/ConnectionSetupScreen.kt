package live.agor.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer

@Composable
fun ConnectionSetupScreen(onLoginSuccess: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context as? FragmentActivity }

    var url by remember { mutableStateOf(container.tokenStore.serverUrl.orEmpty()) }
    var email by remember { mutableStateOf(container.tokenStore.lastEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var autoLoginAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Pre-populate from saved credentials, mirroring iOS .task pre-fill.
        url = container.tokenStore.serverUrl.orEmpty()
        email = container.tokenStore.lastEmail.orEmpty()
    }

    val canUseBiometrics = container.biometricStore.canUnlockFor(url.trim(), email.trim())

    fun submitLogin(
        rawUrl: String,
        rawEmail: String,
        rawPassword: String,
        showError: Boolean,
        allowBusy: Boolean = false,
    ) {
        if (busy && !allowBusy) return
        if (rawUrl.isBlank() || rawEmail.isBlank() || rawPassword.isBlank()) return
        busy = true
        if (showError) error = null
        scope.launch {
            runCatching {
                container.authService.login(rawUrl, rawEmail, rawPassword)
            }.onSuccess {
                busy = false
                onLoginSuccess()
            }.onFailure { throwable ->
                busy = false
                if (showError) error = throwable.message ?: "Login failed"
            }
        }
    }

    fun submitBiometricLogin(showError: Boolean) {
        if (!container.biometricStore.canUnlockFor(url.trim(), email.trim())) return
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
            onSuccess = { recoveredPassword ->
                submitLogin(
                    url.trim(),
                    email.trim(),
                    recoveredPassword,
                    showError,
                    allowBusy = true,
                )
            },
            onFailure = { reason ->
                if (!showError) {
                    busy = false
                    return@authenticateWithBiometrics
                }
                busy = false
                if (!reason.isNullOrBlank()) error = reason
            },
        )
    }

    LaunchedEffect(url, email) {
        if (autoLoginAttempted || busy) return@LaunchedEffect
        if (container.biometricStore.canUnlockFor(url, email)) {
            autoLoginAttempted = true
            submitBiometricLogin(showError = false)
        }
    }

    val topInsets = WindowInsets.statusBars.asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInsets.calculateTopPadding())
            .padding(24.dp)
            .imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Agor",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("agor.local or https://agor.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("login-server-url"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("login-email"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("login-password"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                submitLogin(url.trim(), email.trim(), password, true)
            },
            enabled = !busy && url.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("login-submit"),
        ) {
            Text(if (busy) "Connecting…" else "Sign in")
        }
        if (canUseBiometrics) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (busy) return@Button
                    submitBiometricLogin(showError = true)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("login-biometric"),
            ) {
                Text(if (busy) "Unlocking…" else "Sign in with biometrics")
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
