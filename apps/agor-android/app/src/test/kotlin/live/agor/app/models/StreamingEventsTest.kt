package live.agor.app.models

import live.agor.app.network.AgorJson
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingEventsTest {
    @Test
    fun decodesThinkingStartAndEndEvents() {
        val start = AgorJson.decodeFromString(
            ThinkingStartEvent.serializer(),
            """{"session_id":"session-1","message_id":"message-1","task_id":"task-1"}""",
        )
        val end = AgorJson.decodeFromString(
            ThinkingEndEvent.serializer(),
            """{"session_id":"session-1","message_id":"message-1"}""",
        )

        assertEquals("session-1", start.sessionId)
        assertEquals("message-1", start.messageId)
        assertEquals("task-1", start.taskId)
        assertEquals("session-1", end.sessionId)
        assertEquals("message-1", end.messageId)
    }
}
