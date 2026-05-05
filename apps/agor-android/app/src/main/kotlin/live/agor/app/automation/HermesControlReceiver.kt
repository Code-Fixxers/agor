package live.agor.app.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import live.agor.app.AgorApplication
import live.agor.app.BuildConfig
import live.agor.app.data.HermesImageInput
import live.agor.app.hermes.HermesForegroundService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HermesControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            setJsonResult(context, false, null, "Automation API is debug-only", null)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            var requestId: String? = null
            try {
                val json = resolveControlPayload(intent)
                    ?: throw IllegalArgumentException("Missing automation payload")
                requestId = json.optString(AutomationProtocol.KEY_REQUEST_ID, null)
                val app = context.applicationContext as AgorApplication
                val container = app.container
                val command = json.optString(AutomationProtocol.KEY_COMMAND).trim()
                val data = when (command) {
                    AutomationProtocol.COMMAND_PING -> JSONObject().put("message", "PONG")
                    AutomationProtocol.COMMAND_LOGIN -> {
                        val serverUrl = json.optString(AutomationProtocol.KEY_SERVER_URL)
                        val email = json.optString(AutomationProtocol.KEY_EMAIL, null)
                        val password = json.optString(AutomationProtocol.KEY_PASSWORD, null)
                        val apiKey = json.optString(AutomationProtocol.KEY_API_KEY, null)
                        if (!apiKey.isNullOrBlank()) container.authService.loginWithApiKey(serverUrl, apiKey)
                        else {
                            if (email.isNullOrBlank() || password.isNullOrBlank()) {
                                throw IllegalArgumentException("email and password are required")
                            }
                            container.authService.login(serverUrl, email, password)
                        }
                        if (json.optBoolean(AutomationProtocol.KEY_CONNECT_SOCKET, true)) container.socket.connect()
                        JSONObject().put("message", "Login successful")
                    }
                    AutomationProtocol.COMMAND_HERMES_CONFIGURE -> {
                        configureHermes(container, json)
                        JSONObject().put("message", "Hermes configured")
                    }
                    AutomationProtocol.COMMAND_HERMES_CAPABILITIES,
                    "hermes.capabilities" -> {
                        configureHermes(container, json, requireToken = false)
                        JSONObject(container.hermesClient.capabilities())
                    }
                    AutomationProtocol.COMMAND_HERMES_SESSION_CREATE,
                    "hermes.session.create" -> {
                        val session = container.hermesSessions.createSession(json.optString("title", null))
                        JSONObject().put("sessionId", session.id).put("conversationId", session.conversationId)
                    }
                    AutomationProtocol.COMMAND_HERMES_SESSION_LIST,
                    "hermes.session.list" -> {
                        val sessions = container.hermesSessions.load()
                        JSONObject().put(
                            "sessions",
                            JSONArray(sessions.map { s ->
                                JSONObject()
                                    .put("sessionId", s.id)
                                    .put("title", s.title)
                                    .put("active", s.active)
                                    .put("turns", s.turns.size)
                            }),
                        )
                    }
                    AutomationProtocol.COMMAND_HERMES_SESSION_DELETE,
                    "hermes.session.delete" -> {
                        val sessionId = requireSessionId(json)
                        container.hermesSessions.deleteSession(sessionId)
                        JSONObject().put("deleted", sessionId)
                    }
                    AutomationProtocol.COMMAND_HERMES_SEND,
                    "hermes.send" -> {
                        configureHermes(container, json, requireToken = false)
                        val prompt = json.optString(AutomationProtocol.KEY_PROMPT).ifBlank {
                            json.optString(AutomationProtocol.KEY_HERMES_PROMPT)
                        }
                        val existing = json.optString(AutomationProtocol.KEY_SESSION_ID, null)
                        val images = importImages(container, json.optJSONArray(AutomationProtocol.KEY_IMAGES))
                        val session = if (existing.isNullOrBlank()) container.hermesSessions.createSession(prompt)
                        else container.hermesSessions.getSession(existing) ?: container.hermesSessions.createSession(prompt)
                        val baselineTurns = session.turns.size
                        HermesForegroundService.startPrompt(
                            context.applicationContext,
                            session.id,
                            prompt,
                            images.map { it.dataUrl },
                            images.map { it.attachment },
                        )
                        if (json.optBoolean(AutomationProtocol.KEY_WAIT_FOR_COMPLETION, false)) {
                            val timeoutMs = json.optLong(AutomationProtocol.KEY_TIMEOUT_MS, 60_000L)
                            withTimeoutOrNull(timeoutMs) {
                                container.hermesSessions.sessions.first { list ->
                                    val current = list.firstOrNull { it.id == session.id }
                                    current != null && current.turns.size > baselineTurns && !current.active
                                }
                            }
                        }
                        JSONObject().put("sessionId", session.id).put("active", container.hermesSessions.getSession(session.id)?.active)
                    }
                    AutomationProtocol.COMMAND_HERMES_STATUS,
                    "hermes.status" -> {
                        val session = container.hermesSessions.getSession(requireSessionId(json))
                            ?: throw IllegalArgumentException("Unknown Hermes session")
                        JSONObject()
                            .put("sessionId", session.id)
                            .put("title", session.title)
                            .put("active", session.active)
                            .put("turns", session.turns.size)
                            .put("errorMessage", session.errorMessage)
                    }
                    AutomationProtocol.COMMAND_HERMES_CANCEL,
                    "hermes.cancel" -> {
                        val sessionId = requireSessionId(json)
                        HermesForegroundService.cancel(context.applicationContext, sessionId)
                        JSONObject().put("cancelled", sessionId)
                    }
                    AutomationProtocol.COMMAND_HERMES_LAST_RESPONSE,
                    "hermes.last_response" -> {
                        val session = container.hermesSessions.getSession(requireSessionId(json))
                            ?: throw IllegalArgumentException("Unknown Hermes session")
                        val turn = session.turns.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                        JSONObject()
                            .put("sessionId", session.id)
                            .put("active", session.active)
                            .put("response", turn?.content.orEmpty())
                    }
                    else -> throw IllegalArgumentException("Unknown automation command: $command")
                }
                setJsonResult(context, true, requestId, null, data)
            } catch (t: Throwable) {
                setJsonResult(context, false, requestId, t.message ?: "Automation failed", null)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configureHermes(
        container: live.agor.app.AppContainer,
        json: JSONObject,
        requireToken: Boolean = true,
    ) {
        val rawUrl = json.optString(AutomationProtocol.KEY_HERMES_URL, null)
        val token = json.optString(AutomationProtocol.KEY_HERMES_TOKEN, null)
        val model = json.optString(AutomationProtocol.KEY_HERMES_MODEL, null)
        if (!rawUrl.isNullOrBlank()) container.tokenStore.hermesUrl = rawUrl.trim().trimEnd('/')
        if (!token.isNullOrBlank()) container.tokenStore.hermesToken = token.trim()
        if (!model.isNullOrBlank()) container.tokenStore.hermesModel = model.trim()
        if (requireToken && (rawUrl.isNullOrBlank() || token.isNullOrBlank())) {
            throw IllegalArgumentException("Hermes URL and token are required")
        }
    }

    private suspend fun importImages(
        container: live.agor.app.AppContainer,
        images: JSONArray?,
    ): List<HermesImageInput> {
        if (images == null) return emptyList()
        val out = ArrayList<HermesImageInput>(images.length())
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            val dataUrl = item.optString(AutomationProtocol.KEY_DATA_URL)
            if (dataUrl.isNotBlank()) out += container.hermesImages.importDataUrl(dataUrl)
        }
        return out
    }

    private fun requireSessionId(json: JSONObject): String {
        return json.optString(AutomationProtocol.KEY_SESSION_ID).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("sessionId is required")
    }

    private fun resolveControlPayload(intent: Intent): JSONObject? {
        stringExtra(intent, AutomationProtocol.EXTRA_COMMAND_JSON)?.takeIf { it.isNotBlank() }?.let {
            return runCatching { JSONObject(it.trim()) }.getOrNull()
        }
        stringExtra(intent, AutomationProtocol.EXTRA_COMMAND_JSON_BASE64)?.takeIf { it.isNotBlank() }?.let {
            return runCatching { JSONObject(String(Base64.decode(it.trim(), Base64.DEFAULT))) }.getOrNull()
        }
        val command = stringExtra(intent, AutomationProtocol.EXTRA_COMMAND)?.trim()
        if (command.isNullOrBlank()) return null
        return JSONObject().apply {
            put(AutomationProtocol.KEY_COMMAND, command)
            stringExtra(intent, AutomationProtocol.EXTRA_REQUEST_ID)?.let { put(AutomationProtocol.KEY_REQUEST_ID, it) }
            stringExtra(intent, AutomationProtocol.EXTRA_SERVER_URL_LEGACY)?.let { put(AutomationProtocol.KEY_SERVER_URL, it) }
            stringExtra(intent, AutomationProtocol.EXTRA_EMAIL_LEGACY)?.let { put(AutomationProtocol.KEY_EMAIL, it) }
            stringExtra(intent, AutomationProtocol.EXTRA_PASSWORD_LEGACY)?.let { put(AutomationProtocol.KEY_PASSWORD, it) }
            stringExtra(intent, AutomationProtocol.EXTRA_API_KEY_LEGACY)?.let { put(AutomationProtocol.KEY_API_KEY, it) }
        }
    }

    private fun stringExtra(intent: Intent, key: String): String? {
        intent.getStringExtra(key)?.let { return it }
        val extras = intent.extras ?: return null
        return extras.keySet()
            .firstOrNull { it == key || it.endsWith(key.substringAfterLast('.')) }
            ?.let { extras.get(it)?.toString() }
    }

    private fun setJsonResult(
        context: Context,
        success: Boolean,
        requestId: String?,
        error: String?,
        data: JSONObject?,
    ) {
        val response = JSONObject()
            .put(AutomationProtocol.KEY_SUCCESS, success)
            .put(AutomationProtocol.KEY_REQUEST_ID, requestId)
        if (error != null) response.put("error", error)
        if (data != null) response.put("data", data)
        writeResultFile(context, requestId, response)
        runCatching {
            setResultCode(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            setResultData(response.toString())
            setResultExtras(Bundle().apply {
                putString(AutomationProtocol.KEY_MESSAGE, response.toString())
            })
        }
    }

    private fun writeResultFile(context: Context, requestId: String?, response: JSONObject) {
        val dir = File(context.filesDir, "automation_results").apply { mkdirs() }
        val rawName = requestId?.takeIf { it.isNotBlank() } ?: "latest"
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val text = response.toString()
        File(dir, "$safeName.json").writeText(text)
        File(dir, "latest.json").writeText(text)
    }
}
