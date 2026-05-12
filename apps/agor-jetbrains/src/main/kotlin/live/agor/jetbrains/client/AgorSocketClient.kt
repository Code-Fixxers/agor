package live.agor.jetbrains.client

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

class AgorSocketClient(
    private val baseUrl: String,
    private val token: String?,
    private val onAgorEvent: () -> Unit,
) {
    private var socket: Socket? = null

    fun connect() {
        if (baseUrl.isBlank() || token.isNullOrBlank() || socket != null) return

        val options = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionDelay(2000)
            .setReconnectionDelayMax(30000)
            .setExtraHeaders(mapOf("Authorization" to listOf("Bearer $token")))
            .build()

        socket = IO.socket(URI(baseUrl), options).apply {
            on(Socket.EVENT_CONNECT) { authenticate(this) }
            on("sessions patched") { onAgorEvent() }
            on("tasks created") { onAgorEvent() }
            on("tasks patched") { onAgorEvent() }
            on("messages created") { onAgorEvent() }
            on("messages patched") { onAgorEvent() }
            on("messages permission_resolved") { onAgorEvent() }
            on("sessions permission:request") { onAgorEvent() }
            on("sessions permission:timeout") { onAgorEvent() }
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
