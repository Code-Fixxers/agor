package live.agor.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

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
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(TAG, "[$category] $message")
                LogLevel.INFO -> Log.i(TAG, "[$category] $message")
                LogLevel.WARNING -> Log.w(TAG, "[$category] $message")
                LogLevel.ERROR -> Log.e(TAG, "[$category] $message")
            }
        } catch (_: RuntimeException) {
            // Android's JVM unit-test stubs throw for Log.*; keep logging side effects in memory.
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear(): Int = synchronized(buffer) {
        val removed = buffer.size
        buffer.clear()
        removed
    }

    fun exportText(): String {
        return exportText(crashReport = null)
    }

    fun exportText(crashReport: String? = null): String {
        return buildString {
            appendLine("Agor Android logs")
            appendLine("Generated: ${Clock.System.now()}")
            appendLine("Entries: ${snapshot().size}")
            crashReport?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Last crash")
                appendLine(redactLogText(it))
            }
            appendLine()
            snapshot().forEach { entry ->
                append(Instant.fromEpochMilliseconds(entry.timestampMillis))
                append(" ")
                append(entry.level.name.padEnd(7))
                append(" [")
                append(entry.category)
                append("] ")
                appendLine(redactLogText(entry.message))
            }
        }
    }
}

class CrashLogStore(private val root: File) {
    private val file: File get() = File(root, "last-crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(): String? = runCatching {
        file.takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    internal fun write(threadName: String, throwable: Throwable) {
        file.parentFile?.mkdirs()
        file.writeText(formatCrashReport(threadName, throwable))
    }
}

internal fun formatCrashReport(threadName: String, throwable: Throwable): String =
    buildString {
        appendLine("Thread: $threadName")
        appendLine("Exception: ${throwable::class.java.name}: ${throwable.message.orEmpty()}")
        appendLine(stackTraceText(throwable))
    }

private fun stackTraceText(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    return writer.toString()
}

internal fun redactLogText(text: String): String {
    var redacted = text
    redacted = redacted.replace(
        Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]+=*"),
        "$1[REDACTED]",
    )
    redacted = redacted.replace(
        Regex("(?i)(api[-_ ]?key\\s*[:=]\\s*)[A-Za-z0-9._~+/-]{8,}"),
        "$1[REDACTED]",
    )
    redacted = redacted.replace(
        Regex("(?i)(accessToken|refreshToken|token|password)([\"'\\s:=]+)[^\"'\\s,}]+"),
        "$1$2[REDACTED]",
    )
    return redacted
}
