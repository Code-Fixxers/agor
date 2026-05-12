package live.agor.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import live.agor.app.auth.SecureTokenStore
import live.agor.app.network.AgorJson
import java.security.MessageDigest

private val Context.chatDraftDataStore by preferencesDataStore(name = "agor_chat_drafts")

fun decodeChatDrafts(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        AgorJson.decodeFromString(ChatDraftMapSerializer, raw)
            .filterKeys { it.isNotBlank() }
            .mapValues { it.value.trimEnd() }
            .filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())
}

fun encodeChatDrafts(drafts: Map<String, String>): String =
    AgorJson.encodeToString(
        ChatDraftMapSerializer,
        drafts
            .filterKeys { it.isNotBlank() }
            .mapValues { it.value.trimEnd() }
            .filterValues { it.isNotBlank() },
    )

class ChatDraftStore(
    private val context: Context,
    private val tokens: SecureTokenStore,
) {
    suspend fun load(sessionId: String): String = withContext(Dispatchers.IO) {
        decodeChatDrafts(rawDrafts())[sessionId].orEmpty()
    }

    suspend fun save(sessionId: String, draft: String) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        val next = decodeChatDrafts(rawDrafts()).toMutableMap()
        val cleaned = draft.trimEnd()
        if (cleaned.isBlank()) next.remove(sessionId) else next[sessionId] = cleaned
        context.chatDraftDataStore.edit { prefs ->
            prefs[keyForCurrentServer()] = encodeChatDrafts(next)
        }
    }

    suspend fun clear(sessionId: String) = save(sessionId, "")

    private suspend fun rawDrafts(): String? =
        context.chatDraftDataStore.data.first()[keyForCurrentServer()]

    private fun keyForCurrentServer() = stringPreferencesKey(
        "drafts_${tokens.serverUrl.orEmpty().ifBlank { "default" }.sha256()}",
    )
}

private val ChatDraftMapSerializer = MapSerializer(String.serializer(), String.serializer())

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
