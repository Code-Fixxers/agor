package live.agor.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        onFailure: (String) -> Unit = {},
    ): WhisperLiveKitStream {
        val stream = WhisperLiveKitStream(
            url = whisperLiveKitAsrUrl(baseUrl),
            bearer = bearer,
            http = http,
            onTranscript = onTranscript,
            onFinalTranscript = onFinalTranscript,
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
    private val onFailure: (String) -> Unit,
) : WebSocketListener() {
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var committedTranscript = ""
    private var sentContainerHeader = false

    val isConnected: Boolean
        get() = connected.get() && !closed.get()

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
        if (!sentContainerHeader) {
            sentContainerHeader = true
            ws.send(ByteString.of(*streamingWavHeader()))
        }
        ws.send(ByteString.of(*pcmBytes(samples)))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { webSocket?.send("""{"type":"CloseStream"}""") }
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
        val obj = JSONObject(text)
        when (val type = obj.optString("type")) {
            "Metadata" -> AppLogger.log("WhisperLiveKit metadata received", LogLevel.DEBUG, "Voice")
            "SpeechStarted" -> AppLogger.log("WhisperLiveKit speech started", LogLevel.DEBUG, "Voice")
            "UtteranceEnd" -> AppLogger.log("WhisperLiveKit utterance ended", LogLevel.DEBUG, "Voice")
            "Results" -> handleResults(obj)
            else -> AppLogger.log("WhisperLiveKit WS message ignored: type=$type", LogLevel.DEBUG, "Voice")
        }
    }

    private fun handleResults(obj: JSONObject) {
        val alternative = obj
            .optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?: return
        val transcript = cleanTranscript(alternative.optString("transcript"))
        if (transcript.isBlank()) return
        val isFinal = obj.optBoolean("is_final") || obj.optBoolean("speech_final")
        if (isFinal) {
            committedTranscript = listOf(committedTranscript, transcript)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .let(::cleanTranscript)
            AppLogger.log("WhisperLiveKit final transcript: chars=${committedTranscript.length}", LogLevel.INFO, "Voice")
            onFinalTranscript(committedTranscript)
            onTranscript(committedTranscript)
        } else {
            val live = listOf(committedTranscript, transcript)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .let(::cleanTranscript)
            AppLogger.log("WhisperLiveKit interim transcript: chars=${live.length}", LogLevel.DEBUG, "Voice")
            onTranscript(live)
        }
    }
}

private fun pcmBytes(samples: ShortArray): ByteArray {
    val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) out.putShort(sample)
    return out.array()
}

private fun streamingWavHeader(sampleRate: Int = AudioCapture.SAMPLE_RATE): ByteArray {
    val out = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII))
    out.putInt(-1)
    out.put("WAVE".toByteArray(Charsets.US_ASCII))
    out.put("fmt ".toByteArray(Charsets.US_ASCII))
    out.putInt(16)
    out.putShort(1)
    out.putShort(1)
    out.putInt(sampleRate)
    out.putInt(sampleRate * 2)
    out.putShort(2)
    out.putShort(16)
    out.put("data".toByteArray(Charsets.US_ASCII))
    out.putInt(-1)
    return out.array()
}
