package live.agor.app.ui.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import live.agor.app.LocalAppContainer
import live.agor.app.network.HermesClient

/**
 * One-screen connection wizard for the Hermes Agent endpoint.
 *
 * The user types the base URL (e.g. `http://100.101.157.56:8642`), the bearer
 * token (the `API_SERVER_KEY` from the Hermes container's sops secret), and an
 * optional model override. We probe `/v1/models` to validate before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesSetupScreen(
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(container.tokenStore.hermesUrl ?: "") }
    var token by remember { mutableStateOf(container.tokenStore.hermesToken ?: "") }
    var model by remember { mutableStateOf(container.tokenStore.hermesModel ?: HermesClient.DEFAULT_MODEL) }
    var whisperUrl by remember { mutableStateOf(container.tokenStore.remoteWhisperUrl ?: "") }
    var whisperToken by remember { mutableStateOf(container.tokenStore.remoteWhisperToken ?: "") }
    var probing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes connection") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Hermes runs in a container on your NixOS host and exposes an OpenAI-compatible API. Enter the Tailnet URL and the API server bearer token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; status = null },
                label = { Text("Base URL") },
                placeholder = { Text("http://100.101.157.56:8642") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; status = null },
                label = { Text("API server token") },
                placeholder = { Text("hex bearer (API_SERVER_KEY)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model name") },
                placeholder = { Text(HermesClient.DEFAULT_MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Voice transcription uses the bundled local Whisper model when this build includes it. Optionally set a remote whisper.cpp server for faster transcription; local Whisper is used if the remote server is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = whisperUrl,
                onValueChange = { whisperUrl = it },
                label = { Text("Remote Whisper URL") },
                placeholder = { Text("http://host:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = whisperToken,
                onValueChange = { whisperToken = it },
                label = { Text("Remote Whisper token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            status?.let {
                Text(
                    it,
                    color = if (statusOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (probing) {
                    Box(modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            probing = true
                            statusOk = false
                            val models = container.hermesClient.probe(url.trim(), token.trim())
                            probing = false
                            if (models == null) {
                                status = "Could not reach $url. Check Tailscale + token."
                            } else {
                                statusOk = true
                                status = "OK — models: ${models.joinToString(", ").take(160)}"
                            }
                        }
                    },
                    enabled = !probing && url.isNotBlank() && token.isNotBlank(),
                ) {
                    Text("Test connection")
                }
                Button(
                    onClick = {
                        container.tokenStore.hermesUrl = url.trim().trimEnd('/')
                        container.tokenStore.hermesToken = token.trim()
                        container.tokenStore.hermesModel = model.trim().ifBlank { HermesClient.DEFAULT_MODEL }
                        container.tokenStore.remoteWhisperUrl = whisperUrl.trim().trimEnd('/').ifBlank { null }
                        container.tokenStore.remoteWhisperToken = whisperToken.trim().ifBlank { null }
                        onSaved()
                    },
                    enabled = url.isNotBlank() && token.isNotBlank(),
                ) {
                    Text("Save")
                }
            }
        }
    }

    LaunchedEffect(Unit) { /* no-op; future: prefill from Tailnet discovery */ }
}
