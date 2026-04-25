package live.agor.app.network

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.Polling
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import live.agor.app.models.AgorTask
import live.agor.app.models.Message
import live.agor.app.models.Session
import live.agor.app.models.StreamingChunkEvent
import live.agor.app.models.StreamingEndEvent
import live.agor.app.models.StreamingErrorEvent
import live.agor.app.models.StreamingStartEvent
import live.agor.app.models.ThinkingChunkEvent
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import org.json.JSONObject
import java.net.URI

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting }

/**
 * Socket.IO client. Mirrors apps/agor-ios/AgorApp/Services/SocketService.swift:
 *
 * - Connect with `Authorization: Bearer <token>` extraHeader (lets Feathers middleware
 *   set socket.feathers.user for service calls).
 * - After transport connect, emit `create authentication` so the connection joins the
 *   authenticated channel and gets real-time service events.
 * - On 401 via connect_error, refresh the JWT and reconnect.
 */
class SocketService(
    private val client: AgorClient,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Multi-handler subscriptions (synchronous fan-out from socket thread)
    private val sessionPatched = mutableListOf<(Session) -> Unit>()
    private val taskCreated = mutableListOf<(AgorTask) -> Unit>()
    private val taskPatched = mutableListOf<(AgorTask) -> Unit>()
    private val messageCreated = mutableListOf<(Message) -> Unit>()
    private val messagePatched = mutableListOf<(Message) -> Unit>()

    private val _streamingStart = MutableSharedFlow<StreamingStartEvent>(extraBufferCapacity = 64)
    val streamingStart: SharedFlow<StreamingStartEvent> = _streamingStart.asSharedFlow()
    private val _streamingChunk = MutableSharedFlow<StreamingChunkEvent>(extraBufferCapacity = 256)
    val streamingChunk: SharedFlow<StreamingChunkEvent> = _streamingChunk.asSharedFlow()
    private val _streamingEnd = MutableSharedFlow<StreamingEndEvent>(extraBufferCapacity = 64)
    val streamingEnd: SharedFlow<StreamingEndEvent> = _streamingEnd.asSharedFlow()
    private val _streamingError = MutableSharedFlow<StreamingErrorEvent>(extraBufferCapacity = 64)
    val streamingError: SharedFlow<StreamingErrorEvent> = _streamingError.asSharedFlow()
    private val _thinkingChunk = MutableSharedFlow<ThinkingChunkEvent>(extraBufferCapacity = 256)
    val thinkingChunk: SharedFlow<ThinkingChunkEvent> = _thinkingChunk.asSharedFlow()

    var onAuthFailure: (() -> Unit)? = null

    private var socket: Socket? = null

    fun onSessionPatched(handler: (Session) -> Unit) { sessionPatched += handler }
    fun onTaskCreated(handler: (AgorTask) -> Unit) { taskCreated += handler }
    fun onTaskPatched(handler: (AgorTask) -> Unit) { taskPatched += handler }
    fun onMessageCreated(handler: (Message) -> Unit) { messageCreated += handler }
    fun onMessagePatched(handler: (Message) -> Unit) { messagePatched += handler }

    fun connect() {
        val url = client.baseUrl
        if (url.isEmpty()) {
            logger.log("Cannot connect: missing URL", LogLevel.WARNING, "Socket")
            return
        }
        logger.log("Connecting to $url", category = "Socket")
        _state.value = ConnectionState.Connecting

        val opts = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionDelay(2000)
            .setReconnectionDelayMax(30000)
            .setTransports(arrayOf(Polling.NAME, WebSocket.NAME))
            .build()

        // Auth header for Feathers middleware
        val token = client.tokensRef.accessToken
        if (token != null) {
            opts.extraHeaders = mapOf("Authorization" to listOf("Bearer $token"))
        }

        val s = IO.socket(URI(url), opts)
        attachHandlers(s)
        socket = s
        s.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        _state.value = ConnectionState.Disconnected
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    private fun attachHandlers(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            logger.log("Transport connected — running Feathers auth", LogLevel.INFO, "Socket")
            authenticateWithFeathers()
        }
        s.on(Socket.EVENT_DISCONNECT) {
            logger.log("Socket disconnected", category = "Socket")
            _state.value = ConnectionState.Disconnected
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val errStr = args.joinToString(", ") { it?.toString().orEmpty() }
            logger.log("connect_error: $errStr", LogLevel.ERROR, "Socket")
            val authy = errStr.contains("invalid", true) ||
                errStr.contains("expired", true) ||
                errStr.contains("token", true) ||
                errStr.contains("auth", true)
            if (authy) {
                scope.launch {
                    val refreshed = client.refresh()
                    if (refreshed) reconnect() else onAuthFailure?.invoke()
                }
            }
        }

        s.on("sessions patched") { args -> decode(args, Session.serializer()) { sessionPatched.forEach { h -> h(it) } } }
        s.on("tasks created") { args -> decode(args, AgorTask.serializer()) { taskCreated.forEach { h -> h(it) } } }
        s.on("tasks patched") { args -> decode(args, AgorTask.serializer()) { taskPatched.forEach { h -> h(it) } } }
        s.on("messages created") { args -> decode(args, Message.serializer()) { messageCreated.forEach { h -> h(it) } } }
        s.on("messages patched") { args -> decode(args, Message.serializer()) { messagePatched.forEach { h -> h(it) } } }

        s.on("messages streaming:start") { args -> decode(args, StreamingStartEvent.serializer()) { _streamingStart.tryEmit(it) } }
        s.on("messages streaming:chunk") { args -> decode(args, StreamingChunkEvent.serializer()) { _streamingChunk.tryEmit(it) } }
        s.on("messages streaming:end") { args -> decode(args, StreamingEndEvent.serializer()) { _streamingEnd.tryEmit(it) } }
        s.on("messages streaming:error") { args -> decode(args, StreamingErrorEvent.serializer()) { _streamingError.tryEmit(it) } }
        s.on("messages thinking:chunk") { args -> decode(args, ThinkingChunkEvent.serializer()) { _thinkingChunk.tryEmit(it) } }
    }

    private fun authenticateWithFeathers() {
        val s = socket ?: return
        val token = client.tokensRef.accessToken
        if (token == null) {
            logger.log("Feathers auth skipped — no token", LogLevel.WARNING, "Socket")
            onAuthFailure?.invoke()
            return
        }
        val payload = JSONObject().apply {
            put("strategy", "jwt")
            put("accessToken", token)
        }
        s.emit("create", "authentication", payload, io.socket.client.Ack { ackArgs ->
            val first = ackArgs.firstOrNull()
            if (first is JSONObject && first.has("code")) {
                val code = first.optInt("code")
                val message = first.optString("message", "unknown")
                logger.log("Feathers auth failed ($code): $message", LogLevel.ERROR, "Socket")
                if (code == 401 || code == 403) {
                    scope.launch {
                        if (client.refresh()) authenticateWithFeathers() else onAuthFailure?.invoke()
                    }
                }
            } else {
                logger.log("Feathers auth success — joined authenticated channel", LogLevel.INFO, "Socket")
                _state.value = ConnectionState.Connected
            }
        })
    }

    /**
     * Generic Socket.IO ack call. Used for `find` against the authenticated socket.
     */
    fun <T : Any> emitFind(
        service: String,
        query: JSONObject,
        decode: (JsonElement) -> T?,
        onResult: (T?) -> Unit,
    ) {
        val s = socket
        if (s == null || _state.value != ConnectionState.Connected) {
            onResult(null); return
        }
        s.emit("find", service, query, io.socket.client.Ack { args ->
            val payload = args.getOrNull(1) ?: return@Ack onResult(null)
            val str = payload.toString()
            val parsed = runCatching { AgorJson.parseToJsonElement(str) }.getOrNull()
            onResult(parsed?.let { decode(it) })
        })
    }

    private fun <T> decode(
        args: Array<out Any?>,
        serializer: kotlinx.serialization.KSerializer<T>,
        onValue: (T) -> Unit,
    ) {
        val first = args.firstOrNull() ?: return
        val raw = first.toString()
        runCatching {
            val element = AgorJson.parseToJsonElement(raw)
            AgorJson.decodeFromJsonElement(serializer, element)
        }.onSuccess { onValue(it) }
            .onFailure { logger.log("decode failed: ${it.message}", LogLevel.WARNING, "Socket") }
    }
}
