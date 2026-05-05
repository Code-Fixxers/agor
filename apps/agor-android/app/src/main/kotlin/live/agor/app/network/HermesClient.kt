package live.agor.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.agor.app.auth.SecureTokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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
class HermesClient(private val tokens: SecureTokenStore) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Hermes can sit on a tool-call hop chain for a while; be patient.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val baseUrl: String? get() = tokens.hermesUrl?.trimEnd('/')
    val isConfigured: Boolean get() = !tokens.hermesUrl.isNullOrBlank() && !tokens.hermesToken.isNullOrBlank()
    val model: String get() = tokens.hermesModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * Probe `${url}/v1/models` with the given bearer to validate the connection.
     * Returns the list of advertised model names if reachable, null if not.
     */
    suspend fun probe(url: String, bearer: String): List<String>? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val cleaned = url.trim().trimEnd('/')
        val req = Request.Builder()
            .url("$cleaned/v1/models")
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .get()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val payload = json.decodeFromString(ModelsResponse.serializer(), body)
                payload.data?.map { it.id }
            }
        }.getOrNull()
    }

    /** Send a non-streaming completion request and return the assistant message. */
    suspend fun chat(messages: List<HermesMessage>): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (url, bearer) = requireConfig()
        val body = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = false,
        )
        val raw = json.encodeToString(ChatCompletionRequest.serializer(), body)
        val req = Request.Builder()
            .url("$url/v1/chat/completions")
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .post(raw.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
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
        val name = webhook.trim().trim('/').ifBlank {
            throw IOException("Webhook route is required")
        }

        val encodedRoute = URLEncoder.encode(name, Charsets.UTF_8.toString())
            .replace("+", "%20")

        val reqBody = json.encodeToString(HermesWebhookRequest.serializer(), HermesWebhookRequest(prompt))
        val req = Request.Builder()
            .url("$url/webhooks/$encodedRoute")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(reqBody.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
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
        val body = ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = true,
        )
        val raw = json.encodeToString(ChatCompletionRequest.serializer(), body)
        val req = Request.Builder()
            .url("$url/v1/chat/completions")
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "text/event-stream")
            .post(raw.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(400)
                throw IOException("Hermes ${resp.code}: $text")
            }
            val source = resp.body?.source() ?: throw IOException("empty response")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val chunk = runCatching { json.decodeFromString(ChatCompletionChunk.serializer(), payload) }.getOrNull()
                val delta = chunk?.choices?.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun requireConfig(): Pair<String, String> {
        return requireConfig(tokens.hermesUrl?.trimEnd('/'), tokens.hermesToken)
    }

    private fun requireConfig(rawUrl: String?, rawToken: String?): Pair<String, String> {
        val u = rawUrl?.trimEnd('/') ?: tokens.hermesUrl?.trimEnd('/')
        val t = rawToken ?: tokens.hermesToken
        if (u.isNullOrBlank()) throw IOException("Hermes URL not configured")
        if (t.isNullOrBlank()) throw IOException("Hermes token not configured")
        return u to t
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

    companion object {
        const val DEFAULT_MODEL = "hermes-agent"
    }
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
