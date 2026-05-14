package live.agor.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.agor.app.auth.SecureTokenStore
import live.agor.app.network.AgorJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class HermesSessionStore(
    context: Context,
    private val tokens: SecureTokenStore,
) {
    private val root = File(context.filesDir, "hermes_sessions")
    private val mutex = Mutex()
    private var loadedKey: String? = null
    private val _sessions = MutableStateFlow<List<HermesSession>>(emptyList())
    val sessions: StateFlow<List<HermesSession>> = _sessions.asStateFlow()
    private val _events = MutableSharedFlow<HermesSessionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<HermesSessionEvent> = _events.asSharedFlow()

    suspend fun load(): List<HermesSession> = mutex.withLock {
        loadLocked()
        _sessions.value
    }

    suspend fun createSession(titleSeed: String? = null): HermesSession = update { sessions ->
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val title = titleSeed?.trim()?.take(48)?.ifBlank { null } ?: "New Hermes session"
        val session = HermesSession(
            id = id,
            conversationId = "agor-android-$id",
            title = title,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        listOf(session) + sessions
    }.first()

    suspend fun getSession(sessionId: String): HermesSession? {
        load()
        return _sessions.value.firstOrNull { it.id == sessionId }
    }

    suspend fun deleteSession(sessionId: String) {
        update { sessions -> sessions.filterNot { it.id == sessionId } }
    }

    suspend fun syncRemote(remoteSessions: List<HermesSession>): List<HermesSession> {
        if (remoteSessions.isEmpty()) return load()
        return update { sessions ->
            val remainingRemote = remoteSessions.associateBy { it.conversationId }.toMutableMap()
            val merged = sessions.map { local ->
                val remote = remainingRemote.remove(local.conversationId) ?: return@map local
                mergeRemoteSession(local, remote)
            } + remainingRemote.values
            merged.distinctBy { it.conversationId }
                .sortedByDescending { it.updatedAtMillis }
        }
    }

    suspend fun beginTurn(
        sessionId: String,
        prompt: String,
        attachments: List<HermesAttachment>,
    ): String {
        val assistantTurnId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        update { sessions ->
            sessions.map { session ->
                if (session.id != sessionId) return@map session
                val title = if (session.turns.isEmpty()) prompt.trim().take(48).ifBlank { session.title } else session.title
                session.copy(
                    title = title,
                    updatedAtMillis = now,
                    active = true,
                    errorMessage = null,
                    turns = session.turns +
                        HermesTurn(
                            id = UUID.randomUUID().toString(),
                            role = "user",
                            content = prompt.trim(),
                            createdAtMillis = now,
                            attachments = attachments,
                        ) +
                        HermesTurn(
                            id = assistantTurnId,
                            role = "assistant",
                            content = "",
                            createdAtMillis = now,
                            streaming = true,
                        ),
                )
            }.sortedByDescending { it.updatedAtMillis }
        }
        return assistantTurnId
    }

    suspend fun appendAssistantDelta(
        sessionId: String,
        turnId: String,
        delta: String,
        replaceExisting: Boolean = false,
        emitTextEvent: Boolean = true,
    ) {
        if (delta.isEmpty()) return
        updateTurn(sessionId, turnId) { turn ->
            turn.copy(
                content = if (replaceExisting) delta else turn.content + delta,
                streaming = true,
            )
        }
        if (emitTextEvent) _events.tryEmit(HermesSessionEvent.TextDelta(sessionId, turnId, delta))
    }

    suspend fun appendProgress(sessionId: String, turnId: String, label: String) {
        updateTurn(sessionId, turnId) { turn ->
            if (turn.progress.lastOrNull()?.label == label) turn
            else turn.copy(progress = turn.progress + HermesProgressItem(label = label, atMillis = System.currentTimeMillis()))
        }
        _events.tryEmit(HermesSessionEvent.Progress(sessionId, turnId, label))
    }

    suspend fun completeAssistant(
        sessionId: String,
        turnId: String,
        responseId: String?,
        finalText: String?,
    ) {
        var completedText = ""
        val now = System.currentTimeMillis()
        update { sessions ->
            sessions.map { session ->
                if (session.id != sessionId) return@map session
                completedText = finalText?.takeIf { it.isNotBlank() }
                    ?: session.turns.firstOrNull { it.id == turnId }?.content
                    ?: ""
                session.copy(
                    updatedAtMillis = now,
                    active = false,
                    lastResponseId = responseId ?: session.lastResponseId,
                    turns = session.turns.map { turn ->
                        if (turn.id != turnId) turn
                        else turn.copy(
                            content = finalText?.takeIf { it.isNotBlank() } ?: turn.content,
                            streaming = false,
                        )
                    },
                )
            }.sortedByDescending { it.updatedAtMillis }
        }
        _events.tryEmit(HermesSessionEvent.Completed(sessionId, turnId, completedText))
    }

    suspend fun failAssistant(sessionId: String, turnId: String, message: String) {
        val now = System.currentTimeMillis()
        update { sessions ->
            sessions.map { session ->
                if (session.id != sessionId) return@map session
                session.copy(
                    updatedAtMillis = now,
                    active = false,
                    errorMessage = message,
                    turns = session.turns.map { turn ->
                        if (turn.id != turnId) turn else turn.copy(streaming = false)
                    },
                )
            }.sortedByDescending { it.updatedAtMillis }
        }
        _events.tryEmit(HermesSessionEvent.Failed(sessionId, turnId, message))
    }

    suspend fun cancelSession(sessionId: String) {
        val now = System.currentTimeMillis()
        update { sessions ->
            sessions.map { session ->
                if (session.id != sessionId) return@map session
                session.copy(
                    updatedAtMillis = now,
                    active = false,
                    errorMessage = "Hermes run cancelled",
                    turns = session.turns.map { it.copy(streaming = false) },
                )
            }.sortedByDescending { it.updatedAtMillis }
        }
    }

    private suspend fun updateTurn(
        sessionId: String,
        turnId: String,
        transform: (HermesTurn) -> HermesTurn,
    ) {
        update { sessions ->
            sessions.map { session ->
                if (session.id != sessionId) return@map session
                session.copy(
                    updatedAtMillis = System.currentTimeMillis(),
                    turns = session.turns.map { turn -> if (turn.id == turnId) transform(turn) else turn },
                )
            }.sortedByDescending { it.updatedAtMillis }
        }
    }

    private suspend fun update(transform: (List<HermesSession>) -> List<HermesSession>): List<HermesSession> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                val next = transform(_sessions.value)
                saveLocked(next)
                _sessions.value = next
                next
            }
        }

    private fun loadLocked() {
        val key = scopeKey()
        if (loadedKey == key) return
        loadedKey = key
        val file = fileFor(key)
        _sessions.value = if (!file.exists()) {
            emptyList()
        } else {
            runCatching {
                decodeHermesSessions(file.readText())
            }.getOrDefault(emptyList())
        }
    }

    private fun saveLocked(sessions: List<HermesSession>) {
        val file = fileFor(scopeKey())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encodeHermesSessions(sessions))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun scopeKey(): String = (tokens.hermesUrl ?: "default").sha256()
    private fun fileFor(key: String): File = File(root, "$key.json")

    private fun mergeRemoteSession(local: HermesSession, remote: HermesSession): HermesSession {
        if (local.active) return local
        val useRemoteTurns = remote.turns.size > local.turns.size ||
            (remote.turns.isNotEmpty() && remote.updatedAtMillis > local.updatedAtMillis)
        return local.copy(
            title = remote.title.takeIf { it.isNotBlank() && it != "Hermes session" } ?: local.title,
            updatedAtMillis = maxOf(local.updatedAtMillis, remote.updatedAtMillis),
            lastResponseId = remote.lastResponseId ?: local.lastResponseId,
            turns = if (useRemoteTurns) remote.turns else local.turns,
        )
    }
}

@Serializable
private data class HermesSessionIndex(val sessions: List<HermesSession>)

internal fun encodeHermesSessions(sessions: List<HermesSession>): String =
    AgorJson.encodeToString(HermesSessionIndex.serializer(), HermesSessionIndex(sessions))

internal fun decodeHermesSessions(raw: String): List<HermesSession> =
    runCatching {
        AgorJson.decodeFromString(HermesSessionIndex.serializer(), raw).sessions
    }.getOrDefault(emptyList())

@Serializable
data class HermesSession(
    val id: String,
    val conversationId: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val active: Boolean = false,
    val lastResponseId: String? = null,
    val errorMessage: String? = null,
    val turns: List<HermesTurn> = emptyList(),
)

@Serializable
data class HermesTurn(
    val id: String,
    val role: String,
    val content: String,
    val createdAtMillis: Long,
    val streaming: Boolean = false,
    val attachments: List<HermesAttachment> = emptyList(),
    val progress: List<HermesProgressItem> = emptyList(),
)

@Serializable
data class HermesAttachment(
    val id: String,
    val mimeType: String,
    val localPath: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class HermesProgressItem(
    val label: String,
    @SerialName("at") val atMillis: Long,
)

sealed interface HermesSessionEvent {
    val sessionId: String
    val turnId: String

    data class TextDelta(
        override val sessionId: String,
        override val turnId: String,
        val text: String,
    ) : HermesSessionEvent

    data class Progress(
        override val sessionId: String,
        override val turnId: String,
        val label: String,
    ) : HermesSessionEvent

    data class Completed(
        override val sessionId: String,
        override val turnId: String,
        val text: String,
    ) : HermesSessionEvent

    data class Failed(
        override val sessionId: String,
        override val turnId: String,
        val message: String,
    ) : HermesSessionEvent
}

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
