package live.agor.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.agor.app.auth.SecureTokenStore
import live.agor.app.models.AgorTask
import live.agor.app.models.Board
import live.agor.app.models.FileDetail
import live.agor.app.models.FileListItem
import live.agor.app.models.MCPServer
import live.agor.app.models.Message
import live.agor.app.models.Repo
import live.agor.app.models.Session
import live.agor.app.models.User
import live.agor.app.models.Worktree
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST client for Agor's FeathersJS API.
 *
 * Handles:
 * - Smart URL resolution (auto-add :3030, https/http fallback) via probeBaseUrl()
 * - JWT bearer auth + automatic refresh on 401
 * - Pagination + filter encoding ($limit, $skip, $sort)
 *
 * Mirrors apps/agor-ios/AgorApp/Services/AgorClient.swift.
 */
class AgorClient(private val tokens: SecureTokenStore) {

    val tokensRef: SecureTokenStore get() = tokens

    private val _baseUrl = MutableStateFlow(tokens.serverUrl ?: "")
    val baseUrlState: StateFlow<String> = _baseUrl.asStateFlow()

    val baseUrl: String get() = _baseUrl.value

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun setBaseUrl(url: String) {
        val canon = canonicalUrl(url)
        _baseUrl.value = canon
        tokens.serverUrl = canon
    }

    /**
     * Try several URL candidates ([asGiven, https://...:3030, http://...:3030]) and return
     * the first one whose /health endpoint responds. Mirrors AuthService's probe in iOS.
     */
    suspend fun probeBaseUrl(input: String): String? = withContext(Dispatchers.IO) {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return@withContext null
        for (candidate in candidatesFor(trimmed)) {
            if (healthOk(candidate)) return@withContext candidate
        }
        null
    }

    private fun candidatesFor(input: String): List<String> {
        val out = mutableListOf<String>()
        val hasScheme = input.startsWith("http://") || input.startsWith("https://")
        val hasPort = input.substringAfterLast('/').contains(':')
        if (hasScheme) {
            out += input
            if (!hasPort) out += "$input:3030"
        } else {
            out += "https://$input"
            out += "https://$input:3030"
            out += "http://$input"
            out += "http://$input:3030"
        }
        return out.distinct()
    }

    private fun healthOk(url: String): Boolean {
        val u = "$url/health".toHttpUrlOrNull() ?: return false
        return runCatching {
            http.newCall(Request.Builder().url(u).get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun canonicalUrl(url: String): String = url.trim().trimEnd('/')

    // ---- Auth ----

    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JsonObject(
            mapOf(
                "strategy" to JsonPrimitive("local"),
                "email" to JsonPrimitive(email),
                "password" to JsonPrimitive(password),
            ),
        )
        val resp = unauthenticatedRequest("POST", "/authentication", body)
        val payload = resp as? JsonObject ?: throw IOException("Login response malformed")
        val accessToken = payload["accessToken"]?.jsonPrimitive?.contentOrNull
        val refreshToken = payload["refreshToken"]?.jsonPrimitive?.contentOrNull
        val userEl = payload["user"]
        val user = userEl?.let { AgorJson.decodeFromJsonElement(User.serializer(), it) }
        if (accessToken.isNullOrEmpty() || user == null) {
            throw IOException("Login response missing accessToken or user")
        }
        tokens.accessToken = accessToken
        tokens.refreshToken = refreshToken
        tokens.lastEmail = email
        LoginResult(accessToken, refreshToken, user)
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val refresh = tokens.refreshToken ?: return@withContext false
        val body = JsonObject(
            mapOf(
                "strategy" to JsonPrimitive("local"),
                "refreshToken" to JsonPrimitive(refresh),
            ),
        )
        val resp = runCatching { unauthenticatedRequest("POST", "/authentication-refresh", body) }
            .getOrNull() ?: return@withContext false
        val payload = resp as? JsonObject ?: return@withContext false
        val accessToken = payload["accessToken"]?.jsonPrimitive?.contentOrNull
        val newRefresh = payload["refreshToken"]?.jsonPrimitive?.contentOrNull
        if (accessToken.isNullOrEmpty()) return@withContext false
        tokens.accessToken = accessToken
        if (!newRefresh.isNullOrEmpty()) tokens.refreshToken = newRefresh
        true
    }

    // ---- Service calls ----

    suspend fun me(): User = getOne("/users/me", User.serializer())

    suspend fun listBoards(): List<Board> = listAll("/boards", Board.serializer())

    suspend fun listWorktrees(
        boardId: String? = null,
        includeArchived: Boolean = false,
    ): List<Worktree> {
        val q = buildMap {
            if (boardId != null) put("board_id", boardId)
            if (!includeArchived) put("archived", "false")
        }
        return listAll("/worktrees", Worktree.serializer(), q)
    }

    suspend fun listSessions(
        worktreeId: String? = null,
        includeArchived: Boolean = false,
    ): List<Session> {
        val q = buildMap {
            if (worktreeId != null) put("worktree_id", worktreeId)
            if (!includeArchived) put("archived", "false")
        }
        return listAll("/sessions", Session.serializer(), q)
    }

    suspend fun getSession(id: String): Session = getOne("/sessions/$id", Session.serializer())

    suspend fun listTasks(sessionId: String): List<AgorTask> =
        listAll("/tasks", AgorTask.serializer(), mapOf("session_id" to sessionId))

    suspend fun listMessages(
        sessionId: String,
        limit: Int = 100,
        skip: Int = 0,
    ): List<Message> {
        val q = mapOf(
            "session_id" to sessionId,
            "\$limit" to limit.toString(),
            "\$skip" to skip.toString(),
            "\$sort[index]" to "1",
        )
        return listAll("/messages", Message.serializer(), q)
    }

    suspend fun listRepos(): List<Repo> = listAll("/repos", Repo.serializer())

    suspend fun listMcpServers(): List<MCPServer> =
        listAll("/mcp-servers", MCPServer.serializer())

    suspend fun listFiles(worktreeId: String): List<FileListItem> =
        listAll("/file", FileListItem.serializer(), mapOf("worktree_id" to worktreeId))

    suspend fun getFile(worktreeId: String, path: String): FileDetail {
        val q = mapOf("worktree_id" to worktreeId)
        return getOne(buildUrl("/file/${encodePath(path)}", q), FileDetail.serializer())
    }

    suspend fun sendPrompt(sessionId: String, prompt: String, taskId: String? = null) {
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(prompt))
            put("stream", JsonPrimitive(true))
            taskId?.let { put("task_id", JsonPrimitive(it)) }
        }
        authenticatedRequest("POST", "/sessions/$sessionId/prompt", body)
    }

    suspend fun stopSession(sessionId: String) {
        authenticatedRequest("POST", "/sessions/$sessionId/stop", JsonObject(emptyMap()))
    }

    suspend fun decidePermission(
        sessionId: String,
        permissionId: String,
        approve: Boolean,
        note: String? = null,
    ) {
        val body = buildJsonObject {
            put("permission_id", JsonPrimitive(permissionId))
            put("decision", JsonPrimitive(if (approve) "approved" else "denied"))
            note?.let { put("decision_note", JsonPrimitive(it)) }
        }
        authenticatedRequest("POST", "/sessions/$sessionId/permission-decision", body)
    }

    suspend fun answerInputRequest(
        sessionId: String,
        inputRequestId: String,
        answers: List<String>,
    ) {
        val body = buildJsonObject {
            put("input_request_id", JsonPrimitive(inputRequestId))
            put(
                "answers",
                AgorJson.encodeToJsonElement(ListSerializer(String.serializer()), answers),
            )
        }
        authenticatedRequest("POST", "/sessions/$sessionId/input-response", body)
    }

    suspend fun patchSession(sessionId: String, fields: JsonObject): Session {
        val resp = authenticatedRequest("PATCH", "/sessions/$sessionId", fields)
        return AgorJson.decodeFromJsonElement(Session.serializer(), resp)
    }

    // ---- Internals ----

    private suspend fun <T> getOne(
        pathOrUrl: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val resp = authenticatedRequest("GET", pathOrUrl, body = null)
        return AgorJson.decodeFromJsonElement(serializer, resp)
    }

    private suspend fun <T> listAll(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): List<T> {
        val out = mutableListOf<T>()
        var skip = 0
        val limit = 100
        while (true) {
        val q = query + mapOf(
                "\$limit" to limit.toString(),
                "\$skip" to skip.toString(),
            )
            val element = authenticatedRequest("GET", buildUrl(path, q), body = null)
            val data: List<JsonElement> = when {
                element is JsonObject && element["data"] is kotlinx.serialization.json.JsonArray ->
                    (element["data"] as kotlinx.serialization.json.JsonArray).toList()
                element is kotlinx.serialization.json.JsonArray -> element.toList()
                else -> emptyList()
            }
            data.forEach { out += AgorJson.decodeFromJsonElement(serializer, it) }
            if (data.size < limit) break
            skip += limit
            if (skip > 5000) break // safety
        }
        return out
    }

    private fun buildUrl(path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return path
        val qs = query.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return if (path.contains("?")) "$path&$qs" else "$path?$qs"
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    private suspend fun authenticatedRequest(
        method: String,
        pathOrUrl: String,
        body: JsonElement?,
    ): JsonElement = withContext(Dispatchers.IO) {
        val firstResp = doRequest(method, pathOrUrl, body, useAuth = true)
        if (firstResp.code == 401) {
            firstResp.close()
            val refreshed = refresh()
            if (refreshed) {
                val retry = doRequest(method, pathOrUrl, body, useAuth = true)
                return@withContext readJson(retry)
            }
            throw AuthException("Unauthenticated")
        }
        readJson(firstResp)
    }

    private suspend fun unauthenticatedRequest(
        method: String,
        pathOrUrl: String,
        body: JsonElement?,
    ): JsonElement = withContext(Dispatchers.IO) {
        readJson(doRequest(method, pathOrUrl, body, useAuth = false))
    }

    private fun doRequest(
        method: String,
        pathOrUrl: String,
        body: JsonElement?,
        useAuth: Boolean,
    ): okhttp3.Response {
        if (baseUrl.isEmpty()) throw IOException("Server URL not configured")
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$baseUrl$pathOrUrl"
        val builder = Request.Builder().url(url)
        if (useAuth) {
            tokens.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        }
        builder.header("Accept", "application/json")
        val rb = body?.let { AgorJson.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonMedia) }
        builder.method(method, rb ?: if (method != "GET") "".toRequestBody(jsonMedia) else null)
        return http.newCall(builder.build()).execute()
    }

    private fun readJson(resp: okhttp3.Response): JsonElement = resp.use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val message = runCatching {
                AgorJson.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP ${r.code}"
            throw HttpException(r.code, message, text)
        }
        if (text.isBlank()) JsonObject(emptyMap()) else AgorJson.parseToJsonElement(text)
    }

    data class LoginResult(val accessToken: String, val refreshToken: String?, val user: User)

    class AuthException(message: String) : IOException(message)
    class HttpException(val statusCode: Int, message: String, val body: String) : IOException(message)
}

private inline fun buildJsonObject(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
    val map = mutableMapOf<String, JsonElement>()
    map.block()
    return JsonObject(map)
}
