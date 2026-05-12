package live.agor.app.hermes

import live.agor.app.data.HermesAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesPromptQueueTest {
    @Test
    fun queuesPromptsPerSessionInFifoOrder() {
        val queue = HermesPromptQueue()
        val attachment = HermesAttachment(
            id = "img-1",
            mimeType = "image/png",
            localPath = "/tmp/img.png",
        )

        assertEquals(1, queue.enqueue("session-a", QueuedHermesPrompt("first", listOf("data:a"), listOf(attachment))))
        assertEquals(2, queue.enqueue("session-a", QueuedHermesPrompt("second", emptyList(), emptyList())))
        assertEquals(1, queue.enqueue("session-b", QueuedHermesPrompt("other", emptyList(), emptyList())))

        assertEquals("first", queue.dequeue("session-a")?.prompt)
        assertEquals("second", queue.dequeue("session-a")?.prompt)
        assertNull(queue.dequeue("session-a"))
        assertEquals("other", queue.dequeue("session-b")?.prompt)
    }

    @Test
    fun reportsQueueDepthForMissingSessionsAsZero() {
        val queue = HermesPromptQueue()

        assertEquals(0, queue.depth("missing"))
    }
}
