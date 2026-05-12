package live.agor.app.network

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.agor.app.models.User
import live.agor.app.models.AgenticTool
import live.agor.app.models.ModelConfig
import live.agor.app.models.PermissionConfig
import live.agor.app.models.PermissionMode
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AgorClientPayloadTest {
    @Test
    fun baseUrlCandidatesNormalizeInputAndPreferHttpsBeforeHttpFallback() {
        assertEquals(
            listOf(
                "https://agor.example.test",
                "https://agor.example.test:3030",
                "http://agor.example.test",
                "http://agor.example.test:3030",
            ),
            agorBaseUrlCandidates("  agor.example.test/  "),
        )
    }

    @Test
    fun baseUrlCandidatesKeepExplicitSchemeAndTryDefaultDaemonPort() {
        assertEquals(
            listOf("http://localhost", "http://localhost:3030"),
            agorBaseUrlCandidates(" http://localhost/ "),
        )
    }

    @Test
    fun authenticatedRequestRefreshesExpiredTokenAndRetriesOriginalCall() = runBlocking {
        val server = MockWebServer()
        val tokens = FakeAgorTokenStore(
            serverUrl = server.url("/").toString().trimEnd('/'),
            accessToken = "expired-token",
            refreshToken = "refresh-token",
        )
        val client = AgorClient(tokens)
        client.setBaseUrl(tokens.serverUrl!!)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"jwt expired"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"fresh-token","refreshToken":"fresh-refresh"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                AgorJson.encodeToString(
                    User.serializer(),
                    User(userId = "user-1", email = "user@example.test", name = "User"),
                ),
            ),
        )

        val user = client.me()

        assertEquals("user-1", user.userId)
        assertEquals("fresh-token", tokens.accessToken)
        assertEquals("fresh-refresh", tokens.refreshToken)
        assertEquals("Bearer expired-token", server.takeRequest().getHeader("Authorization"))
        val refreshRequest = server.takeRequest()
        assertEquals("/authentication-refresh", refreshRequest.path)
        assertTrue(refreshRequest.body.readUtf8().contains("refresh-token"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }

    @Test
    fun uploadMultipartBodyIncludesNotifyFlagMessageAndFiles() {
        val body = uploadSessionFilesMultipartBody(
            files = listOf(
                UploadFileInput(
                    filename = "notes.txt",
                    mimeType = "text/plain",
                    bytes = "hello".toByteArray(),
                ),
            ),
            notifyAgent = true,
            message = "Attached file(s): {filepath}",
        )
        val buffer = Buffer()
        body.writeTo(buffer)
        val raw = buffer.readUtf8()

        assertTrue(raw.contains("name=\"notifyAgent\""))
        assertTrue(raw.contains("true"))
        assertTrue(raw.contains("name=\"message\""))
        assertTrue(raw.contains("Attached file(s): {filepath}"))
        assertTrue(raw.contains("name=\"files\"; filename=\"notes.txt\""))
        assertTrue(raw.contains("hello"))
    }

    private class FakeAgorTokenStore(
        override var serverUrl: String?,
        override var accessToken: String?,
        override var refreshToken: String?,
        override var lastEmail: String? = null,
    ) : AgorTokenStore


    @Test
    fun permissionDecisionPayloadMatchesDaemonContract() {
        val payload = permissionDecisionPayload(
            requestId = "perm-1",
            taskId = "task-1",
            approve = true,
            decidedBy = "user-1",
        )

        assertEquals("perm-1", payload["requestId"]?.jsonPrimitive?.content)
        assertEquals("task-1", payload["taskId"]?.jsonPrimitive?.content)
        assertEquals(true, payload["allow"]?.jsonPrimitive?.boolean)
        assertEquals("Approved by user", payload["reason"]?.jsonPrimitive?.content)
        assertFalse(payload["remember"]!!.jsonPrimitive.boolean)
        assertEquals("once", payload["scope"]?.jsonPrimitive?.content)
        assertEquals("user-1", payload["decidedBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun inputResponsePayloadUsesAnswerMap() {
        val payload = inputResponsePayload(
            requestId = "input-1",
            taskId = "task-1",
            answers = mapOf("Pick one" to "A"),
            respondedBy = "user-1",
        )

        assertEquals("input-1", payload["requestId"]?.jsonPrimitive?.content)
        assertEquals("task-1", payload["taskId"]?.jsonPrimitive?.content)
        assertEquals("A", payload["answers"]?.jsonObject?.get("Pick one")?.jsonPrimitive?.content)
        assertEquals("user-1", payload["respondedBy"]?.jsonPrimitive?.content)
    }

    @Test
    fun createSessionPayloadPreservesRuntimeConfig() {
        val payload = createSessionPayload(
            worktreeId = "wt-1",
            agenticTool = AgenticTool.CLAUDE_CODE,
            title = "Fresh session",
            permissionConfig = PermissionConfig(mode = PermissionMode.PLAN),
            modelConfig = ModelConfig(model = "claude-opus-4-6", effort = "high"),
        )

        assertEquals("wt-1", payload["worktree_id"]?.jsonPrimitive?.content)
        assertEquals("idle", payload["status"]?.jsonPrimitive?.content)
        assertEquals("claude-code", payload["agentic_tool"]?.jsonPrimitive?.content)
        assertEquals("Fresh session", payload["title"]?.jsonPrimitive?.content)
        assertEquals("plan", payload["permission_config"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        assertEquals("claude-opus-4-6", payload["model_config"]?.jsonObject?.get("model")?.jsonPrimitive?.content)
        assertEquals("high", payload["model_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun sessionMcpAttachPayloadUsesDaemonContract() {
        val payload = sessionMcpAttachPayload("mcp-1")

        assertEquals("mcp-1", payload["mcpServerId"]?.jsonPrimitive?.content)
    }

    @Test
    fun sessionMcpTogglePayloadUsesBooleanEnabled() {
        val payload = sessionMcpTogglePayload(enabled = false)

        assertEquals(false, payload["enabled"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun sessionPermissionModePayloadUsesPermissionConfigShape() {
        val payload = sessionPermissionModePayload(PermissionMode.PLAN)

        assertEquals(
            "plan",
            payload["permission_config"]?.jsonObject?.get("mode")?.jsonPrimitive?.content,
        )
    }
}
