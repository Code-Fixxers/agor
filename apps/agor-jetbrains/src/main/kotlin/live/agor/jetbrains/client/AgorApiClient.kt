package live.agor.jetbrains.client

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    fun loadSnapshot(): AgorSnapshot {
        val boards = getList("/boards", BoardDto::class.java).map { it.toModel() }
        val worktrees = getList("/worktrees", WorktreeDto::class.java, mapOf("archived" to "false")).map { it.toModel() }
        val sessions = getList(
            "/sessions",
            SessionDto::class.java,
            mapOf("archived" to "false", "\$limit" to "250"),
        ).map { it.toModel() }
        return AgorSnapshot(boards, worktrees, sessions)
    }

    fun promptSession(sessionId: String, prompt: String) {
        postJson("/sessions/${sessionId.encodePath()}/prompt", """{"prompt":${prompt.json()}}""")
    }

    fun stopSession(sessionId: String) {
        postJson("/sessions/${sessionId.encodePath()}/stop", "{}")
    }

    fun decidePermission(sessionId: String, messageId: String, decision: String) {
        postJson(
            "/sessions/${sessionId.encodePath()}/permission-decision",
            """{"messageId":${messageId.json()},"decision":${decision.json()}}""",
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

    private fun postJson(path: String, body: String) {
        val request = requestBuilder(path)
            .header("content-type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Agor ${response.statusCode()}: ${response.body().take(300)}")
        }
    }

    private fun send(method: String, path: String, query: Map<String, String>): HttpResponse<String> {
        val request = requestBuilder(path, query).method(method, HttpRequest.BodyPublishers.noBody()).build()
        val response = http.send(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Agor ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response
    }

    private fun requestBuilder(path: String, query: Map<String, String> = emptyMap()): HttpRequest.Builder {
        val separator = if (path.contains("?")) "&" else "?"
        val queryString = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = separator) {
            "${it.key.url()}=${it.value.url()}"
        }
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}$path$queryString"))
            .header("accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("authorization", "Bearer $token")
        return builder
    }

    private data class BoardDto(
        @SerializedName("board_id") val boardId: String?,
        val name: String?,
    ) {
        fun toModel() = AgorBoard(boardId.orEmpty(), name ?: "Untitled board")
    }

    private data class WorktreeDto(
        @SerializedName("worktree_id") val worktreeId: String?,
        @SerializedName("board_id") val boardId: String?,
        val name: String?,
        val ref: String?,
        val path: String?,
    ) {
        fun toModel() = AgorWorktree(worktreeId.orEmpty(), boardId, name ?: "Untitled worktree", ref, path.orEmpty())
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
}

private class JdkAgorHttpTransport(
    private val delegate: HttpClient = HttpClient.newHttpClient(),
) : AgorHttpTransport {
    override fun send(request: HttpRequest): HttpResponse<String> =
        delegate.send(request, HttpResponse.BodyHandlers.ofString())
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
