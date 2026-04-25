package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import live.agor.app.AppContainer
import live.agor.app.models.AgorTask
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.PermissionStatus
import live.agor.app.models.Session
import live.agor.app.network.StreamingService
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * ViewModel backing a single session's chat screen. Coordinates:
 *   - REST loads (session, tasks, messages, files)
 *   - Live socket events (messages created/patched, streaming chunks)
 *   - Drafts (per-session, in-memory, persisted to SidebarCache could be added)
 */
class ChatViewModel(private val container: AppContainer, val sessionId: String) : ViewModel() {

    data class UiState(
        val session: Session? = null,
        val tasks: List<AgorTask> = emptyList(),
        val messages: List<Message> = emptyList(),
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val draft: String = "",
        val pendingPermissionId: String? = null,
        val pendingInputRequestId: String? = null,
        val live: Map<String, StreamingService.StreamSnapshot> = emptyMap(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        container.socket.onMessageCreated { msg ->
            if (msg.sessionId != sessionId) return@onMessageCreated
            viewModelScope.launch { onMessageCreated(msg) }
        }
        container.socket.onMessagePatched { msg ->
            if (msg.sessionId != sessionId) return@onMessagePatched
            viewModelScope.launch { onMessagePatched(msg) }
        }
        container.socket.onTaskCreated { t ->
            if (t.sessionId != sessionId) return@onTaskCreated
            viewModelScope.launch { _state.value = _state.value.copy(tasks = _state.value.tasks + t) }
        }
        container.socket.onTaskPatched { t ->
            if (t.sessionId != sessionId) return@onTaskPatched
            viewModelScope.launch {
                val updated = _state.value.tasks.map { if (it.taskId == t.taskId) t else it }
                _state.value = _state.value.copy(tasks = updated)
            }
        }
        viewModelScope.launch {
            container.streaming.live.collect { live ->
                _state.value = _state.value.copy(live = live)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            try {
                val session = container.client.getSession(sessionId)
                val tasks = container.client.listTasks(sessionId)
                val messages = container.client.listMessages(sessionId, limit = 200)
                _state.value = _state.value.copy(
                    session = session,
                    tasks = tasks,
                    messages = messages,
                    pendingPermissionId = firstPendingPermission(messages),
                    pendingInputRequestId = firstPendingInput(messages),
                    isLoading = false,
                )
            } catch (t: Throwable) {
                AppLogger.log("Chat load failed: ${t.message}", LogLevel.ERROR, "Chat")
                _state.value = _state.value.copy(isLoading = false, errorMessage = t.message)
            }
        }
    }

    fun loadEarlier() {
        viewModelScope.launch {
            val current = _state.value.messages
            val skip = current.size
            val older = container.client.listMessages(sessionId, limit = 100, skip = skip)
            _state.value = _state.value.copy(messages = older + current)
        }
    }

    fun updateDraft(text: String) { _state.value = _state.value.copy(draft = text) }

    fun sendPrompt() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            try {
                container.client.sendPrompt(sessionId, text)
                _state.value = _state.value.copy(draft = "")
            } catch (t: Throwable) {
                AppLogger.log("Send failed: ${t.message}", LogLevel.ERROR, "Chat")
                _state.value = _state.value.copy(errorMessage = t.message)
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

    private fun onMessageCreated(message: Message) {
        val current = _state.value.messages
        val replaced = current.filterNot { it.messageId == message.messageId } + message
        _state.value = _state.value.copy(
            messages = replaced.sortedBy { it.index },
            pendingPermissionId = firstPendingPermission(replaced),
            pendingInputRequestId = firstPendingInput(replaced),
        )
        container.streaming.finalize(message.messageId)
    }

    private fun onMessagePatched(message: Message) = onMessageCreated(message)

    private fun firstPendingPermission(messages: List<Message>): String? {
        val sorted = messages.sortedByDescending { it.index }
        for (m in sorted) {
            if (m.content is MessageContent.Permission &&
                m.content.request.status == PermissionStatus.PENDING) {
                return m.content.request.permissionId
            }
        }
        return null
    }

    private fun firstPendingInput(messages: List<Message>): String? {
        val sorted = messages.sortedByDescending { it.index }
        for (m in sorted) {
            if (m.content is MessageContent.InputRequest &&
                m.content.request.status == live.agor.app.models.InputRequestStatus.PENDING) {
                return m.content.request.inputRequestId
            }
        }
        return null
    }
}
