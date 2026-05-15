package live.agor.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesResponseEventParserTest {
    @Test
    fun parsesResponseTextDeltaFromEventPayload() {
        val event = HermesResponseEventParser.parse(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"hello"}""",
        )

        assertEquals(HermesResponseEvent.TextDelta("hello"), event)
    }

    @Test
    fun parsesReasoningDeltaFromResponsesStream() {
        val event = HermesResponseEventParser.parse(
            "response.reasoning_text.delta",
            """{"type":"response.reasoning_text.delta","delta":"Thinking"}""",
        )

        assertEquals(HermesResponseEvent.ReasoningDelta("Thinking"), event)
    }

    @Test
    fun parsesReasoningDeltaFromChatCompletionStream() {
        val event = HermesResponseEventParser.parse(
            null,
            """{"object":"chat.completion.chunk","choices":[{"delta":{"reasoning_content":"Thinking"}}]}""",
        )

        assertEquals(HermesResponseEvent.ReasoningDelta("Thinking"), event)
    }

    @Test
    fun parsesTextDeltaFromChatCompletionStream() {
        val event = HermesResponseEventParser.parse(
            null,
            """{"object":"chat.completion.chunk","choices":[{"delta":{"content":"ok"}}]}""",
        )

        assertEquals(HermesResponseEvent.TextDelta("ok"), event)
    }

    @Test
    fun parsesCompletedOutputTextFromNestedResponse() {
        val event = HermesResponseEventParser.parse(
            "response.completed",
            """{"response":{"id":"resp-1","output":[{"content":[{"text":"final answer"}]}]}}""",
        )

        assertEquals(HermesResponseEvent.Completed("resp-1", "final answer"), event)
    }

    @Test
    fun parsesFailureMessageFromResponseError() {
        val event = HermesResponseEventParser.parse(
            "response.failed",
            """{"response":{"error":{"message":"token expired"}}}""",
        )

        assertEquals(HermesResponseEvent.Failed("token expired"), event)
    }

    @Test
    fun ignoresUnknownOrBlankDeltas() {
        assertNull(HermesResponseEventParser.parse("response.output_text.delta", """{"delta":""}"""))
        assertNull(HermesResponseEventParser.parse("response.created", """{"type":"response.created"}"""))
    }
}
