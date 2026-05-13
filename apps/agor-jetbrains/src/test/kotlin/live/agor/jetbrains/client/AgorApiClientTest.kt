package live.agor.jetbrains.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
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
                    response("""{"data":[]}"""),
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
    fun `loads every paginated session page`() {
        val firstPage = (1..250).joinToString(",") {
            """{"session_id":"sess-$it","worktree_id":"wt-1","title":"Session $it","agentic_tool":"codex","status":"idle"}"""
        }
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[{"board_id":"board-1","name":"Main"}],"total":1,"limit":100,"skip":0}"""),
                    response("""{"data":[{"worktree_id":"wt-1","board_id":"board-1","name":"Addon","path":"/repo","ref":"main"}],"total":1,"limit":100,"skip":0}"""),
                    response("""{"data":[$firstPage],"total":251,"limit":250,"skip":0}"""),
                    response("""{"data":[{"session_id":"sess-251","worktree_id":"wt-1","title":"Session 251","agentic_tool":"codex","status":"idle"}],"total":251,"limit":250,"skip":250}"""),
                    response("""{"data":[],"total":0,"limit":250,"skip":0}"""),
                ),
            ),
        )

        val snapshot = AgorApiClient("http://localhost:3030", "token-123", transport).loadSnapshot()

        assertEquals(251, snapshot.sessions.size)
        assertEquals("sess-251", snapshot.sessions.last().sessionId)
        assertTrue(transport.requests[2].uri().rawQuery.contains("%24skip=0"))
        assertTrue(transport.requests[3].uri().rawQuery.contains("%24skip=250"))
    }

    @Test
    fun `exchanges personal API keys for JWT before service calls`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"accessToken":"jwt-123","refreshToken":"refresh-123","user":{"user_id":"user-1"}}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                ),
            ),
        )

        val client = AgorApiClient("http://localhost:3030", "agor_sk_test", transport)

        client.loadSnapshot()

        val authRequest = transport.requests.first()
        assertEquals("POST", authRequest.method())
        assertEquals("/authentication", authRequest.uri().path)
        assertFalse(authRequest.headers().firstValue("authorization").isPresent)
        assertEquals("""{"strategy":"api-key","apiKey":"agor_sk_test"}""", authRequest.bodyText())
        assertTrue(transport.requests.drop(1).all {
            it.headers().firstValue("authorization").orElse(null) == "Bearer jwt-123"
        })
        assertEquals("jwt-123", client.currentBearerToken())
    }

    @Test
    fun `normalizes bare host settings to http URLs`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                ),
            ),
        )

        AgorApiClient("100.101.157.56:3030", "token-123", transport).loadSnapshot()

        assertEquals("http://100.101.157.56:3030/boards", transport.requests.first().uri().toString())
    }

    @Test
    fun `uses HTTP 1_1 and request timeout for remote daemon compatibility`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                ),
            ),
        )

        AgorApiClient("http://100.101.157.56:3030", "token-123", transport).loadSnapshot()

        val request = transport.requests.first()
        assertEquals(HttpClient.Version.HTTP_1_1, request.version().orElseThrow())
        assertTrue(request.timeout().orElseThrow().seconds > 0)
    }

    @Test
    fun `loads pending permission request messages into snapshot`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response("""{"data":[]}"""),
                    response(
                        """
                        {
                          "data": [
                            {
                              "message_id": "msg-1",
                              "session_id": "sess-1",
                              "task_id": "task-1",
                              "type": "permission_request",
                              "content": {
                                "request_id": "req-1",
                                "task_id": "task-1",
                                "tool_name": "Bash",
                                "tool_input": {"command": "cargo test"},
                                "status": "pending"
                              }
                            },
                            {
                              "message_id": "msg-2",
                              "session_id": "sess-2",
                              "type": "permission_request",
                              "content": {
                                "request_id": "req-2",
                                "tool_name": "Write",
                                "tool_input": {"file_path": "/tmp/a"},
                                "status": "approved"
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val permissions = AgorApiClient("http://localhost:3030", "token-123", transport)
            .loadSnapshot()
            .permissionRequests

        assertEquals(1, permissions.size)
        assertEquals("req-1", permissions.single().requestId)
        assertEquals("sess-1", permissions.single().sessionId)
        assertEquals("task-1", permissions.single().taskId)
        assertEquals("Bash", permissions.single().toolName)
        assertEquals("""{"command":"cargo test"}""", permissions.single().toolInputJson)
        assertTrue(transport.requests[3].uri().rawQuery.contains("type=permission_request"))
    }

    @Test
    fun `loads navigator snapshot when optional permission request query fails`() {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response("""{"data":[{"board_id":"board-1","name":"Main"}]}"""),
                    response("""{"data":[{"worktree_id":"wt-1","board_id":"board-1","name":"Addon","path":"/repo","ref":"main"}]}"""),
                    response("""{"data":[{"session_id":"sess-1","worktree_id":"wt-1","title":"Build","agentic_tool":"codex","status":"running"}]}"""),
                    response("""{"name":"GeneralError","message":"Unexpected token 'R'","code":500,"className":"general-error"}""", 500),
                ),
            ),
        )

        val snapshot = AgorApiClient("http://localhost:3030", "token-123", transport).loadSnapshot()

        assertEquals("Main", snapshot.boards.single().name)
        assertEquals("Addon", snapshot.worktrees.single().name)
        assertEquals("Build", snapshot.sessions.single().title)
        assertTrue(snapshot.permissionRequests.isEmpty())
    }

    @Test
    fun `posts permission decisions with Agor payload shape`() {
        val transport = RecordingTransport(ArrayDeque(listOf(response("{}"))))

        AgorApiClient("http://localhost:3030", "secret", transport).decidePermission(
            sessionId = "sess-1",
            requestId = "req-1",
            taskId = "task-1",
            allow = true,
            scope = AgorPermissionScope.PROJECT,
        )

        val request = transport.requests.single()
        assertEquals("POST", request.method())
        assertEquals("/sessions/sess-1/permission-decision", request.uri().path)
        assertEquals(
            """{"requestId":"req-1","taskId":"task-1","allow":true,"reason":"Approved by JetBrains","remember":true,"scope":"project","decidedBy":"jetbrains"}""",
            request.bodyText(),
        )
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
    fun `maps empty HTTP parser failures to actionable connection error`() {
        val message = runCatching {
            AgorApiClient("http://localhost:3030", null, FailingTransport(IOException("HTTP/1.1 header parser received no bytes")))
                .loadSnapshot()
        }.exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("Could not connect to Agor at http://localhost:3030"))
        assertTrue(message.contains("Start the Agor daemon"))
        assertTrue(!message.contains("HTTP/1.1 header parser received no bytes"))
    }

    @Test
    fun `maps HTTP response timeouts to daemon not responding error`() {
        val message = runCatching {
            AgorApiClient("http://100.101.157.56:3030", null, FailingTransport(HttpTimeoutException("request timed out")))
                .loadSnapshot()
        }.exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("Agor accepted the TCP connection at http://100.101.157.56:3030"))
        assertTrue(message.contains("did not return an HTTP response"))
    }

    @Test
    fun `maps invalid Agor URLs to configuration error`() {
        val message = runCatching {
            AgorApiClient("not a url", null, RecordingTransport(ArrayDeque()))
                .loadSnapshot()
        }.exceptionOrNull()?.message

        assertEquals("Invalid Agor URL: not a url", message)
    }

    @Test
    fun `posts prompt action as JSON`() {
        val transport = RecordingTransport(ArrayDeque(listOf(response("{}"))))

        AgorApiClient("http://localhost:3030", "secret", transport).promptSession("sess/1", "hello\nthere")

        val request = transport.requests.single()
        assertEquals("POST", request.method())
        assertEquals("/sessions/sess/1/prompt", request.uri().path)
        assertEquals("Bearer secret", request.headers().firstValue("authorization").orElse(null))
        assertEquals("""{"prompt":"hello\nthere","messageSource":"agor"}""", request.bodyText())
    }

    @Test
    fun `creates sessions with Agor defaults and sends initial prompt`() {
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    response("""{"session_id":"sess-1","worktree_id":"wt-1","title":"Build it","agentic_tool":"codex","status":"idle"}"""),
                    response("""{"task_id":"task-1"}"""),
                ),
            ),
        )

        val created = AgorApiClient("http://localhost:3030", "secret", transport).createSession(
            AgorCreateSessionRequest("wt-1", "codex", "Build it", "ship it"),
        )

        assertEquals("sess-1", created.sessionId)
        assertEquals("/sessions", transport.requests[0].uri().path)
        assertEquals(
            """{"worktree_id":"wt-1","agentic_tool":"codex","status":"idle","permission_config":{"mode":"auto"},"title":"Build it","description":"ship it"}""",
            transport.requests[0].bodyText(),
        )
        assertEquals("/sessions/sess-1/prompt", transport.requests[1].uri().path)
    }

    @Test
    fun `creates worktrees through repo scoped route`() {
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    response("""{"worktree_id":"wt-1","repo_id":"repo-1","board_id":"board-1","name":"feature-a","path":"/repo/feature-a","ref":"feature-a"}"""),
                ),
            ),
        )

        val created = AgorApiClient("http://localhost:3030", "secret", transport).createWorktree(
            AgorCreateWorktreeRequest(
                repoId = "repo-1",
                boardId = "board-1",
                name = "feature-a",
                sourceBranch = "main",
            ),
        )

        assertEquals("wt-1", created.worktreeId)
        assertEquals("repo-1", created.repoId)
        assertEquals("/repos/repo-1/worktrees", transport.requests.single().uri().path)
        assertEquals(
            """{"name":"feature-a","ref":"feature-a","refType":"branch","createBranch":true,"pullLatest":true,"sourceBranch":"main","boardId":"board-1"}""",
            transport.requests.single().bodyText(),
        )
    }

    @Test
    fun `spawns session then prompts returned child`() {
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    response("""{"session_id":"child-1","worktree_id":"wt-1","title":"Child","agentic_tool":"claude-code","status":"idle"}"""),
                    response("""{"task_id":"task-1"}"""),
                ),
            ),
        )

        val created = AgorApiClient("http://localhost:3030", "secret", transport).spawnSession(
            AgorSpawnSessionRequest("parent-1", "review this", "Child", "codex"),
        )

        assertEquals("child-1", created.sessionId)
        assertEquals("/sessions/parent-1/spawn", transport.requests[0].uri().path)
        assertEquals("""{"prompt":"review this","title":"Child","agent":"codex"}""", transport.requests[0].bodyText())
        assertEquals("/sessions/child-1/prompt", transport.requests[1].uri().path)
    }
}

private fun HttpRequest.bodyText(): String {
    val subscriber = StringSubscriber()
    bodyPublisher().orElseThrow().subscribe(subscriber)
    return subscriber.body
}

private class StringSubscriber : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
    private val bytes = java.io.ByteArrayOutputStream()
    var body: String = ""
        private set

    override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: java.nio.ByteBuffer) {
        val buffer = ByteArray(item.remaining())
        item.get(buffer)
        bytes.write(buffer)
    }

    override fun onError(throwable: Throwable) {
        throw throwable
    }

    override fun onComplete() {
        body = bytes.toString(Charsets.UTF_8)
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

private class FailingTransport(
    private val error: IOException,
) : AgorHttpTransport {
    override fun send(request: HttpRequest): HttpResponse<String> {
        throw error
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
