package live.agor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.util.Base64
import live.agor.app.notifications.AgorNotificationManager
import live.agor.app.automation.AutomationProtocol
import live.agor.app.ui.AgorRootScreen
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.ui.theme.AgorTheme

class MainActivity : ComponentActivity() {

    private companion object {
        const val ACTION_NATIVE_LOGIN = "live.agor.app.action.NATIVE_LOGIN"
        const val ACTION_NATIVE_HERMES_TRIGGER = "live.agor.app.action.NATIVE_HERMES_TRIGGER"
        const val EXTRA_SERVER_URL = "live.agor.app.extra.SERVER_URL"
        const val EXTRA_EMAIL = "live.agor.app.extra.EMAIL"
        const val EXTRA_PASSWORD = "live.agor.app.extra.PASSWORD"
        const val EXTRA_API_KEY = "live.agor.app.extra.API_KEY"
        const val EXTRA_CONNECT_SOCKET = "live.agor.app.extra.CONNECT_SOCKET"
        const val EXTRA_HERMES_WEBHOOK = "live.agor.app.extra.HERMES_WEBHOOK"
        const val EXTRA_HERMES_PROMPT = "live.agor.app.extra.HERMES_PROMPT"
        const val EXTRA_HERMES_URL = "live.agor.app.extra.HERMES_URL"
        const val EXTRA_HERMES_TOKEN = "live.agor.app.extra.HERMES_TOKEN"
    }

    private val container: AppContainer
        get() = (application as AgorApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-launch: capture the session id before Compose starts so the very first
        // composition already has the pending route.
        handleEntryIntent(intent)
        handleControlApiIntent(intent)
        handleNativeLoginIntent(intent)
        handleNativeHermesIntent(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AgorTheme {
                    AgorRootScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-resume: a notification (or deep-link) opened the existing task instance.
        setIntent(intent)
        handleEntryIntent(intent)
        handleControlApiIntent(intent)
        handleNativeLoginIntent(intent)
        handleNativeHermesIntent(intent)
    }

    private fun handleControlApiIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != AutomationProtocol.ACTION_CONTROL && !isControlPayloadPresent(intent)) return

        val rawCommand = intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND_JSON)
        val commandPayload = resolveControlPayload(intent, rawCommand)
        if (commandPayload == null) {
            AppLogger.log("Automation command rejected: missing payload", LogLevel.WARNING, "Automation")
            return
        }

        lifecycleScope.launch {
            var requestId: String? = null
            val responseAction = intent.getStringExtra(AutomationProtocol.EXTRA_RESPONSE_ACTION)
            try {
                val json = commandPayload
                val command = json.optString(AutomationProtocol.KEY_COMMAND, "").trim()
                requestId = json.optString(AutomationProtocol.KEY_REQUEST_ID, null)

                val result = when (command) {
                    AutomationProtocol.COMMAND_LOGIN -> {
                        val serverUrl = json.optString(AutomationProtocol.KEY_SERVER_URL, "")
                        val apiKey = json.optString(AutomationProtocol.KEY_API_KEY, null)
                        val email = json.optString(AutomationProtocol.KEY_EMAIL, null)
                        val password = json.optString(AutomationProtocol.KEY_PASSWORD, null)
                        val connectSocket =
                            try {
                                json.getBoolean(AutomationProtocol.KEY_CONNECT_SOCKET)
                            } catch (_: Exception) {
                                true
                            }
                        executeLogin(serverUrl, email, password, apiKey, connectSocket)
                        "Login successful"
                    }
                    AutomationProtocol.COMMAND_HERMES_TRIGGER -> {
                        val webhook = json.optString(AutomationProtocol.KEY_HERMES_WEBHOOK, "")
                        val prompt = json.optString(AutomationProtocol.KEY_HERMES_PROMPT, "")
                        val rawUrl = json.optString(AutomationProtocol.KEY_HERMES_URL, null)
                        val token = json.optString(AutomationProtocol.KEY_HERMES_TOKEN, null)
                        container.hermesClient.triggerWebhook(webhook, prompt, rawUrl, token)
                        "Hermes trigger executed"
                    }
                    AutomationProtocol.COMMAND_PING -> "PONG"
                    else -> throw IllegalArgumentException("Unknown automation command: $command")
                }

                sendAutomationResponse(responseAction, requestId, true, result)
                AppLogger.log("Automation command [$command] succeeded", LogLevel.INFO, "Automation")
            } catch (t: Throwable) {
                val message = "Automation command failed: ${t.message}"
                sendAutomationResponse(
                    responseAction,
                    requestId,
                    false,
                    message,
                )
                AppLogger.log(message, LogLevel.WARNING, "Automation")
            }
        }
    }

    private fun isControlPayloadPresent(intent: Intent): Boolean {
        return intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND_JSON).isNullOrBlank().not() ||
            intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND_JSON_BASE64).isNullOrBlank().not() ||
            intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND).isNullOrBlank().not()
    }

    private fun resolveControlPayload(
        intent: Intent,
        rawCommand: String?,
    ): JSONObject? {
        if (!rawCommand.isNullOrBlank()) {
            parseCommandJson(rawCommand)?.let {
                return it
            }
        }

        val b64 = intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND_JSON_BASE64)
        if (!b64.isNullOrBlank()) {
            return parseCommandJson(
                runCatching {
                    String(Base64.decode(b64.trim(), Base64.DEFAULT))
                }.getOrNull(),
            )
        }

        val command = intent.getStringExtra(AutomationProtocol.EXTRA_COMMAND)?.trim()
        if (command.isNullOrBlank()) return null
        val json = JSONObject().apply {
            put(AutomationProtocol.KEY_COMMAND, command)
            intent.getStringExtra(AutomationProtocol.EXTRA_REQUEST_ID)?.let {
                put(AutomationProtocol.KEY_REQUEST_ID, it)
            }
            intent.getStringExtra(AutomationProtocol.EXTRA_SERVER_URL_LEGACY)?.let {
                put(AutomationProtocol.KEY_SERVER_URL, it)
            }
            intent.getStringExtra(AutomationProtocol.EXTRA_EMAIL_LEGACY)?.let {
                put(AutomationProtocol.KEY_EMAIL, it)
            }
            intent.getStringExtra(AutomationProtocol.EXTRA_PASSWORD_LEGACY)?.let {
                put(AutomationProtocol.KEY_PASSWORD, it)
            }
            intent.getStringExtra(AutomationProtocol.EXTRA_API_KEY_LEGACY)?.let {
                put(AutomationProtocol.KEY_API_KEY, it)
            }
            if (intent.hasExtra(AutomationProtocol.EXTRA_CONNECT_SOCKET_LEGACY)) {
                put(AutomationProtocol.KEY_CONNECT_SOCKET, intent.getBooleanExtra(AutomationProtocol.EXTRA_CONNECT_SOCKET_LEGACY, true))
            }
        }
        return json
    }

    private fun parseCommandJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw.trim())
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleNativeLoginIntent(intent: Intent?) {
        if (intent?.action != ACTION_NATIVE_LOGIN) return

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        val shouldConnectSocket = intent.getBooleanExtra(EXTRA_CONNECT_SOCKET, true)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        val email = intent.getStringExtra(EXTRA_EMAIL)
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        lifecycleScope.launch {
            try {
                executeLogin(serverUrl, email, password, apiKey, shouldConnectSocket)
                AppLogger.log("Native login successful", LogLevel.INFO, "Auth")
            } catch (t: Throwable) {
                AppLogger.log(
                    "Native login failed: ${t.message}",
                    LogLevel.WARNING,
                    "Auth",
                )
            }
        }
    }

    private suspend fun executeLogin(
        serverUrl: String?,
        email: String?,
        password: String?,
        apiKey: String?,
        shouldConnectSocket: Boolean = true,
    ) {
        if (serverUrl.isNullOrBlank()) {
            throw IllegalArgumentException("Native login rejected: missing server URL")
        }

        if (!apiKey.isNullOrBlank()) {
            if (!email.isNullOrBlank()) {
                AppLogger.log(
                    "Native login ignores email when API key mode is used: $email",
                    LogLevel.INFO,
                    "Auth",
                )
            }
            container.authService.loginWithApiKey(serverUrl, apiKey)
        } else {
            if (email.isNullOrBlank() || password.isNullOrBlank()) {
                throw IllegalArgumentException("Native login rejected: missing email/password")
            }
            container.authService.login(serverUrl, email, password)
        }

        if (shouldConnectSocket) {
            container.socket.connect()
        }
    }

    private fun sendAutomationResponse(
        responseAction: String?,
        requestId: String?,
        success: Boolean,
        message: String,
    ) {
        if (responseAction.isNullOrBlank()) return

        val response = Intent(responseAction).apply {
            setPackage(packageName)
            putExtra(AutomationProtocol.KEY_SUCCESS, success)
            putExtra(AutomationProtocol.KEY_MESSAGE, message)
            if (requestId != null) {
                putExtra(AutomationProtocol.EXTRA_RESPONSE_REQUEST_ID, requestId)
            }
        }
        runCatching { sendBroadcast(response) }
            .onFailure { throwable ->
                AppLogger.log(
                    "Failed to send automation response: ${throwable.message}",
                    LogLevel.WARNING,
                    "Automation",
                )
            }
        }
    }

    private fun handleNativeHermesIntent(intent: Intent?) {
        if (intent?.action != ACTION_NATIVE_HERMES_TRIGGER) return

        val webhook = intent.getStringExtra(EXTRA_HERMES_WEBHOOK)
        val prompt = intent.getStringExtra(EXTRA_HERMES_PROMPT)
        val rawUrl = intent.getStringExtra(EXTRA_HERMES_URL)
        val bearer = intent.getStringExtra(EXTRA_HERMES_TOKEN)

        if (webhook.isNullOrBlank() || prompt.isNullOrBlank()) {
            AppLogger.log(
                "Native Hermes trigger rejected: webhook and prompt are both required",
                LogLevel.WARNING,
                "Hermes",
            )
            return
        }

        lifecycleScope.launch {
            try {
                val reply = container.hermesClient.triggerWebhook(webhook, prompt, rawUrl, bearer)
                AppLogger.log(
                    "Native Hermes trigger [$webhook]: ${reply.take(1_000)}",
                    LogLevel.INFO,
                    "Hermes",
                )
            } catch (t: Throwable) {
                AppLogger.log(
                    "Native Hermes trigger failed: ${t.message}",
                    LogLevel.WARNING,
                    "Hermes",
                )
            }
        }
    }

    private fun handleEntryIntent(intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getStringExtra(AgorNotificationManager.EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) {
            container.requestOpenSession(sessionId)
        }
    }
}
