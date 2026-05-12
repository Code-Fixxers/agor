package live.agor.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    @Test
    fun clearRemovesBufferedEntriesAndReturnsCount() {
        seedLogs(
            LogEntry(1L, LogLevel.INFO, "Test", "First"),
            LogEntry(2L, LogLevel.WARNING, "Test", "Second"),
        )

        val removed = AppLogger.clear()

        assertEquals(2, removed)
        assertTrue(AppLogger.snapshot().isEmpty())
    }

    @Test
    fun exportRedactsCommonSecrets() {
        seedLogs(
            LogEntry(
                1L,
                LogLevel.INFO,
                "Network",
                "Authorization: Bearer abc.def.ghi apiKey=sk-test-secret accessToken: jwt-secret password=hunter2",
            ),
        )

        val exported = AppLogger.exportText()

        assertTrue(exported.contains("Authorization: Bearer [REDACTED]"))
        assertTrue(exported.contains("apiKey=[REDACTED]"))
        assertTrue(exported.contains("accessToken: [REDACTED]"))
        assertTrue(exported.contains("password=[REDACTED]"))
        assertTrue(!exported.contains("sk-test-secret"))
        assertTrue(!exported.contains("jwt-secret"))
        assertTrue(!exported.contains("hunter2"))
    }

    @Test
    fun exportIncludesRedactedCrashReport() {
        seedLogs()
        val exported = AppLogger.exportText(
            formatCrashReport("main", IllegalStateException("token=secret-token")),
        )

        assertTrue(exported.contains("Last crash"))
        assertTrue(exported.contains("Thread: main"))
        assertTrue(exported.contains("token=[REDACTED]"))
        assertTrue(!exported.contains("secret-token"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedLogs(vararg entries: LogEntry) {
        val field = AppLogger::class.java.getDeclaredField("buffer").apply {
            isAccessible = true
        }
        val buffer = field.get(AppLogger) as ArrayDeque<LogEntry>
        synchronized(buffer) {
            buffer.clear()
            entries.forEach(buffer::addLast)
        }
    }
}
