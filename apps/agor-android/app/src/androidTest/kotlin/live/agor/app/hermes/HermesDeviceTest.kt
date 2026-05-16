package live.agor.app.hermes

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import live.agor.app.network.HermesClient
import live.agor.app.network.HermesResponseEvent
import live.agor.app.network.HermesTokenStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesDeviceTest {
    @Test
    fun streamsHermesResponsesApiThroughAndroidHttpStack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"hello"}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_device","output_text":"hello"}}

                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = HermesClient(
                FakeHermesTokenStore(
                    hermesUrl = server.url("/v1").toString(),
                    hermesToken = "device-token",
                    hermesModel = null,
                ),
            )

            val events = client.responseStream(
                conversationId = "agor-android-device",
                prompt = "ping",
            ).toList()

            assertEquals(
                listOf(
                    HermesResponseEvent.TextDelta("hello"),
                    HermesResponseEvent.Completed(responseId = "resp_device", outputText = "hello"),
                ),
                events,
            )
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer device-token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains(""""model":"hermes-agent""""))
            assertTrue(body.contains(""""conversation":"agor-android-device""""))
            assertTrue(body.contains(""""type":"input_text""""))
        } finally {
            server.shutdown()
        }
    }

    private class FakeHermesTokenStore(
        override var hermesUrl: String?,
        override var hermesToken: String?,
        override var hermesModel: String?,
    ) : HermesTokenStore
}
