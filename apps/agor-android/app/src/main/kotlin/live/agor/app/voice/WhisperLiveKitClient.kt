package live.agor.app.voice

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class WhisperLiveKitClient(
    rawBaseUrl: String,
    private val bearer: String?,
) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun open(
        onTranscript: (String) -> Unit,
        onFinalTranscript: (String) -> Unit = {},
        onConfig: (Boolean) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): WhisperLiveKitStream {
        val stream = WhisperLiveKitStream(
            url = whisperLiveKitAsrUrl(baseUrl),
            bearer = bearer,
            http = http,
            onTranscript = onTranscript,
            onFinalTranscript = onFinalTranscript,
            onConfig = onConfig,
            onFailure = onFailure,
        )
        stream.open()
        return stream
    }
}

fun whisperLiveKitAsrUrl(rawBaseUrl: String): String {
    val baseUrl = rawBaseUrl.trim().trimEnd('/')
    val wsBase = when {
        baseUrl.startsWith("https://", ignoreCase = true) -> "wss://" + baseUrl.drop("https://".length)
        baseUrl.startsWith("http://", ignoreCase = true) -> "ws://" + baseUrl.drop("http://".length)
        baseUrl.startsWith("ws://", ignoreCase = true) || baseUrl.startsWith("wss://", ignoreCase = true) -> baseUrl
        else -> "ws://$baseUrl"
    }
    return "$wsBase/asr"
}

class WhisperLiveKitStream internal constructor(
    private val url: String,
    private val bearer: String?,
    private val http: OkHttpClient,
    private val onTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
    private val onConfig: (Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) : WebSocketListener() {
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val transcriptState = WhisperLiveKitTranscriptState()
    private var webSocket: WebSocket? = null
    @Volatile
    private var serverUseAudioWorklet: Boolean? = null

    val isConnected: Boolean
        get() = connected.get() && !closed.get()

    val useAudioWorklet: Boolean?
        get() = serverUseAudioWorklet

    fun open() {
        val req = Request.Builder()
            .url(url)
            .apply {
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }
            .build()
        AppLogger.log("WhisperLiveKit WS connecting: $url", LogLevel.INFO, "Voice")
        webSocket = http.newWebSocket(req, this)
    }

    fun sendPcm(samples: ShortArray) {
        val ws = webSocket ?: return
        if (!isConnected || samples.isEmpty()) return
        if (serverUseAudioWorklet != true) return
        ws.send(ByteString.of(*pcmBytes(samples)))
    }

    fun sendWebmChunk(bytes: ByteArray, length: Int = bytes.size) {
        val ws = webSocket ?: return
        if (!isConnected || length <= 0) return
        ws.send(bytes.toByteString(0, length))
    }

    fun finish() {
        val ws = webSocket ?: return
        if (!isConnected) return
        runCatching { ws.send(ByteString.EMPTY) }
            .onFailure {
                AppLogger.log("WhisperLiveKit finish signal failed: ${it.message}", LogLevel.WARNING, "Voice")
            }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { webSocket?.close(1000, "voice input stopped") }
        connected.set(false)
        webSocket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        connected.set(true)
        AppLogger.log("WhisperLiveKit WS connected: code=${response.code}", LogLevel.INFO, "Voice")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching { handleMessage(text) }
            .onFailure {
                AppLogger.log("WhisperLiveKit message parse failed: ${it.message}", LogLevel.WARNING, "Voice")
            }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (closed.get()) return
        connected.set(false)
        val detail = "WhisperLiveKit WS failed: ${t.message ?: "unknown"} code=${response?.code}"
        AppLogger.log(detail, LogLevel.WARNING, "Voice")
        onFailure(detail)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        connected.set(false)
        closed.set(true)
        AppLogger.log("WhisperLiveKit WS closed: code=$code reason=$reason", LogLevel.INFO, "Voice")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        connected.set(false)
        AppLogger.log("WhisperLiveKit WS closing: code=$code reason=$reason", LogLevel.DEBUG, "Voice")
    }

    private fun handleMessage(text: String) {
        when (val update = transcriptState.handle(text)) {
            is WhisperLiveKitMessage.Config -> {
                serverUseAudioWorklet = update.useAudioWorklet
                val mode = if (update.useAudioWorklet) "PCM AudioWorklet" else "WebM MediaRecorder"
                AppLogger.log("WhisperLiveKit config received: mode=$mode", LogLevel.INFO, "Voice")
                onConfig(update.useAudioWorklet)
            }
            is WhisperLiveKitMessage.Transcript -> {
                AppLogger.log(
                    "WhisperLiveKit ${if (update.isFinal) "final" else "interim"} transcript: chars=${update.text.length}",
                    if (update.isFinal) LogLevel.INFO else LogLevel.DEBUG,
                    "Voice",
                )
                if (update.isFinal) onFinalTranscript(update.text)
                onTranscript(update.text)
            }
            is WhisperLiveKitMessage.Metadata -> AppLogger.log("WhisperLiveKit metadata received", LogLevel.DEBUG, "Voice")
            is WhisperLiveKitMessage.SpeechStarted -> AppLogger.log("WhisperLiveKit speech started", LogLevel.DEBUG, "Voice")
            is WhisperLiveKitMessage.UtteranceEnd -> AppLogger.log("WhisperLiveKit utterance ended", LogLevel.DEBUG, "Voice")
            is WhisperLiveKitMessage.Error -> {
                AppLogger.log("WhisperLiveKit server error: ${update.message}", LogLevel.WARNING, "Voice")
                onFailure(update.message)
            }
            is WhisperLiveKitMessage.Ignored -> AppLogger.log("WhisperLiveKit WS message ignored: type=${update.type}", LogLevel.DEBUG, "Voice")
        }
    }
}

private fun pcmBytes(samples: ShortArray): ByteArray {
    val out = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) out.putShort(sample)
    return out.array()
}

internal sealed interface WhisperLiveKitMessage {
    data class Config(val useAudioWorklet: Boolean) : WhisperLiveKitMessage
    data class Transcript(val text: String, val isFinal: Boolean) : WhisperLiveKitMessage
    data class Error(val message: String) : WhisperLiveKitMessage
    data object Metadata : WhisperLiveKitMessage
    data object SpeechStarted : WhisperLiveKitMessage
    data object UtteranceEnd : WhisperLiveKitMessage
    data class Ignored(val type: String) : WhisperLiveKitMessage
}

internal class WhisperLiveKitTranscriptState {
    private var deepgramCommittedTranscript = ""
    private var fullStateTranscript = ""

    fun handle(text: String): WhisperLiveKitMessage {
        val obj = JSONObject(text)
        if (obj.optString("status") == "error") {
            val message = obj.optString("error")
                .ifBlank { obj.optString("message") }
                .ifBlank { "WhisperLiveKit server error" }
            return WhisperLiveKitMessage.Error(message)
        }
        return when (val type = obj.optString("type")) {
            "config" -> WhisperLiveKitMessage.Config(obj.optBoolean("useAudioWorklet"))
            "Metadata" -> WhisperLiveKitMessage.Metadata
            "SpeechStarted" -> WhisperLiveKitMessage.SpeechStarted
            "UtteranceEnd" -> WhisperLiveKitMessage.UtteranceEnd
            "Results" -> handleDeepgramResults(obj)
            "ready_to_stop" -> finalFullStateTranscript()
            "diff", "snapshot" -> WhisperLiveKitMessage.Ignored(type)
            else -> {
                if (obj.has("lines") || obj.has("buffer_transcription") || obj.has("buffer_translation")) {
                    handleFullState(obj)
                } else {
                    WhisperLiveKitMessage.Ignored(type.ifBlank { "unknown" })
                }
            }
        }
    }

    private fun handleFullState(obj: JSONObject): WhisperLiveKitMessage {
        val parts = mutableListOf<String>()
        val lines = obj.optJSONArray("lines")
        if (lines != null) {
            for (i in 0 until lines.length()) {
                val line = lines.optJSONObject(i) ?: continue
                val text = cleanTranscript(line.optString("text"))
                if (text.isNotBlank()) parts += text
            }
        }
        cleanTranscript(obj.optString("buffer_transcription")).takeIf { it.isNotBlank() }?.let(parts::add)

        val current = cleanTranscript(parts.joinToString(" "))
        if (current.isBlank() || current == fullStateTranscript) {
            return WhisperLiveKitMessage.Ignored(obj.optString("status").ifBlank { "full_state" })
        }
        fullStateTranscript = current
        return WhisperLiveKitMessage.Transcript(current, isFinal = false)
    }

    private fun finalFullStateTranscript(): WhisperLiveKitMessage {
        val final = fullStateTranscript.ifBlank { deepgramCommittedTranscript }
        return if (final.isBlank()) {
            WhisperLiveKitMessage.Ignored("ready_to_stop")
        } else {
            WhisperLiveKitMessage.Transcript(final, isFinal = true)
        }
    }

    private fun handleDeepgramResults(obj: JSONObject): WhisperLiveKitMessage {
        val alternative = obj
            .optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?: return WhisperLiveKitMessage.Ignored("Results")
        val transcript = cleanTranscript(alternative.optString("transcript"))
        if (transcript.isBlank()) return WhisperLiveKitMessage.Ignored("Results")
        val isFinal = obj.optBoolean("is_final") || obj.optBoolean("speech_final")
        if (!isFinal) {
            val live = mergeTranscript(deepgramCommittedTranscript, transcript)
            return WhisperLiveKitMessage.Transcript(live, isFinal = false)
        }

        deepgramCommittedTranscript = mergeTranscript(deepgramCommittedTranscript, transcript)
        return WhisperLiveKitMessage.Transcript(deepgramCommittedTranscript, isFinal = true)
    }

    private fun mergeTranscript(existing: String, incoming: String): String {
        val left = cleanTranscript(existing)
        val right = cleanTranscript(incoming)
        if (left.isBlank()) return right
        if (right.isBlank() || right == left) return left
        if (right.startsWith(left, ignoreCase = true)) return right
        if (left.endsWith(right, ignoreCase = true)) return left
        return cleanTranscript("$left $right")
    }
}
