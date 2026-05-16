package live.agor.app.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesClientRequestTest {
    @Test
    fun probeUsesConfiguredOpenAiV1BaseUrlWithoutDuplicatingV1AndSendsLiteLlmHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"hermes-model"}]}"""))
        val url = server.url("/v1/").toString()
        val client = HermesClient(
            FakeHermesTokenStore(
                hermesUrl = url,
                hermesToken = "litellm-key",
                hermesModel = "hermes-model",
            ),
        )

        val models = client.probe(url, "litellm-key")

        assertEquals(listOf("hermes-model"), models)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer litellm-key", request.getHeader("Authorization"))
        assertEquals("litellm-key", request.getHeader("x-litellm-api-key"))
        server.shutdown()
    }

    @Test
    fun chatUsesConfiguredOpenAiV1BaseUrlAndHermesAgentDefaultModel() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""))
        val client = HermesClient(
            FakeHermesTokenStore(
                hermesUrl = server.url("/v1").toString(),
                hermesToken = "litellm-key",
                hermesModel = null,
            ),
        )

        val reply = client.chat(listOf(HermesMessage("user", "ping")))

        assertEquals("pong", reply)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer litellm-key", request.getHeader("Authorization"))
        assertEquals("litellm-key", request.getHeader("x-litellm-api-key"))
        assertEquals("hermes-agent", request.body.readUtf8().let { body ->
            Regex(""""model"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
        })
        server.shutdown()
    }

    @Test
    fun responseStreamUsesHermesResponsesApiWithConversationForTextTurns() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: response.reasoning_text.delta
                    data: {"type":"response.reasoning_text.delta","delta":"Thinking"}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"ok"}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_123","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}}

                    """.trimIndent(),
                ),
        )
        val client = HermesClient(
            FakeHermesTokenStore(
                hermesUrl = server.url("/v1").toString(),
                hermesToken = "litellm-key",
                hermesModel = "hermes-model",
            ),
        )

        val events = client.responseStream(
            conversationId = "agor-android-session",
            prompt = "new prompt",
        ).toList()

        assertEquals(
            listOf(
                HermesResponseEvent.ReasoningDelta("Thinking"),
                HermesResponseEvent.TextDelta("ok"),
                HermesResponseEvent.Completed(responseId = "resp_123", outputText = "ok"),
            ),
            events,
        )
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""conversation":"agor-android-session""""))
        assertTrue(body.contains(""""type":"input_text""""))
        assertTrue(body.contains("new prompt"))
        assertTrue(body.contains(""""stream":true"""))
        server.shutdown()
    }

    @Test
    fun responseStreamUsesHermesResponsesApiForImagesToo() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"seen"}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_img","output_text":"seen"}}

                    """.trimIndent(),
                ),
        )
        val client = HermesClient(
            FakeHermesTokenStore(
                hermesUrl = server.url("/v1").toString(),
                hermesToken = "litellm-key",
                hermesModel = "hermes-model",
            ),
        )

        val events = client.responseStream(
            conversationId = "agor-android-session",
            prompt = "describe this",
            imageDataUrls = listOf("data:image/png;base64,abc"),
        ).toList()

        assertEquals(
            listOf(
                HermesResponseEvent.TextDelta("seen"),
                HermesResponseEvent.Completed(responseId = "resp_img", outputText = "seen"),
            ),
            events,
        )
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""type":"input_image""""))
        assertTrue(body.contains("data:image/png;base64,abc"))
        server.shutdown()
    }

    private class FakeHermesTokenStore(
        override var hermesUrl: String?,
        override var hermesToken: String?,
        override var hermesModel: String?,
    ) : HermesTokenStore
}
