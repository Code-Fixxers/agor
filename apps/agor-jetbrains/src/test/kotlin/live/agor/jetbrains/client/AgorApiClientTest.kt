package live.agor.jetbrains.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession

class AgorApiClientTest {
    @Test
    fun `loads paginated Feathers responses with auth headers`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[{"board_id":"board-1","name":"Main"}]}"""),
                    response("""{"data":[{"worktree_id":"wt-1","board_id":"board-1","name":"Addon","path":"/repo","ref":"main"}]}"""),
                    response("""{"data":[{"session_id":"sess-1","worktree_id":"wt-1","title":"Build","agentic_tool":"codex","status":"running"}]}"""),
                ),
            ),
        )

        val snapshot = AgorApiClient("http://localhost:3030", "token-123", transport).loadSnapshot()

        assertEquals("Main", snapshot.boards.single().name)
        assertEquals("Addon", snapshot.worktrees.single().name)
        assertEquals(AgorSessionStatus.RUNNING, snapshot.sessions.single().status)
        assertTrue(transport.requests.all { it.headers().firstValue("authorization").orElse(null) == "Bearer token-123" })
        assertTrue(transport.requests[2].uri().rawQuery.contains("%24limit=250"))
    }

    @Test
    fun `surfaces 401 response bodies`() {
        val transport = RecordingTransport(ArrayDeque(listOf(response("""{"error":"unauthorized"}""", 401))))

        val message = runCatching {
            AgorApiClient("http://localhost:3030", null, transport).loadSnapshot()
        }.exceptionOrNull()?.message

        assertEquals("""Agor 401: {"error":"unauthorized"}""", message)
    }

    @Test
    fun `posts prompt action as JSON`() {
        val transport = RecordingTransport(ArrayDeque(listOf(response("{}"))))

        AgorApiClient("http://localhost:3030", "secret", transport).promptSession("sess/1", "hello\nthere")

        val request = transport.requests.single()
        assertEquals("POST", request.method())
        assertEquals("/sessions/sess/1/prompt", request.uri().path)
        assertEquals("Bearer secret", request.headers().firstValue("authorization").orElse(null))
    }
}

private class RecordingTransport(
    private val responses: ArrayDeque<HttpResponse<String>>,
) : AgorHttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): HttpResponse<String> {
        requests += request
        return responses.removeFirst()
    }
}

private fun response(body: String, status: Int = 200): HttpResponse<String> =
    object : HttpResponse<String> {
        override fun statusCode(): Int = status
        override fun request(): HttpRequest =
            HttpRequest.newBuilder(URI.create("http://localhost")).build()
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("http://localhost")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
