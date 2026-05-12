package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.data.HermesImageInput
import live.agor.app.data.HermesSession
import live.agor.app.data.HermesTurn
import live.agor.app.hermes.HermesForegroundService

/**
 * Hermes screen state backed by a disk cache plus foreground service-owned
 * streams. The ViewModel is only UI state; active Hermes calls survive it.
 */
class HermesViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val sessions: List<HermesSession> = emptyList(),
        val selectedSessionId: String? = null,
        val turns: List<HermesTurn> = emptyList(),
        val isSending: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * One-shot event stream emitting each completed assistant reply.
     *
     * Consumers (e.g. the voice controller) subscribe to TTS the final text. We use
     * a SharedFlow with replay=0 + DROP_OLDEST so a missed event during process
     * recreation never piles up.
     */
    private val _replies = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val replies: SharedFlow<String> = _replies.asSharedFlow()

    private val emittedReplyTurnIds = HashSet<String>()

    init {
        viewModelScope.launch {
            container.hermesSessions.load()
            container.hermesSessions.sessions.collect { sessions ->
                val selected = _state.value.selectedSessionId
                    ?.takeIf { id -> sessions.any { it.id == id } }
                    ?: sessions.firstOrNull()?.id
                val current = sessions.firstOrNull { it.id == selected }
                _state.value = State(
                    sessions = sessions,
                    selectedSessionId = selected,
                    turns = current?.turns.orEmpty(),
                    isSending = current?.active == true,
                    errorMessage = current?.errorMessage,
                )
                current?.turns
                    ?.lastOrNull { it.role == "assistant" && !it.streaming && it.content.isNotBlank() }
                    ?.let { turn ->
                        if (emittedReplyTurnIds.add(turn.id)) _replies.tryEmit(turn.content)
                    }
            }
        }
    }

    fun selectSession(sessionId: String) {
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        _state.value = _state.value.copy(
            selectedSessionId = session.id,
            turns = session.turns,
            isSending = session.active,
            errorMessage = session.errorMessage,
        )
    }

    fun openSession(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) selectSession(sessionId)
    }

    fun newSession() {
        viewModelScope.launch {
            val session = container.hermesSessions.createSession()
            selectSession(session.id)
        }
    }

    fun deleteSelectedSession() {
        val id = _state.value.selectedSessionId ?: return
        viewModelScope.launch { container.hermesSessions.deleteSession(id) }
    }

    fun send(prompt: String, images: List<HermesImageInput> = emptyList()) {
        if (prompt.isBlank() && images.isEmpty()) return
        viewModelScope.launch {
            val selected = _state.value.selectedSessionId
            val session = selected?.let { container.hermesSessions.getSession(it) }
                ?: container.hermesSessions.createSession(prompt)
            if (_state.value.selectedSessionId != session.id) {
                selectSession(session.id)
            }
            HermesForegroundService.startPrompt(
                context = container.appContext,
                sessionId = session.id,
                prompt = prompt,
                imageDataUrls = images.map { it.dataUrl },
                attachments = images.map { it.attachment },
            )
        }
    }

    fun retryFailedTurn() {
        val state = _state.value
        val sessionId = state.selectedSessionId ?: return
        val failedAssistantIndex = state.turns.indexOfLast { it.role == "assistant" && !it.streaming }
        if (failedAssistantIndex < 1 || state.errorMessage.isNullOrBlank()) return
        val userTurn = state.turns.subList(0, failedAssistantIndex)
            .lastOrNull { it.role == "user" }
            ?: return
        HermesForegroundService.startPrompt(
            context = container.appContext,
            sessionId = sessionId,
            prompt = userTurn.content,
            imageDataUrls = emptyList(),
            attachments = userTurn.attachments,
        )
    }

    fun resumeFailedTurn() {
        val state = _state.value
        val sessionId = state.selectedSessionId ?: return
        val failedAssistant = state.turns.lastOrNull { it.role == "assistant" && !it.streaming } ?: return
        if (state.errorMessage.isNullOrBlank()) return
        val partial = failedAssistant.content.trim()
        val prompt = if (partial.isBlank()) {
            "Continue the previous Hermes turn from where it failed."
        } else {
            "Continue the previous Hermes turn from where it failed. Do not repeat this already streamed text:\n\n$partial"
        }
        HermesForegroundService.startPrompt(
            context = container.appContext,
            sessionId = sessionId,
            prompt = prompt,
            imageDataUrls = emptyList(),
            attachments = emptyList(),
        )
    }

    fun cancel() {
        val id = _state.value.selectedSessionId ?: return
        HermesForegroundService.cancel(container.appContext, id)
    }
}
