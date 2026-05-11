package live.agor.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
)

object AppLogger {
    private const val TAG = "Agor"
    private const val MAX_BUFFER = 1024
    private val buffer = ArrayDeque<LogEntry>()
    private val _stream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val stream: SharedFlow<LogEntry> = _stream.asSharedFlow()

    fun log(message: String, level: LogLevel = LogLevel.INFO, category: String = "App") {
        val entry = LogEntry(Clock.System.now().toEpochMilliseconds(), level, category, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        _stream.tryEmit(entry)
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, "[$category] $message")
            LogLevel.INFO -> Log.i(TAG, "[$category] $message")
            LogLevel.WARNING -> Log.w(TAG, "[$category] $message")
            LogLevel.ERROR -> Log.e(TAG, "[$category] $message")
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun exportText(): String {
        return buildString {
            appendLine("Agor Android logs")
            appendLine("Generated: ${Clock.System.now()}")
            appendLine("Entries: ${snapshot().size}")
            appendLine()
            snapshot().forEach { entry ->
                append(Instant.fromEpochMilliseconds(entry.timestampMillis))
                append(" ")
                append(entry.level.name.padEnd(7))
                append(" [")
                append(entry.category)
                append("] ")
                appendLine(entry.message)
            }
        }
    }
}
