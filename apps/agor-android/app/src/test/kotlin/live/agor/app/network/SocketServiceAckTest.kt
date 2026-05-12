package live.agor.app.network

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocketServiceAckTest {
    @Test
    fun parsesNodeStyleNullThenResultAck() {
        val payload = socketAckPayload(arrayOf(null, JSONObject(mapOf("id" to "mcp-1"))))

        assertEquals("mcp-1", payload?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun parsesSingleResultAck() {
        val payload = socketAckPayload(arrayOf(JSONObject(mapOf("ok" to true))))

        assertEquals("true", payload?.jsonObject?.get("ok")?.jsonPrimitive?.content)
    }

    @Test
    fun treatsErrorObjectAckAsNoPayload() {
        val payload = socketAckPayload(
            arrayOf(JSONObject(mapOf("code" to 401, "message" to "Not authenticated"))),
        )

        assertNull(payload)
    }

    @Test
    fun treatsNoAckSentinelAsNoPayload() {
        assertNull(socketAckPayload(arrayOf("NO ACK")))
    }
}
