package live.agor.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
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

private enum class LoginMode {
    Credentials,
    ApiKey,
}

private const val DEFAULT_SERVER_URL = "http://192.168.88.116:3030"

private fun normalizeUrl(input: String): String = input.trim().trimEnd('/')
private fun normalizeEmailForLogin(input: String): String = input.trim()
private fun canonicalizeEmailForCredentials(input: String): String = input.trim().lowercase()

@Composable
fun ConnectionSetupScreen(onLoginSuccess: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context as? FragmentActivity }

    var mode by remember { mutableStateOf(LoginMode.Credentials) }
    var url by remember { mutableStateOf(container.tokenStore.serverUrl.orEmpty().ifBlank { DEFAULT_SERVER_URL }) }
    var email by remember { mutableStateOf(container.tokenStore.lastEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var autoLoginAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Pre-populate from saved credentials, mirroring iOS .task pre-fill.
        url = container.tokenStore.serverUrl.orEmpty().ifBlank { DEFAULT_SERVER_URL }
        email = container.tokenStore.lastEmail.orEmpty()
    }

    val canUseBiometrics = if (mode == LoginMode.Credentials) {
        container.biometricStore.canUnlockFor(
            normalizeUrl(url),
            canonicalizeEmailForCredentials(email),
        )
    } else {
        false
    }

    fun submitLogin(
        rawUrl: String,
        rawEmail: String,
        rawPassword: String,
        showError: Boolean,
        allowBusy: Boolean = false,
    ) {
        if (busy && !allowBusy) return
        val normalizedUrl = normalizeUrl(rawUrl)
        val normalizedEmail = normalizeEmailForLogin(rawEmail)
        if (normalizedUrl.isBlank() || normalizedEmail.isBlank() || rawPassword.isBlank()) return
        busy = true
        if (showError) error = null
        scope.launch {
            runCatching {
                container.authService.login(normalizedUrl, normalizedEmail, rawPassword)
            }.onSuccess {
                busy = false
                val savedUrl = container.tokenStore.serverUrl ?: normalizedUrl
                val savedEmail = container.tokenStore.lastEmail ?: normalizedEmail
                if (
                    activity != null &&
                    container.biometricStore.canEnrollBiometrics() &&
                    !container.biometricStore.canUnlockFor(savedUrl, canonicalizeEmailForCredentials(savedEmail))
                ) {
                    busy = true
                    container.biometricStore.authenticateToSaveCredentials(
                        activity = activity,
                        serverUrl = savedUrl,
                        email = savedEmail,
                        password = rawPassword,
                    ) { _, reason ->
                        busy = false
                        if (showError && !reason.isNullOrBlank()) error = reason
                        onLoginSuccess()
                    }
                } else {
                    onLoginSuccess()
                }
            }.onFailure { throwable ->
                busy = false
                if (showError) error = throwable.message ?: "Login failed"
            }
        }
    }

    fun submitApiKeyLogin(
        rawUrl: String,
        rawApiKey: String,
        showError: Boolean,
        allowBusy: Boolean = false,
    ) {
        if (busy && !allowBusy) return
        val normalizedUrl = normalizeUrl(rawUrl)
        if (normalizedUrl.isBlank() || rawApiKey.isBlank()) return
        busy = true
        if (showError) error = null
        scope.launch {
            runCatching {
                container.authService.loginWithApiKey(normalizedUrl, rawApiKey)
            }.onSuccess {
                busy = false
                onLoginSuccess()
            }.onFailure { throwable ->
                busy = false
                if (showError) error = throwable.message ?: "Sign in failed"
            }
        }
    }

    fun submitBiometricLogin(showError: Boolean) {
        if (!container.biometricStore.canUnlockFor(normalizeUrl(url), canonicalizeEmailForCredentials(email))) return
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
                    normalizeUrl(url),
                    normalizeEmailForLogin(email),
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

    LaunchedEffect(url, email, mode) {
        if (mode != LoginMode.Credentials) return@LaunchedEffect
        if (autoLoginAttempted || busy) return@LaunchedEffect
        if (container.biometricStore.canUnlockFor(normalizeUrl(url), canonicalizeEmailForCredentials(email))) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            @Composable
            fun loginModeButton(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
                if (selected) {
                    Button(
                        onClick = onClick,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag(tag),
                    ) {
                        Text(text)
                    }
                } else {
                    OutlinedButton(
                        onClick = onClick,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag(tag),
                    ) {
                        Text(text)
                    }
                }
            }

            loginModeButton(
                text = "Email + Password",
                selected = mode == LoginMode.Credentials,
                onClick = { mode = LoginMode.Credentials },
                tag = "login-mode-credentials",
            )
            loginModeButton(
                text = "API Key",
                selected = mode == LoginMode.ApiKey,
                onClick = { mode = LoginMode.ApiKey },
                tag = "login-mode-api-key",
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://100.101.157.56:3030") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("login-server-url"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))

        if (mode == LoginMode.Credentials) {
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
        } else {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Personal API Key") },
                placeholder = { Text("agor_sk_...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("login-api-key"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (mode == LoginMode.Credentials) {
                    submitLogin(url.trim(), email.trim(), password, true)
                } else {
                    submitApiKeyLogin(normalizeUrl(url), apiKey.trim(), true)
                }
            },
            enabled = when (mode) {
                LoginMode.Credentials -> !busy && url.isNotBlank() && email.isNotBlank() && password.isNotBlank()
                LoginMode.ApiKey -> !busy && url.isNotBlank() && apiKey.isNotBlank()
            },
            modifier = Modifier.fillMaxWidth().testTag("login-submit"),
        ) {
            Text(
                when {
                    busy -> "Connecting…"
                    mode == LoginMode.Credentials -> "Sign in"
                    else -> "Sign in with API key"
                },
            )
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
