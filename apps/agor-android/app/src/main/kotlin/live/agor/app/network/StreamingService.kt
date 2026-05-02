package live.agor.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.agor.app.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Accumulates streaming chunks per message and emits coalesced live snapshots
 * suitable for rendering.
 *
 * Design notes (changed in the perf overhaul, see plans/floofy-weaving-key.md):
 *
 *  - Buffers are [ConcurrentHashMap] because three independent collectors mutate
 *    them concurrently from `Dispatchers.Default`. The previous `mutableMapOf`
 *    raced under high stream throughput.
 *
 *  - Throttling is done via a [MutableSharedFlow] + [sample] @ 100 ms instead of
 *    a manual debounce job. The old guard `if (debounceJob?.isActive) return`
 *    silently dropped trailing chunks before a long pause; `sample` keeps the
 *    most recent value and never drops the trailing edge.
 *
 *  - Chunk emissions update a single map entry via [MutableStateFlow.update]
 *    rather than rebuilding `buffers.mapValues { … }`. Map identity stays for
 *    unrelated entries, which is what allows Compose smart-skip to avoid
 *    re-recomposing message bubbles whose own snapshot didn't change.
 */
class StreamingService(
    private val socket: SocketService,
    private val logger: AppLogger,
) {
    data class StreamSnapshot(val text: String, val thinking: String, val finished: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val buffers = ConcurrentHashMap<String, StringBuilder>()
    private val thinkBuffers = ConcurrentHashMap<String, StringBuilder>()

    private val _live = MutableStateFlow<Map<String, StreamSnapshot>>(emptyMap())
    val live: StateFlow<Map<String, StreamSnapshot>> = _live.asStateFlow()

    /** Coalesced "this messageId got a new chunk; emit a snapshot for it" signal. */
    private val emitTicks = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    init {
        // Sample at 100ms — at most ~10 emissions/sec per message under heavy
        // streaming. Multiple chunks for the same messageId between ticks
        // coalesce into one snapshot read.
        scope.launch {
            emitTicks
                .sample(100)
                .collect { messageId -> emitFor(messageId, finished = false) }
        }

        scope.launch {
            socket.streamingStart.collect { ev ->
                val key = ev.messageId ?: return@collect
                buffers.computeIfAbsent(key) { StringBuilder() }
                thinkBuffers.computeIfAbsent(key) { StringBuilder() }
                emitTicks.tryEmit(key)
            }
        }
        scope.launch {
            socket.streamingChunk.collect { ev ->
                val key = ev.messageId ?: ev.sessionId
                buffers.computeIfAbsent(key) { StringBuilder() }.append(ev.text)
                emitTicks.tryEmit(key)
            }
        }
        scope.launch {
            socket.thinkingChunk.collect { ev ->
                val key = ev.messageId ?: ev.sessionId
                thinkBuffers.computeIfAbsent(key) { StringBuilder() }.append(ev.text)
                emitTicks.tryEmit(key)
            }
        }
        scope.launch {
            socket.streamingEnd.collect { ev ->
                val key = ev.messageId ?: return@collect
                emitFor(key, finished = true)
            }
        }
        scope.launch {
            socket.streamingError.collect {
                logger // referenced to avoid unused
                // Mark every active buffer finished so orphan placeholders settle.
                for (key in buffers.keys.toList()) emitFor(key, finished = true)
            }
        }
    }

    fun snapshot(messageId: String): StreamSnapshot? = _live.value[messageId]

    /** Drop a buffer once the canonical `messages created` event arrives. */
    fun finalize(messageId: String) {
        buffers.remove(messageId)
        thinkBuffers.remove(messageId)
        _live.update { it - messageId }
    }

    /**
     * Update only this messageId's snapshot. Other entries keep their
     * StreamSnapshot reference, so Compose smart-skip can ignore unrelated
     * bubbles when the new map is read.
     */
    private fun emitFor(messageId: String, finished: Boolean) {
        val text = buffers[messageId]?.toString().orEmpty()
        val thinking = thinkBuffers[messageId]?.toString().orEmpty()
        val snap = StreamSnapshot(text, thinking, finished)
        _live.update { current ->
            val prev = current[messageId]
            if (prev == snap) current else current + (messageId to snap)
        }
    }
}
