package live.agor.jetbrains.client

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.Polling
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject
import java.net.URI

class AgorSocketClient(
    private val baseUrl: String,
    private val token: String?,
    private val onAgorEvent: (AgorSocketEvent) -> Unit,
) {
    private var socket: Socket? = null

    fun connect() {
        if (baseUrl.isBlank() || token.isNullOrBlank() || socket != null) return

        val options = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionDelay(2000)
            .setReconnectionDelayMax(30000)
            .setTransports(arrayOf(Polling.NAME, WebSocket.NAME))
            .setExtraHeaders(mapOf("Authorization" to listOf("Bearer $token")))
            .build()

        socket = IO.socket(URI(baseUrl), options).apply {
            on(Socket.EVENT_CONNECT) { authenticate(this) }
            on("sessions patched") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("tasks created") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("tasks patched") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("messages created") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId(), it.messageId())) }
            on("messages patched") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId(), it.messageId())) }
            on("messages permission_resolved") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("sessions permission:request") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("sessions permission:timeout") { onAgorEvent(AgorSocketEvent.SnapshotChanged(it.sessionId())) }
            on("messages streaming:start") { args ->
                args.jsonObject()?.let {
                    onAgorEvent(
                        AgorSocketEvent.StreamingStarted(
                            sessionId = it.optString("session_id"),
                            messageId = it.optNullableString("message_id"),
                            taskId = it.optNullableString("task_id"),
                            index = it.optNullableInt("index"),
                            timestamp = it.optNullableString("timestamp"),
                        ),
                    )
                }
            }
            on("messages streaming:chunk") { args ->
                args.streamingChunk(thinking = false)?.let(onAgorEvent)
            }
            on("messages thinking:chunk") { args ->
                args.streamingChunk(thinking = true)?.let(onAgorEvent)
            }
            on("messages streaming:end") { args ->
                args.jsonObject()?.let {
                    onAgorEvent(AgorSocketEvent.StreamingEnded(it.optString("session_id"), it.optNullableString("message_id")))
                }
            }
            on("messages streaming:error") { args ->
                args.jsonObject()?.let {
                    onAgorEvent(
                        AgorSocketEvent.StreamingFailed(
                            sessionId = it.optString("session_id"),
                            messageId = it.optNullableString("message_id"),
                            error = it.optString("error", "Streaming failed"),
                        ),
                    )
                }
            }
            connect()
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }

    private fun authenticate(socket: Socket) {
        val bearer = token ?: return
        val payload = JSONObject()
            .put("strategy", "jwt")
            .put("accessToken", bearer)
        socket.emit("create", "authentication", payload)
    }
}

private fun Array<Any>.jsonObject(): JSONObject? = firstOrNull() as? JSONObject

private fun Array<Any>.sessionId(): String? = jsonObject()?.optNullableString("session_id")

private fun Array<Any>.messageId(): String? = jsonObject()?.optNullableString("message_id")

private fun Array<Any>.streamingChunk(thinking: Boolean): AgorSocketEvent.StreamingChunk? {
    val obj = jsonObject() ?: return null
    val sessionId = obj.optString("session_id").takeIf { it.isNotBlank() } ?: return null
    val text = obj.optNullableString("chunk") ?: obj.optNullableString("text") ?: return null
    return AgorSocketEvent.StreamingChunk(
        sessionId = sessionId,
        messageId = obj.optNullableString("message_id"),
        text = text,
        thinking = thinking,
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
