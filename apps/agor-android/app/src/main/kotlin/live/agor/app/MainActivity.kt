package live.agor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import live.agor.app.notifications.AgorNotificationManager
import live.agor.app.ui.AgorRootScreen
import live.agor.app.ui.theme.AgorTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as AgorApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-launch: capture the session id before Compose starts so the very first
        // composition already has the pending route.
        handleEntryIntent(intent)
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
    }

    private fun handleEntryIntent(intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getStringExtra(AgorNotificationManager.EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) {
            container.requestOpenSession(sessionId)
        }
    }
}
