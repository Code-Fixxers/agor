package live.agor.app.ui.settings

import live.agor.app.auth.AuthState
import live.agor.app.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsUploadMessageTest {
    @Test
    fun diagnosticsUploadMessageIncludesFilepathPlaceholder() {
        assertEquals(
            "Attached file(s): {filepath}\n\nPlease review these Android diagnostics logs.",
            diagnosticsUploadMessage(),
        )
    }

    @Test
    fun diagnosticHealthSummaryIncludesAuthSocketHttpLogsAndCrashState() {
        assertEquals(
            "authenticated · socket reconnecting · HTTP https://agor.example.test · 42 log entries · crash report available",
            diagnosticHealthSummary(
                authState = AuthState.Authenticated,
                connectionState = ConnectionState.Reconnecting,
                baseUrl = "https://agor.example.test",
                logEntryCount = 42,
                hasCrashReport = true,
            ),
        )
    }

    @Test
    fun diagnosticHealthSummaryHandlesMissingBaseUrlAndNoCrash() {
        assertEquals(
            "needs login · socket disconnected · HTTP base URL unset · 0 log entries · no crash report",
            diagnosticHealthSummary(
                authState = AuthState.NeedsLogin,
                connectionState = ConnectionState.Disconnected,
                baseUrl = "",
                logEntryCount = 0,
                hasCrashReport = false,
            ),
        )
    }
}
