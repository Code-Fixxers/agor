package live.agor.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.util.AppLogger

/**
 * Accumulates streaming chunks per message and emits a debounced (50ms) live snapshot
 * suitable for rendering. Final state is whatever the daemon's `messages created` event
 * produces — this only owns the in-progress text. Mirrors iOS StreamingService.swift.
 */
class StreamingService(
    private val socket: SocketService,
    private val logger: AppLogger,
) {
    data class StreamSnapshot(val text: String, val thinking: String, val finished: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val buffers = mutableMapOf<String, StringBuilder>()
    private val thinkBuffers = mutableMapOf<String, StringBuilder>()

    private val _live = MutableStateFlow<Map<String, StreamSnapshot>>(emptyMap())
    val live: StateFlow<Map<String, StreamSnapshot>> = _live.asStateFlow()

    private var debounceJob: Job? = null

    init {
        scope.launch { socket.streamingStart.collect { ev ->
            val key = ev.messageId ?: return@collect
            buffers.getOrPut(key) { StringBuilder() }
            thinkBuffers.getOrPut(key) { StringBuilder() }
            scheduleEmit()
        } }
        scope.launch { socket.streamingChunk.collect { ev ->
            val key = ev.messageId ?: ev.sessionId
            buffers.getOrPut(key) { StringBuilder() }.append(ev.text)
            scheduleEmit()
        } }
        scope.launch { socket.thinkingChunk.collect { ev ->
            val key = ev.messageId ?: ev.sessionId
            thinkBuffers.getOrPut(key) { StringBuilder() }.append(ev.text)
            scheduleEmit()
        } }
        scope.launch { socket.streamingEnd.collect { ev ->
            val key = ev.messageId ?: return@collect
            emitNow(key, finished = true)
        } }
        scope.launch { socket.streamingError.collect { ev ->
            logger // referenced to avoid unused
            for ((k, _) in buffers.toMap()) emitNow(k, finished = true)
        } }
    }

    fun snapshot(messageId: String): StreamSnapshot? = _live.value[messageId]

    /** Drop a buffer once the canonical `messages created` arrives. */
    fun finalize(messageId: String) {
        buffers.remove(messageId)
        thinkBuffers.remove(messageId)
        _live.value = _live.value - messageId
    }

    private fun scheduleEmit() {
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch {
            delay(50)
            emitAll(finished = false)
        }
    }

    private fun emitAll(finished: Boolean) {
        val snap = buffers.mapValues { (k, sb) ->
            StreamSnapshot(sb.toString(), thinkBuffers[k]?.toString().orEmpty(), finished)
        }
        _live.value = snap
    }

    private fun emitNow(messageId: String, finished: Boolean) {
        val text = buffers[messageId]?.toString().orEmpty()
        val thinking = thinkBuffers[messageId]?.toString().orEmpty()
        _live.value = _live.value + (messageId to StreamSnapshot(text, thinking, finished))
    }
}
