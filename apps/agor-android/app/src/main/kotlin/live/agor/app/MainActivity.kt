package live.agor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import live.agor.app.notifications.AgorNotificationManager
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
        handleNativeLoginIntent(intent)
        handleNativeHermesIntent(intent)
    }

    private fun handleNativeLoginIntent(intent: Intent?) {
        if (intent?.action != ACTION_NATIVE_LOGIN) return

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        val shouldConnectSocket = intent.getBooleanExtra(EXTRA_CONNECT_SOCKET, true)
        if (serverUrl.isNullOrBlank()) {
            AppLogger.log(
                "Native login rejected: missing server URL",
                LogLevel.WARNING,
                "Auth",
            )
            return
        }

        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        val email = intent.getStringExtra(EXTRA_EMAIL)
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        lifecycleScope.launch {
            try {
                if (!apiKey.isNullOrBlank()) {
                    if (email != null) {
                        AppLogger.log(
                            "Native login ignores email when API key mode is used: $email",
                            LogLevel.INFO,
                            "Auth",
                        )
                    }
                    container.authService.loginWithApiKey(serverUrl, apiKey)
                } else {
                    if (email.isNullOrBlank() || password.isNullOrBlank()) {
                        AppLogger.log(
                            "Native login rejected: missing email/password",
                            LogLevel.WARNING,
                            "Auth",
                        )
                        return@launch
                    }
                    container.authService.login(serverUrl, email, password)
                }

                if (shouldConnectSocket) {
                    container.socket.connect()
                }

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
