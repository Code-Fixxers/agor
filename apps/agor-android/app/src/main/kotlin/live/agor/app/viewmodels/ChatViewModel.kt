package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import live.agor.app.AppContainer
import live.agor.app.models.AgorTask
import live.agor.app.models.InputRequestStatus
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.PermissionStatus
import live.agor.app.models.Session
import live.agor.app.network.StreamingService
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * ViewModel backing a single session's chat screen.
 *
 * Design (changed in the perf overhaul, see plans/floofy-weaving-key.md):
 *
 *  - Three independent data flows ([messages], [tasks], [live]) instead of one
 *    monolithic [UiState]. A streaming chunk only churns [live]; tasks/messages
 *    keep their stable references and Compose smart-skip applies to bubbles
 *    whose own snapshot didn't change.
 *
 *  - Transient UI fields (loading, error, draft, session metadata) live in a
 *    separate [uiState] so a 45 s polling refresh that flips `isLoading` does
 *    not invalidate any row-flattening derivation downstream.
 *
 *  - Message inserts/patches use binary-search sorted insertion instead of
 *    `filterNot + sortedBy` over the whole list per event.
 */
class ChatViewModel(private val container: AppContainer, val sessionId: String) : ViewModel() {

    /** Transient UI bits — never observed by row flattening. */
    data class UiState(
        val session: Session? = null,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val draft: String = "",
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _tasks = MutableStateFlow<List<AgorTask>>(emptyList())
    val tasks: StateFlow<List<AgorTask>> = _tasks.asStateFlow()

    /** Mirrors [StreamingService.live] directly; no extra copy. */
    val live: StateFlow<Map<String, StreamingService.StreamSnapshot>> = container.streaming.live

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Lazy derivation — scans messages from the end, returns first pending. */
    val pendingPermissionId: StateFlow<String?> = _messages
        .map(::firstPendingPermission)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingInputRequestId: StateFlow<String?> = _messages
        .map(::firstPendingInput)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        container.socket.onMessageCreated { msg ->
            if (msg.sessionId != sessionId) return@onMessageCreated
            viewModelScope.launch { upsertSorted(msg) }
        }
        container.socket.onMessagePatched { msg ->
            if (msg.sessionId != sessionId) return@onMessagePatched
            viewModelScope.launch { upsertSorted(msg) }
        }
        container.socket.onTaskCreated { t ->
            if (t.sessionId != sessionId) return@onTaskCreated
            viewModelScope.launch { _tasks.update { current -> current + t } }
        }
        container.socket.onTaskPatched { t ->
            if (t.sessionId != sessionId) return@onTaskPatched
            viewModelScope.launch {
                _tasks.update { current ->
                    val idx = current.indexOfFirst { it.taskId == t.taskId }
                    if (idx < 0) current + t
                    else current.toMutableList().apply { set(idx, t) }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val session = container.client.getSession(sessionId)
                val tasks = container.client.listTasks(sessionId)
                val messages = container.client.listMessages(sessionId, limit = 200)
                _messages.value = messages.sortedBy { it.index }
                _tasks.value = tasks
                _uiState.update {
                    it.copy(session = session, isLoading = false)
                }
            } catch (t: Throwable) {
                AppLogger.log("Chat load failed: ${t.message}", LogLevel.ERROR, "Chat")
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message) }
            }
        }
    }

    fun loadEarlier() {
        viewModelScope.launch {
            val current = _messages.value
            val skip = current.size
            val older = container.client.listMessages(sessionId, limit = 100, skip = skip)
            // Older messages have lower indexes; merge then re-sort. This path is
            // a user-driven action (button tap) — not in the streaming hot path —
            // so a single sortedBy is fine.
            _messages.value = (older + current).sortedBy { it.index }
        }
    }

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun sendPrompt() {
        val text = _uiState.value.draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            try {
                container.client.sendPrompt(sessionId, text)
                _uiState.update { it.copy(draft = "") }
            } catch (t: Throwable) {
                AppLogger.log("Send failed: ${t.message}", LogLevel.ERROR, "Chat")
                _uiState.update { it.copy(errorMessage = t.message) }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { container.client.stopSession(sessionId) }
        }
    }

    fun decidePermission(permissionId: String, approve: Boolean) {
        viewModelScope.launch {
            runCatching { container.client.decidePermission(sessionId, permissionId, approve) }
        }
    }

    fun answerInputRequest(inputRequestId: String, answers: List<String>) {
        viewModelScope.launch {
            runCatching { container.client.answerInputRequest(sessionId, inputRequestId, answers) }
        }
    }

    fun archiveSession() {
        viewModelScope.launch {
            runCatching {
                container.client.patchSession(
                    sessionId,
                    JsonObject(mapOf("archived" to JsonPrimitive(true))),
                )
            }
        }
    }

    /**
     * Replace-or-insert [message] in [_messages] while keeping the list sorted
     * by [Message.index]. O(log n) search + O(n) array-list insert; the previous
     * implementation was O(n) filter + O(n log n) sort on every socket event.
     */
    private fun upsertSorted(message: Message) {
        _messages.update { current ->
            val withoutDup = if (current.any { it.messageId == message.messageId }) {
                current.filterNot { it.messageId == message.messageId }
            } else {
                current
            }
            val pos = withoutDup.binarySearch { it.index.compareTo(message.index) }
            val insertIdx = if (pos < 0) -(pos + 1) else pos
            val out = ArrayList<Message>(withoutDup.size + 1)
            out.addAll(withoutDup)
            if (insertIdx >= out.size) out.add(message) else out.add(insertIdx, message)
            out
        }
        // Drop the streaming buffer for this id once the canonical message lands.
        container.streaming.finalize(message.messageId)
    }

    /** Walks the (already sorted) message list from the end. */
    private fun firstPendingPermission(messages: List<Message>): String? {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val c = m.content
            if (c is MessageContent.Permission && c.request.status == PermissionStatus.PENDING) {
                return c.request.permissionId
            }
        }
        return null
    }

    private fun firstPendingInput(messages: List<Message>): String? {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val c = m.content
            if (c is MessageContent.InputRequest && c.request.status == InputRequestStatus.PENDING) {
                return c.request.inputRequestId
            }
        }
        return null
    }
}
