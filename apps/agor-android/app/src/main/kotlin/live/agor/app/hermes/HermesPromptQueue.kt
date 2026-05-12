package live.agor.app.hermes

import live.agor.app.data.HermesAttachment
import java.util.concurrent.ConcurrentHashMap

internal data class QueuedHermesPrompt(
    val prompt: String,
    val imageDataUrls: List<String>,
    val attachments: List<HermesAttachment>,
)

internal class HermesPromptQueue {
    private val queuedPrompts = ConcurrentHashMap<String, ArrayDeque<QueuedHermesPrompt>>()

    fun enqueue(sessionId: String, prompt: QueuedHermesPrompt): Int {
        val queue = queuedPrompts.getOrPut(sessionId) { ArrayDeque() }
        return synchronized(queue) {
            queue.addLast(prompt)
            queue.size
        }
    }

    fun dequeue(sessionId: String): QueuedHermesPrompt? {
        val queue = queuedPrompts[sessionId] ?: return null
        return synchronized(queue) {
            val next = queue.removeFirstOrNull()
            if (queue.isEmpty()) queuedPrompts.remove(sessionId)
            next
        }
    }

    fun depth(sessionId: String): Int {
        val queue = queuedPrompts[sessionId] ?: return 0
        return synchronized(queue) { queue.size }
    }
}
