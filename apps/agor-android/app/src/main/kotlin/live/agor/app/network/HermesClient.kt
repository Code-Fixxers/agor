package live.agor.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.agor.app.data.HermesSession
import live.agor.app.data.HermesTurn
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import kotlin.text.Charsets

/**
 * OpenAI-compatible client for the NousResearch Hermes Agent.
 *
 * Hermes runs in its own NixOS systemd-nspawn container (see `nixos-llm`
 * repo: hosts/nixos/hermes-container.nix) and exposes a chat-completions
 * API on port 8642. Tools (Agor, Linear, GitHub, …) are wired server-side as
 * MCP servers — the Android app does not see or call them, it just sees the
 * assistant's final natural-language replies.
 */
interface HermesTokenStore {
    var hermesUrl: String?
    var hermesToken: String?
    var hermesModel: String?
}

class HermesClient(private val tokens: HermesTokenStore) {
    private enum class RemoteHistoryMode {
        Unknown,
        Conversations,
        Responses,
        Unsupported,
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Hermes can sit on a tool-call hop chain for a while; be patient.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // LiteLLM/Caddy SSE is more predictable over HTTP/1.1; OkHttp's HTTP/2
        // stream handling can surface gateway closes as "unexpected end of stream".
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    @Volatile
    private var remoteHistoryMode: RemoteHistoryMode = RemoteHistoryMode.Unknown

    val baseUrl: String? get() = tokens.hermesUrl?.let { normalizeHermesRootUrl(it) }
    val isConfigured: Boolean get() = !tokens.hermesUrl.isNullOrBlank() && !tokens.hermesToken.isNullOrBlank()
    val model: String get() = tokens.hermesModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * Probe `${url}/v1/models` with the given bearer to validate the connection.
     * Returns the list of advertised model names if reachable, null if not.
     */
    suspend fun probe(url: String, bearer: String): List<String>? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val cleaned = normalizeHermesRootUrl(url)
        runCatching {
            val endpoint = "$cleaned/v1/models".toHttpUrlOrNull() ?: return@runCatching null
            val req = Request.Builder()
                .url(endpoint)
                .hermesAuthHeaders(bearer)
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        throw IOException(hermesHttpErrorMessage(resp.code, text.take(400)))
                    }
                    return@use null
                }
                val payload = json.decodeFromString(ModelsResponse.serializer(), text)
                payload.data?.map { it.id }
            }
        }.getOrElse { error ->
            if (error is IOException) throw error
            null
        }
    }

    /** Send a non-streaming completion request and return the assistant message. */
    suspend fun chat(
        messages: List<HermesMessage>,
        rawUrl: String? = null,
        bearer: String? = null,
        rawModel: String? = null,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (url, token) = requireConfig(rawUrl, bearer)
        val started = System.nanoTime()
        AppLogger.log("Hermes chat request messages=${messages.size} model=${rawModel ?: model}", LogLevel.INFO, "Hermes")
        val body = ChatCompletionRequest(
            model = rawModel?.takeIf { it.isNotBlank() } ?: model,
            messages = messages,
            stream = false,
        )
        val raw = json.encodeToString(ChatCompletionRequest.serializer(), body)
        val req = Request.Builder()
            .url("$url/v1/chat/completions")
            .hermesAuthHeaders(token)
            .header("Accept", "application/json")
            .post(raw.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.log(
                "Hermes chat response ${resp.code} in ${elapsedMs(started)}ms bytes=${text.length}",
                if (resp.isSuccessful) LogLevel.INFO else LogLevel.WARNING,
                "Hermes",
            )
            if (!resp.isSuccessful) {
                throw IOException("Hermes ${resp.code}: ${text.take(400)}")
            }
            val payload = json.decodeFromString(ChatCompletionResponse.serializer(), text)
            payload.choices?.firstOrNull()?.message?.content.orEmpty()
        }
    }

    /**
     * Trigger a Hermes webhook (`/webhooks/<name>`) with a one-off prompt.
     *
     * This maps to Hermes' non-chat execution path and is useful for short,
     * imperative commands. Supports optional URL/token override for automation
     * contexts where stored tokens need not be provisioned yet.
     */
    suspend fun triggerWebhook(
        webhook: String,
        prompt: String,
        rawUrl: String? = null,
        bearer: String? = null,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (url, token) = requireConfig(rawUrl, bearer)
        val started = System.nanoTime()
        val name = webhook.trim().trim('/').ifBlank {
            throw IOException("Webhook route is required")
        }
        AppLogger.log("Hermes webhook request route=$name chars=${prompt.length}", LogLevel.INFO, "Hermes")

        val encodedRoute = URLEncoder.encode(name, Charsets.UTF_8.toString())
            .replace("+", "%20")

        val reqBody = json.encodeToString(HermesWebhookRequest.serializer(), HermesWebhookRequest(prompt))
        val req = Request.Builder()
            .url("$url/webhooks/$encodedRoute")
            .hermesAuthHeaders(token)
            .header("Accept", "application/json")
            .post(reqBody.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.log(
                "Hermes webhook route=$name response ${resp.code} in ${elapsedMs(started)}ms bytes=${text.length}",
                if (resp.isSuccessful) LogLevel.INFO else LogLevel.WARNING,
                "Hermes",
            )
            if (!resp.isSuccessful) {
                throw IOException("Hermes ${resp.code}: ${text.take(400)}")
            }
            parseWebhookResponse(text)
        }
    }

    /**
     * Stream chat-completion deltas as a Flow of content chunks.
     *
     * Emits each `choices[0].delta.content` as the SSE data lines arrive. The
     * stream completes when the server sends `data: [DONE]`. Tool-call deltas
     * are observed but not surfaced — they're server-side concerns.
     */
    fun chatStream(messages: List<HermesMessage>): Flow<String> = flow {
        val (url, bearer) = requireConfig()
        val started = System.nanoTime()
        var chunks = 0
        var chars = 0
        AppLogger.log("Hermes chat stream opening messages=${messages.size} model=$model", LogLevel.INFO, "Hermes")
        val body = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = true,
        )
        val raw = json.encodeToString(ChatCompletionRequest.serializer(), body)
        val req = Request.Builder()
            .url("$url/v1/chat/completions")
            .hermesAuthHeaders(bearer)
            .header("Accept", "text/event-stream")
            .post(raw.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(400)
                AppLogger.log("Hermes chat stream HTTP ${resp.code} in ${elapsedMs(started)}ms: $text", LogLevel.WARNING, "Hermes")
                throw IOException(hermesHttpErrorMessage(resp.code, text))
            }
            val source = resp.body?.source() ?: throw IOException("empty response")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val chunk = runCatching { json.decodeFromString(ChatCompletionChunk.serializer(), payload) }.getOrNull()
                val delta = chunk?.choices?.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    chunks += 1
                    chars += delta.length
                    emit(delta)
                }
            }
        }
        AppLogger.log("Hermes chat stream closed chunks=$chunks chars=$chars elapsed=${elapsedMs(started)}ms", LogLevel.INFO, "Hermes")
    }.flowOn(Dispatchers.IO)

    suspend fun capabilities(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (url, bearer) = requireConfig()
        val started = System.nanoTime()
        val req = Request.Builder()
            .url("$url/v1/capabilities")
            .hermesAuthHeaders(bearer)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.log(
                "Hermes capabilities response ${resp.code} in ${elapsedMs(started)}ms",
                if (resp.isSuccessful) LogLevel.DEBUG else LogLevel.WARNING,
                "Hermes",
            )
            if (!resp.isSuccessful) {
                throw IOException("Hermes ${resp.code}: ${text.take(400)}")
            }
            text
        }
    }

    suspend fun downloadStoredSessions(maxConversations: Int = 100): List<HermesSession> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val (url, bearer) = requireConfig()
            if (remoteHistoryMode == RemoteHistoryMode.Unsupported) return@withContext emptyList()
            val started = System.nanoTime()
            AppLogger.log("Hermes stored session import requested max=$maxConversations", LogLevel.INFO, "Hermes")
            val summaries = runCatching {
                if (remoteHistoryMode == RemoteHistoryMode.Responses) emptyList()
                else listStoredConversationSummaries(url, bearer, maxConversations)
            }.getOrElse { error ->
                if (!isUnsupportedHistoryRoute(error)) throw error
                AppLogger.log("Hermes conversation summary import unsupported, falling back to responses", LogLevel.DEBUG, "Hermes")
                remoteHistoryMode = RemoteHistoryMode.Responses
                emptyList()
            }
            if (remoteHistoryMode != RemoteHistoryMode.Responses) {
                remoteHistoryMode = RemoteHistoryMode.Conversations
                return@withContext summaries.map { summary ->
                    val items = runCatching { listStoredConversationItems(url, bearer, summary.id) }
                        .getOrDefault(emptyList())
                    buildHermesSession(summary, items)
                }.sortedByDescending { it.updatedAtMillis }.also {
                    AppLogger.log("Hermes stored session import loaded ${it.size} sessions in ${elapsedMs(started)}ms", LogLevel.INFO, "Hermes")
                }
            }
            runCatching {
                listStoredResponseSessions(url, bearer, maxConversations)
            }.onSuccess {
                remoteHistoryMode = RemoteHistoryMode.Responses
                AppLogger.log("Hermes stored session import loaded ${it.size} sessions in ${elapsedMs(started)}ms", LogLevel.INFO, "Hermes")
            }.getOrElse { error ->
                if (isUnsupportedHistoryRoute(error)) {
                    remoteHistoryMode = RemoteHistoryMode.Unsupported
                    AppLogger.log("Hermes remote history API unsupported; keeping local sessions only", LogLevel.INFO, "Hermes")
                    emptyList()
                } else {
                    throw error
                }
            }
        }

    fun responseStream(
        conversationId: String,
        prompt: String,
        imageDataUrls: List<String> = emptyList(),
        messages: List<HermesMessage> = listOf(HermesMessage("user", prompt)),
    ): Flow<HermesResponseEvent> = flow {
        val (url, bearer) = requireConfig()
        val started = System.nanoTime()
        var events = 0
        if (imageDataUrls.isEmpty()) {
            AppLogger.log(
                "Hermes chat-completions stream opening conversation=${conversationId.take(8)} messages=${messages.size}",
                LogLevel.INFO,
                "Hermes",
            )
            val streamResult = runCatching {
                streamChatCompletionEvents(url, bearer, messages)
            }.getOrElse { error ->
                if (!isPrematureStreamEnd(error)) throw error
                AppLogger.log(
                    "Hermes chat-completions stream ended early; retrying non-streaming completion: ${error.message}",
                    LogLevel.WARNING,
                    "Hermes",
                )
                val fallback = chat(messages = messages, rawUrl = url, bearer = bearer, rawModel = model)
                emit(HermesResponseEvent.Completed(responseId = null, outputText = fallback))
                AppLogger.log("Hermes chat-completions fallback completed chars=${fallback.length}", LogLevel.INFO, "Hermes")
                return@flow
            }
            events = streamResult.events
            if (!streamResult.completed) {
                AppLogger.log(
                    "Hermes chat-completions stream closed without DONE; retrying non-streaming completion",
                    LogLevel.WARNING,
                    "Hermes",
                )
                val fallback = chat(messages = messages, rawUrl = url, bearer = bearer, rawModel = model)
                emit(HermesResponseEvent.Completed(responseId = null, outputText = fallback))
                return@flow
            }
            AppLogger.log("Hermes chat-completions stream closed events=$events elapsed=${elapsedMs(started)}ms", LogLevel.INFO, "Hermes")
            return@flow
        }
        AppLogger.log(
            "Hermes response stream opening conversation=${conversationId.take(8)} chars=${prompt.length} images=${imageDataUrls.size}",
            LogLevel.INFO,
            "Hermes",
        )
        val req = Request.Builder()
            .url("$url/v1/responses")
            .hermesAuthHeaders(bearer)
            .header("Accept", "text/event-stream")
            .post(buildResponseRequest(conversationId, prompt, imageDataUrls).toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(400)
                AppLogger.log("Hermes response stream HTTP ${resp.code} in ${elapsedMs(started)}ms: $text", LogLevel.WARNING, "Hermes")
                throw IOException(hermesHttpErrorMessage(resp.code, text))
            }
            val source = resp.body?.source() ?: throw IOException("empty response")
            var eventName: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        HermesResponseEventParser.parse(eventName, payload)?.let {
                            events += 1
                            emit(it)
                        }
                    }
                }
            }
        }
        AppLogger.log("Hermes response stream closed events=$events elapsed=${elapsedMs(started)}ms", LogLevel.INFO, "Hermes")
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<HermesResponseEvent>.streamChatCompletionEvents(
        url: String,
        bearer: String,
        messages: List<HermesMessage>,
    ): ChatStreamResult {
        var events = 0
        var completed = false
        val body = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = true,
        )
        val raw = json.encodeToString(ChatCompletionRequest.serializer(), body)
        val req = Request.Builder()
            .url("$url/v1/chat/completions")
            .hermesAuthHeaders(bearer)
            .header("Accept", "text/event-stream")
            .post(raw.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(400)
                throw IOException(hermesHttpErrorMessage(resp.code, text))
            }
            val source = resp.body?.source() ?: throw IOException("empty response")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    completed = true
                    break
                }
                HermesResponseEventParser.parse(null, payload)?.let {
                    events += 1
                    emit(it)
                }
            }
        }
        return ChatStreamResult(events = events, completed = completed)
    }

    private fun isPrematureStreamEnd(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is EOFException || current is ProtocolException) return true
            val message = current.message.orEmpty().lowercase()
            if (
                "unexpected end of stream" in message ||
                "unexpected end of input" in message ||
                "stream was reset" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private data class ChatStreamResult(
        val events: Int,
        val completed: Boolean,
    )

    private fun requireConfig(): Pair<String, String> {
        return requireConfig(tokens.hermesUrl, tokens.hermesToken)
    }

    private fun requireConfig(rawUrl: String?, rawToken: String?): Pair<String, String> {
        val u = rawUrl?.let { normalizeHermesRootUrl(it) } ?: tokens.hermesUrl?.let { normalizeHermesRootUrl(it) }
        val t = rawToken ?: tokens.hermesToken
        if (u.isNullOrBlank()) throw IOException("Hermes URL not configured")
        if (t.isNullOrBlank()) throw IOException("Hermes token not configured")
        return u to t
    }

    private fun hermesHttpErrorMessage(code: Int, body: String): String {
        return when (code) {
            401, 403 -> "Hermes token expired or rejected. Update the Hermes API token in settings, then retry or resume this turn."
            else -> "Hermes $code: $body"
        }
    }

    private fun parseWebhookResponse(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""

        return runCatching {
            val parsed = json.decodeFromString(WebhookResponse.serializer(), trimmed)
            parsed.output
                .ifBlank { parsed.result }
                .ifBlank { parsed.response }
                .ifBlank { parsed.message }
                .ifBlank { parsed.data ?: parsed.raw }
        }.getOrElse { trimmed }
    }

    private fun buildResponseRequest(
        conversationId: String,
        prompt: String,
        imageDataUrls: List<String>,
    ): String {
        val content = JSONArray().apply {
            put(JSONObject().put("type", "input_text").put("text", prompt))
            for (dataUrl in imageDataUrls) {
                put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
            }
        }
        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content),
        )
        return JSONObject()
            .put("model", model)
            .put("store", true)
            .put("stream", true)
            .put("conversation", conversationId)
            .put("input", input)
            .toString()
    }

    private fun isUnsupportedHistoryRoute(error: Throwable): Boolean {
        val http = error as? HermesHttpException ?: return false
        return http.code == 404 || http.code == 405
    }

    private fun listStoredResponseSessions(
        url: String,
        bearer: String,
        maxResponses: Int,
    ): List<HermesSession> {
        val responses = listStoredResponses(url, bearer, maxResponses)
        if (responses.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
        for (response in responses) {
            val responseId = response.optStringOrNull("id") ?: continue
            val conversationId = response.conversationId() ?: responseId
            grouped.getOrPut(conversationId) { mutableListOf() } += response
        }
        return grouped.map { (conversationId, conversationResponses) ->
            val turns = conversationResponses.flatMap { response ->
                val responseId = response.optStringOrNull("id")
                val inputItems = responseId?.let {
                    runCatching { listStoredResponseInputItems(url, bearer, it) }.getOrDefault(emptyList())
                }.orEmpty()
                inputItems + response
            }.mapIndexedNotNull { index, item -> item.toHermesTurn(conversationId, index) }
                .distinctBy { it.id }
                .sortedBy { it.createdAtMillis }
            val createdAt = turns.firstOrNull()?.createdAtMillis
                ?: conversationResponses.mapNotNull { parseRemoteTime(it.opt("created_at")) }.minOrNull()
                ?: System.currentTimeMillis()
            val updatedAt = turns.lastOrNull()?.createdAtMillis
                ?: conversationResponses.mapNotNull { parseRemoteTime(it.opt("created_at")) }.maxOrNull()
                ?: createdAt
            val title = turns.firstOrNull { it.role == "user" }?.content?.trim()?.take(48)?.ifBlank { null }
                ?: "Hermes session"
            HermesSession(
                id = conversationId,
                conversationId = conversationId,
                title = title,
                createdAtMillis = createdAt,
                updatedAtMillis = updatedAt,
                lastResponseId = conversationResponses.lastOrNull()?.optStringOrNull("id"),
                turns = turns,
            )
        }.sortedByDescending { it.updatedAtMillis }
    }

    private fun listStoredResponses(
        url: String,
        bearer: String,
        maxResponses: Int,
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var after: String? = null
        while (out.size < maxResponses) {
            val limit = minOf(50, maxResponses - out.size)
            val query = buildString {
                append("limit=").append(limit)
                if (!after.isNullOrBlank()) append("&after=").append(encodeQuery(after.orEmpty()))
            }
            val payload = getJson(url, bearer, "/v1/responses?$query")
            val data = payload.optJSONArray("data") ?: JSONArray()
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let { out += it }
            }
            val hasMore = payload.optBoolean("has_more", false)
            after = payload.optStringOrNull("last_id")
                ?: data.optJSONObject(data.length() - 1)?.optStringOrNull("id")
            if (!hasMore || after.isNullOrBlank()) break
        }
        return out
    }

    private fun listStoredResponseInputItems(
        url: String,
        bearer: String,
        responseId: String,
    ): List<JSONObject> {
        val payload = getJson(url, bearer, "/v1/responses/${encodePath(responseId)}/input_items?limit=100")
        val data = payload.optJSONArray("data")
            ?: payload.optJSONArray("items")
            ?: JSONArray()
        return buildList {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun listStoredConversationSummaries(
        url: String,
        bearer: String,
        maxConversations: Int,
    ): List<RemoteConversationSummary> {
        val out = ArrayList<RemoteConversationSummary>()
        var after: String? = null
        while (out.size < maxConversations) {
            val limit = minOf(50, maxConversations - out.size)
            val query = buildString {
                append("limit=").append(limit)
                if (!after.isNullOrBlank()) append("&after=").append(encodeQuery(after.orEmpty()))
            }
            val payload = getJson(url, bearer, "/v1/conversations?$query")
            val data = payload.optJSONArray("data")
                ?: payload.optJSONArray("conversations")
                ?: JSONArray()
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                out += RemoteConversationSummary(
                    id = id,
                    title = obj.optStringOrNull("title")
                        ?: obj.optStringOrNull("name")
                        ?: obj.optJSONObject("metadata")?.optStringOrNull("title"),
                    createdAtMillis = parseRemoteTime(obj.opt("created_at"))
                        ?: parseRemoteTime(obj.opt("createdAt")),
                    updatedAtMillis = parseRemoteTime(obj.opt("updated_at"))
                        ?: parseRemoteTime(obj.opt("updatedAt"))
                        ?: parseRemoteTime(obj.opt("last_active_at"))
                        ?: parseRemoteTime(obj.opt("lastActiveAt")),
                )
            }
            val hasMore = payload.optBoolean("has_more", false)
            after = payload.optStringOrNull("last_id")
                ?: data.optJSONObject(data.length() - 1)?.optStringOrNull("id")
            if (!hasMore || after.isNullOrBlank()) break
        }
        return out
    }

    private fun listStoredConversationItems(
        url: String,
        bearer: String,
        conversationId: String,
    ): List<JSONObject> {
        val encodedId = encodePath(conversationId)
        val payload = getJson(url, bearer, "/v1/conversations/$encodedId/items?limit=100&order=asc")
        val data = payload.optJSONArray("data")
            ?: payload.optJSONArray("items")
            ?: JSONArray()
        return buildList {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun getJson(url: String, bearer: String, path: String): JSONObject {
        val req = Request.Builder()
            .url("$url$path")
            .hermesAuthHeaders(bearer)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HermesHttpException(resp.code, text.take(400))
            }
            return JSONObject(text)
        }
    }

    private fun buildHermesSession(
        summary: RemoteConversationSummary,
        items: List<JSONObject>,
    ): HermesSession {
        val turns = items.mapIndexedNotNull { index, item -> item.toHermesTurn(summary.id, index) }
            .sortedBy { it.createdAtMillis }
        val createdAt = summary.createdAtMillis
            ?: turns.firstOrNull()?.createdAtMillis
            ?: System.currentTimeMillis()
        val updatedAt = listOfNotNull(
            summary.updatedAtMillis,
            turns.lastOrNull()?.createdAtMillis,
            createdAt,
        ).maxOrNull() ?: createdAt
        val title = summary.title
            ?: turns.firstOrNull { it.role == "user" }?.content?.trim()?.take(48)?.ifBlank { null }
            ?: "Hermes session"
        val lastResponseId = items.asReversed()
            .firstOrNull { it.optString("role") == "assistant" || it.optString("type").contains("response") }
            ?.optStringOrNull("id")
        return HermesSession(
            id = summary.id,
            conversationId = summary.id,
            title = title,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt,
            active = false,
            lastResponseId = lastResponseId,
            turns = turns,
        )
    }

    private fun JSONObject.toHermesTurn(conversationId: String, index: Int): HermesTurn? {
        val type = optString("type", "")
        val role = optStringOrNull("role")
            ?: optJSONObject("message")?.optStringOrNull("role")
            ?: when {
                type.contains("input", ignoreCase = true) -> "user"
                type.contains("message", ignoreCase = true) -> "assistant"
                type.contains("response", ignoreCase = true) -> "assistant"
                else -> null
            }
            ?: return null
        if (role !in setOf("user", "assistant")) return null
        val text = extractMessageText(this).trim()
        if (text.isBlank()) return null
        val createdAt = parseRemoteTime(opt("created_at"))
            ?: parseRemoteTime(opt("createdAt"))
            ?: parseRemoteTime(optJSONObject("message")?.opt("created_at"))
            ?: System.currentTimeMillis()
        return HermesTurn(
            id = optStringOrNull("id") ?: "$conversationId-$index-$role",
            role = role,
            content = text,
            createdAtMillis = createdAt,
        )
    }

    private fun extractMessageText(obj: JSONObject): String {
        obj.optStringOrNull("text")?.let { return it }
        obj.optStringOrNull("output_text")?.let { return it }
        obj.optJSONObject("message")?.let { return extractMessageText(it) }
        obj.optJSONArray("content")?.let { return extractTextArray(it) }
        obj.optJSONArray("output")?.let { output ->
            val out = StringBuilder()
            for (i in 0 until output.length()) {
                output.optJSONObject(i)?.let { item ->
                    val text = extractMessageText(item)
                    if (text.isNotBlank()) out.append(text)
                }
            }
            return out.toString()
        }
        obj.optStringOrNull("content")?.let { return it }
        return ""
    }

    private fun extractTextArray(parts: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.opt(i)
            when (part) {
                is String -> out.append(part)
                is JSONObject -> {
                    val text = part.optStringOrNull("text")
                        ?: part.optStringOrNull("content")
                        ?: part.optStringOrNull("input_text")
                        ?: part.optStringOrNull("output_text")
                        ?: part.optJSONObject("text")?.optStringOrNull("value")
                    if (!text.isNullOrBlank()) out.append(text)
                }
            }
        }
        return out.toString()
    }

    private fun parseRemoteTime(raw: Any?): Long? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is Number -> raw.toLong().let { if (it < 100_000_000_000L) it * 1000L else it }
            is String -> raw.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000L else it }
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.conversationId(): String? {
        optStringOrNull("conversation")?.let { return it }
        val conversation = optJSONObject("conversation") ?: return null
        return conversation.optStringOrNull("id")
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.toString())
        .replace("+", "%20")

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.toString())

    companion object {
        const val DEFAULT_MODEL = "hermes-model"
    }
}

internal fun normalizeHermesRootUrl(rawUrl: String): String {
    val cleaned = rawUrl.trim().trimEnd('/')
    return if (cleaned.endsWith("/v1", ignoreCase = true)) cleaned.dropLast(3) else cleaned
}

private fun Request.Builder.hermesAuthHeaders(bearer: String): Request.Builder {
    return header("Authorization", "Bearer $bearer")
        .header("x-litellm-api-key", bearer)
}

internal object HermesResponseEventParser {
    fun parse(eventName: String?, payload: String): HermesResponseEvent? {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        parseChatCompletionChunk(obj)?.let { return it }
        if (obj.has("error")) return HermesResponseEvent.Failed(extractFailureMessage(obj))
        val type = obj.optString("type", eventName.orEmpty())
        return when {
            type == "response.reasoning_text.delta" -> {
                val delta = obj.optString("delta", obj.optString("text", ""))
                if (delta.isBlank()) null else HermesResponseEvent.ReasoningDelta(delta)
            }
            type == "response.output_text.delta" -> {
                val delta = obj.optString("delta", obj.optString("text", ""))
                if (delta.isBlank()) null else HermesResponseEvent.TextDelta(delta)
            }
            type == "response.completed" -> {
                val response = obj.optJSONObject("response") ?: obj
                HermesResponseEvent.Completed(
                    responseId = response.optString("id", null),
                    outputText = extractOutputText(response),
                )
            }
            type == "response.failed" || type == "error" -> {
                HermesResponseEvent.Failed(extractFailureMessage(obj))
            }
            type.contains("output_item") || type.contains("tool") || type.contains("function") -> {
                val item = obj.optJSONObject("item")
                val label = item?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: item?.optString("type")?.takeIf { it.isNotBlank() }
                    ?: type
                HermesResponseEvent.Progress(label)
            }
            else -> null
        }
    }

    private fun parseChatCompletionChunk(obj: JSONObject): HermesResponseEvent? {
        if (obj.optString("object") != "chat.completion.chunk" && !obj.has("choices")) return null
        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: return null
        delta.optStringOrNull("reasoning_content")?.let { return HermesResponseEvent.ReasoningDelta(it) }
        delta.optStringOrNull("content")?.let { return HermesResponseEvent.TextDelta(it) }
        return null
    }

    private fun extractOutputText(response: JSONObject): String {
        response.optString("output_text", "").takeIf { it.isNotBlank() }?.let { return it }
        val out = StringBuilder()
        val output = response.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", part.optString("content", ""))
                if (text.isNotBlank()) out.append(text)
            }
        }
        return out.toString()
    }

    private fun extractFailureMessage(obj: JSONObject): String {
        obj.optStringOrNull("message")?.let { return it }
        obj.optJSONObject("error")?.optStringOrNull("message")?.let { return it }
        val response = obj.optJSONObject("response")
        response?.optJSONObject("error")?.optStringOrNull("message")?.let { return it }
        extractOutputText(response ?: obj).takeIf { it.isNotBlank() }?.let { return it }
        return obj.toString().take(400)
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }
}

sealed interface HermesResponseEvent {
    data class ReasoningDelta(val text: String) : HermesResponseEvent
    data class TextDelta(val text: String) : HermesResponseEvent
    data class Progress(val label: String) : HermesResponseEvent
    data class Completed(val responseId: String?, val outputText: String) : HermesResponseEvent
    data class Failed(val message: String) : HermesResponseEvent
}

@Serializable
data class HermesMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<HermesMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice>? = null,
) {
    @Serializable
    data class Choice(val message: HermesMessage? = null)
}

@Serializable
private data class ChatCompletionChunk(
    val choices: List<DeltaChoice>? = null,
) {
    @Serializable
    data class DeltaChoice(val delta: Delta? = null, val finish_reason: String? = null)

    @Serializable
    data class Delta(val role: String? = null, val content: String? = null)
}

@Serializable
private data class ModelsResponse(val data: List<ModelEntry>? = null) {
    @Serializable
    data class ModelEntry(val id: String)
}

@Serializable
private data class HermesWebhookRequest(val prompt: String)

@Serializable
private data class WebhookResponse(
    val output: String = "",
    val result: String = "",
    val response: String = "",
    val message: String = "",
    val data: String? = null,
    val raw: String = "",
)

private data class RemoteConversationSummary(
    val id: String,
    val title: String?,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
)

private class HermesHttpException(
    val code: Int,
    body: String,
) : IOException("Hermes $code: $body")

private fun elapsedMs(startedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
