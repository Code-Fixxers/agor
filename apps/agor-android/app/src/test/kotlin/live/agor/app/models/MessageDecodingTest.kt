package live.agor.app.models

import live.agor.app.network.AgorJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDecodingTest {
    @Test
    fun decodesPlainTextMessage() {
        val message = decodeMessage(
            """
            {
              "message_id": "m-1",
              "session_id": "s-1",
              "type": "assistant",
              "role": "assistant",
              "index": 1,
              "timestamp": "2026-05-11T00:00:00Z",
              "content": "hello"
            }
            """.trimIndent(),
        )

        assertEquals(MessageContent.Text("hello"), message.content)
    }

    @Test
    fun decodesKnownAndUnknownBlocks() {
        val message = decodeMessage(
            """
            {
              "message_id": "m-2",
              "session_id": "s-1",
              "type": "assistant",
              "role": "assistant",
              "index": 2,
              "timestamp": "2026-05-11T00:00:00Z",
              "content": [
                {"type": "text", "text": "summary"},
                {"type": "tool_use", "id": "tool-1", "name": "Read", "input": {"file_path": "README.md"}},
                {"type": "tool_result", "tool_use_id": "tool-1", "content": "ok", "is_error": false},
                {"type": "thinking", "thinking": "checking"},
                {"type": "mystery"}
              ]
            }
            """.trimIndent(),
        )

        val blocks = (message.content as MessageContent.Blocks).blocks
        assertTrue(blocks[0] is ContentBlock.Text)
        assertTrue(blocks[1] is ContentBlock.ToolUse)
        assertTrue(blocks[2] is ContentBlock.ToolResult)
        assertTrue(blocks[3] is ContentBlock.Thinking)
        assertEquals("mystery", (blocks[4] as ContentBlock.Unknown).type)
    }

    @Test
    fun decodesPermissionInputAndImageContent() {
        val permission = decodeMessage(
            """
            {
              "message_id": "m-3",
              "session_id": "s-1",
              "task_id": "t-1",
              "type": "permission_request",
              "role": "assistant",
              "index": 3,
              "timestamp": "2026-05-11T00:00:00Z",
              "content": {
                "request_id": "perm-1",
                "task_id": "t-1",
                "tool_name": "Bash",
                "status": "pending"
              }
            }
            """.trimIndent(),
        )
        val input = decodeMessage(
            """
            {
              "message_id": "m-4",
              "session_id": "s-1",
              "type": "input_request",
              "role": "assistant",
              "index": 4,
              "timestamp": "2026-05-11T00:00:00Z",
              "content": {
                "request_id": "input-1",
                "questions": [{"question": "Pick one", "kind": "single_choice", "options": ["A"]}],
                "status": "pending"
              }
            }
            """.trimIndent(),
        )
        val image = decodeMessage(
            """
            {
              "message_id": "m-5",
              "session_id": "s-1",
              "type": "assistant",
              "role": "assistant",
              "index": 5,
              "timestamp": "2026-05-11T00:00:00Z",
              "content": [
                {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AAAA"}}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("perm-1", (permission.content as MessageContent.Permission).request.permissionId)
        assertEquals("input-1", (input.content as MessageContent.InputRequest).request.inputRequestId)
        val block = ((image.content as MessageContent.Blocks).blocks.single() as ContentBlock.Image)
        assertEquals("image/png", block.source.mediaType)
        assertEquals("AAAA", block.source.data)
    }

    private fun decodeMessage(json: String): Message =
        AgorJson.decodeFromString(Message.serializer(), json)
}
