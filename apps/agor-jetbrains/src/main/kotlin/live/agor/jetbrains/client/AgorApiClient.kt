package live.agor.jetbrains.client

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.nio.charset.StandardCharsets

interface AgorHttpTransport {
    fun send(request: HttpRequest): HttpResponse<String>
}

class AgorApiClient(
    private val baseUrl: String,
    private val token: String?,
    private val http: AgorHttpTransport = JdkAgorHttpTransport(),
    private val gson: Gson = Gson(),
) {
    private val normalizedBaseUrl: String = normalizeBaseUrl(baseUrl)
    private val apiKeyCredential: String? = token?.trim()?.takeIf { it.startsWith(API_KEY_PREFIX) }
    private var resolvedBearerToken: String? = token?.trim()?.takeUnless { it.startsWith(API_KEY_PREFIX) }

    fun loadSnapshot(): AgorSnapshot {
        val boards = getList("/boards", BoardDto::class.java).map { it.toModel() }
        val worktrees = getAllPages("/worktrees", WorktreeDto::class.java, mapOf("archived" to "false")).map { it.toModel() }
        val sessions = getAllPages(
            "/sessions",
            SessionDto::class.java,
            mapOf("archived" to "false", "\$limit" to "250"),
        ).map { it.toModel() }
        val permissionRequests = runCatching {
            getAllPages(
                "/messages",
                MessageDto::class.java,
                mapOf("type" to "permission_request", "\$limit" to "250"),
            ).mapNotNull { it.toPermissionRequest(gson) }
        }.getOrDefault(emptyList())
        return AgorSnapshot(boards, worktrees, sessions, permissionRequests)
    }

    fun loadRepos(): List<AgorRepo> =
        getAllPages("/repos", RepoDto::class.java).map { it.toModel() }

    fun connectionBaseUrl(): String = normalizedBaseUrl.trimEnd('/')

    fun currentBearerToken(): String? = resolvedBearerToken

    fun promptSession(sessionId: String, prompt: String) {
        postJson("/sessions/${sessionId.encodePath()}/prompt", """{"prompt":${prompt.json()},"messageSource":"agor"}""")
    }

    fun stopSession(sessionId: String) {
        postJson("/sessions/${sessionId.encodePath()}/stop", "{}")
    }

    fun createSession(request: AgorCreateSessionRequest): AgorSession {
        val created = postJsonFor(
            "/sessions",
            buildString {
                append("""{"worktree_id":${request.worktreeId.json()}""")
                append(""","agentic_tool":${request.agenticTool.json()}""")
                append(",\"status\":\"idle\"")
                append(""","permission_config":{"mode":${defaultPermissionMode(request.agenticTool).json()}}""")
                if (!request.title.isNullOrBlank()) append(""","title":${request.title.json()}""")
                if (!request.initialPrompt.isNullOrBlank()) append(""","description":${request.initialPrompt.json()}""")
                append("}")
            },
            SessionDto::class.java,
        ).toModel()

        val prompt = request.initialPrompt?.trim()
        if (!prompt.isNullOrEmpty()) promptSession(created.sessionId, prompt)
        return created
    }

    fun createWorktree(request: AgorCreateWorktreeRequest): AgorWorktree =
        postJsonFor(
            "/repos/${request.repoId.encodePath()}/worktrees",
            buildString {
                append("""{"name":${request.name.json()}""")
                append(""","ref":${request.name.json()}""")
                append(",\"refType\":\"branch\"")
                append(""","createBranch":${request.createBranch}""")
                append(""","pullLatest":${request.pullLatest}""")
                append(""","sourceBranch":${request.sourceBranch.json()}""")
                if (!request.boardId.isNullOrBlank()) append(""","boardId":${request.boardId.json()}""")
                append("}")
            },
            WorktreeDto::class.java,
        ).toModel()

    fun forkSession(sessionId: String, prompt: String): AgorSession {
        val created = postJsonFor(
            "/sessions/${sessionId.encodePath()}/fork",
            """{"prompt":${prompt.json()}}""",
            SessionDto::class.java,
        ).toModel()
        if (prompt.isNotBlank()) promptSession(created.sessionId, prompt)
        return created
    }

    fun spawnSession(request: AgorSpawnSessionRequest): AgorSession {
        val created = postJsonFor(
            "/sessions/${request.parentSessionId.encodePath()}/spawn",
            buildString {
                append("""{"prompt":${request.prompt.json()}""")
                if (!request.title.isNullOrBlank()) append(""","title":${request.title.json()}""")
                if (!request.agenticTool.isNullOrBlank()) append(""","agent":${request.agenticTool.json()}""")
                append("}")
            },
            SessionDto::class.java,
        ).toModel()
        if (request.prompt.isNotBlank()) promptSession(created.sessionId, request.prompt)
        return created
    }

    fun decidePermission(
        sessionId: String,
        requestId: String,
        taskId: String?,
        allow: Boolean,
        scope: AgorPermissionScope = AgorPermissionScope.ONCE,
    ) {
        val reason = if (allow) "Approved by JetBrains" else "Denied by JetBrains"
        postJson(
            "/sessions/${sessionId.encodePath()}/permission-decision",
            buildString {
                append("""{"requestId":${requestId.json()}""")
                if (!taskId.isNullOrBlank()) append(""","taskId":${taskId.json()}""")
                append(""","allow":$allow""")
                append(""","reason":${reason.json()}""")
                append(""","remember":${scope != AgorPermissionScope.ONCE}""")
                append(""","scope":${scope.wireName.json()}""")
                append(""","decidedBy":"jetbrains"}""")
            },
        )
    }

    private fun <T> getList(path: String, type: Class<T>, query: Map<String, String> = emptyMap()): List<T> {
        val response = send("GET", path, query)
        val root = gson.fromJson(response.body(), JsonElement::class.java)
        val array = when {
            root is JsonArray -> root
            root is JsonObject && root.has("data") && root.get("data").isJsonArray -> root.getAsJsonArray("data")
            else -> JsonArray()
        }
        return array.map { gson.fromJson(it, type) }
    }

    private fun <T> getAllPages(path: String, type: Class<T>, query: Map<String, String> = emptyMap()): List<T> {
        val limit = query["\$limit"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
        val items = mutableListOf<T>()
        var skip = query["\$skip"]?.toIntOrNull() ?: 0
        while (true) {
            val pageQuery = query + mapOf("\$limit" to limit.toString(), "\$skip" to skip.toString())
            val response = send("GET", path, pageQuery)
            val root = gson.fromJson(response.body(), JsonElement::class.java)
            val array = when {
                root is JsonArray -> root
                root is JsonObject && root.has("data") && root.get("data").isJsonArray -> root.getAsJsonArray("data")
                else -> JsonArray()
            }
            items += array.map { gson.fromJson(it, type) }

            if (root !is JsonObject || !root.has("total")) break
            val total = root.int("total") ?: break
            val pageLimit = root.int("limit") ?: limit
            val pageSkip = root.int("skip") ?: skip
            val pageSize = array.size()
            val nextSkip = pageSkip + pageSize
            if (pageSize < pageLimit || nextSkip >= total) break
            skip = nextSkip
        }
        return items
    }

    private fun postJson(path: String, body: String) {
        postJsonResponse(path, body)
    }

    private fun <T> postJsonFor(path: String, body: String, type: Class<T>): T {
        val response = postJsonResponse(path, body)
        return gson.fromJson(response.body(), type)
    }

    private fun postJsonResponse(path: String, body: String): HttpResponse<String> {
        val request = requestBuilder(path)
            .header("content-type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = sendRequest(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Agor ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response
    }

    private fun send(method: String, path: String, query: Map<String, String>): HttpResponse<String> {
        val request = requestBuilder(path, query).method(method, HttpRequest.BodyPublishers.noBody()).build()
        val response = sendRequest(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Agor ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response
    }

    private fun ensureBearerToken() {
        if (resolvedBearerToken?.isNotBlank() == true) return
        val apiKey = apiKeyCredential ?: return
        val request = requestBuilder("/authentication", authenticated = false)
            .header("content-type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString("""{"strategy":"api-key","apiKey":${apiKey.json()}}"""))
            .build()
        val response = sendRequest(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Agor authentication ${response.statusCode()}: ${response.body().take(300)}")
        }
        val root = gson.fromJson(response.body(), JsonObject::class.java)
        val accessToken = root.string("accessToken")
            ?: throw IllegalStateException("Agor authentication response did not include an access token.")
        resolvedBearerToken = accessToken
    }

    private fun sendRequest(request: HttpRequest): HttpResponse<String> =
        try {
            http.send(request)
        } catch (error: HttpTimeoutException) {
            throw IllegalStateException(responseTimeoutMessage(), error)
        } catch (error: IOException) {
            throw IllegalStateException(connectionErrorMessage(), error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Agor request was interrupted.", error)
        }

    private fun requestBuilder(
        path: String,
        query: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): HttpRequest.Builder {
        val separator = if (path.contains("?")) "&" else "?"
        val queryString = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = separator) {
            "${it.key.url()}=${it.value.url()}"
        }
        val uri = runCatching { URI.create("${normalizedBaseUrl.trimEnd('/')}$path$queryString") }
            .getOrElse { throw IllegalStateException("Invalid Agor URL: $baseUrl", it) }
        val builder = HttpRequest.newBuilder(uri)
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(REQUEST_TIMEOUT)
            .header("accept", "application/json")
        if (authenticated) {
            ensureBearerToken()
            val bearer = resolvedBearerToken
            if (!bearer.isNullOrBlank()) builder.header("authorization", "Bearer $bearer")
        }
        return builder
    }

    private fun connectionErrorMessage(): String =
        "Could not connect to Agor at ${normalizedBaseUrl.trimEnd('/')}. Start the Agor daemon or update the Agor URL in Settings > Tools > Agor."

    private fun responseTimeoutMessage(): String =
        "Agor accepted the TCP connection at ${normalizedBaseUrl.trimEnd('/')} but did not return an HTTP response. Verify the Agor daemon is healthy and that /health responds from this machine."

    private data class BoardDto(
        @SerializedName("board_id") val boardId: String?,
        val name: String?,
    ) {
        fun toModel() = AgorBoard(boardId.orEmpty(), name ?: "Untitled board")
    }

    private data class RepoDto(
        @SerializedName("repo_id") val repoId: String?,
        val name: String?,
        val slug: String?,
        @SerializedName("default_branch") val defaultBranch: String?,
    ) {
        fun toModel() = AgorRepo(
            repoId = repoId.orEmpty(),
            name = name ?: slug ?: "Untitled repo",
            slug = slug ?: name ?: "repo",
            defaultBranch = defaultBranch,
        )
    }

    private data class WorktreeDto(
        @SerializedName("worktree_id") val worktreeId: String?,
        @SerializedName("repo_id") val repoId: String?,
        @SerializedName("board_id") val boardId: String?,
        val name: String?,
        val ref: String?,
        val path: String?,
    ) {
        fun toModel() = AgorWorktree(worktreeId.orEmpty(), repoId, boardId, name ?: "Untitled worktree", ref, path.orEmpty())
    }

    private data class SessionDto(
        @SerializedName("session_id") val sessionId: String?,
        @SerializedName("worktree_id") val worktreeId: String?,
        val title: String?,
        @SerializedName("agentic_tool") val agenticTool: String?,
        val status: String?,
    ) {
        fun toModel() = AgorSession(
            sessionId = sessionId.orEmpty(),
            worktreeId = worktreeId.orEmpty(),
            title = title ?: "Untitled session",
            agenticTool = agenticTool ?: "agent",
            status = when (status?.lowercase()) {
                "running" -> AgorSessionStatus.RUNNING
                "idle" -> AgorSessionStatus.IDLE
                "completed" -> AgorSessionStatus.COMPLETED
                "failed" -> AgorSessionStatus.FAILED
                "queued" -> AgorSessionStatus.QUEUED
                else -> AgorSessionStatus.UNKNOWN
            },
        )
    }

    private data class MessageDto(
        @SerializedName("message_id") val messageId: String?,
        @SerializedName("session_id") val sessionId: String?,
        @SerializedName("task_id") val taskId: String?,
        val type: String?,
        val content: JsonObject?,
    ) {
        fun toPermissionRequest(gson: Gson): AgorPermissionRequest? {
            if (type != "permission_request") return null
            val contentObj = content ?: return null
            val status = contentObj.string("status")
            if (status != "pending") return null
            val requestId = contentObj.string("request_id") ?: return null
            val resolvedTaskId = contentObj.string("task_id") ?: taskId
            val toolInput = contentObj.get("tool_input") ?: JsonObject()
            return AgorPermissionRequest(
                messageId = messageId.orEmpty(),
                sessionId = sessionId.orEmpty(),
                taskId = resolvedTaskId,
                requestId = requestId,
                toolName = contentObj.string("tool_name") ?: "Tool",
                toolInputJson = gson.toJson(toolInput),
            )
        }
    }
}

private fun defaultPermissionMode(agenticTool: String): String =
    when (agenticTool) {
        "gemini" -> "autoEdit"
        "codex" -> "auto"
        "opencode" -> "autoEdit"
        "copilot" -> "acceptEdits"
        else -> "acceptEdits"
    }

private class JdkAgorHttpTransport(
    private val delegate: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
) : AgorHttpTransport {
    override fun send(request: HttpRequest): HttpResponse<String> =
        delegate.send(request, HttpResponse.BodyHandlers.ofString())
}

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val DEFAULT_PAGE_SIZE = 250
private const val API_KEY_PREFIX = "agor_sk_"

private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}

private fun String.url(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
private fun String.encodePath(): String = split("/").joinToString("/") { it.url() }
private fun String.json(): String =
    buildString {
        append('"')
        for (char in this@json) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

private fun JsonObject.string(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
}

private fun JsonObject.int(key: String): Int? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else null
}
