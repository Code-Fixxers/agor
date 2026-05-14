package live.agor.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
    fun chatUsesConfiguredOpenAiV1BaseUrlAndCurrentHermesGatewayDefaultModel() = runBlocking {
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
        assertEquals("hermes-model", request.body.readUtf8().let { body ->
            Regex(""""model"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
        })
        server.shutdown()
    }

    private class FakeHermesTokenStore(
        override var hermesUrl: String?,
        override var hermesToken: String?,
        override var hermesModel: String?,
    ) : HermesTokenStore
}
