package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.network.HermesMessage
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Holds the running Hermes conversation in memory.
 *
 * Conversation history is intentionally per-process: Hermes' own server-side
 * state (the NousResearch agent loop) is the source of truth for tool-call
 * sequences and persona context. The Android app keeps a flat list of role +
 * content turns to render the chat UI; nothing more.
 */
class HermesViewModel(private val container: AppContainer) : ViewModel() {

    data class Turn(val role: String, val content: String, val streaming: Boolean = false)

    data class State(
        val turns: List<Turn> = emptyList(),
        val isSending: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var sendJob: Job? = null

    fun send(prompt: String) {
        if (prompt.isBlank() || _state.value.isSending) return
        val user = Turn(role = "user", content = prompt.trim())
        val placeholder = Turn(role = "assistant", content = "", streaming = true)
        _state.value = State(
            turns = _state.value.turns + user + placeholder,
            isSending = true,
            errorMessage = null,
        )
        sendJob = viewModelScope.launch { stream(prompt) }
    }

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        val turns = _state.value.turns.toMutableList()
        if (turns.lastOrNull()?.streaming == true) {
            val last = turns.removeAt(turns.lastIndex)
            turns += last.copy(streaming = false)
        }
        _state.value = _state.value.copy(isSending = false)
    }

    fun clear() {
        sendJob?.cancel()
        sendJob = null
        _state.value = State()
    }

    private suspend fun stream(prompt: String) {
        val client = container.hermesClient
        val messages = buildPayload()
        try {
            val builder = StringBuilder()
            client.chatStream(messages).collect { delta ->
                builder.append(delta)
                replaceLastAssistant(builder.toString(), streaming = true)
            }
            replaceLastAssistant(builder.toString(), streaming = false)
            _state.value = _state.value.copy(isSending = false)
        } catch (t: Throwable) {
            AppLogger.log("Hermes stream failed: ${t.message}", LogLevel.WARNING, "Hermes")
            // Fallback: try non-streaming once. Some proxies mangle SSE headers.
            runCatching { container.hermesClient.chat(messages) }
                .onSuccess { reply ->
                    replaceLastAssistant(reply, streaming = false)
                    _state.value = _state.value.copy(isSending = false)
                }
                .onFailure { e ->
                    val turns = _state.value.turns.toMutableList()
                    if (turns.lastOrNull()?.streaming == true) turns.removeAt(turns.lastIndex)
                    _state.value = _state.value.copy(
                        turns = turns,
                        isSending = false,
                        errorMessage = e.message ?: t.message ?: "Hermes call failed",
                    )
                }
        }
    }

    private fun buildPayload(): List<HermesMessage> {
        // Skip the trailing streaming placeholder so we don't echo it back to Hermes.
        val sourceTurns = _state.value.turns
            .let { if (it.lastOrNull()?.streaming == true) it.dropLast(1) else it }
        return sourceTurns.map { HermesMessage(role = it.role, content = it.content) }
    }

    private fun replaceLastAssistant(content: String, streaming: Boolean) {
        val turns = _state.value.turns.toMutableList()
        val last = turns.lastOrNull() ?: return
        if (last.role != "assistant") return
        turns[turns.lastIndex] = last.copy(content = content, streaming = streaming)
        _state.value = _state.value.copy(turns = turns)
    }
}
