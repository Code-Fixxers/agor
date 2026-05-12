package live.agor.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDraftStoreTest {
    @Test
    fun chatDraftsIgnoreBlankSessionIdsAndDrafts() {
        val raw = encodeChatDrafts(
            mapOf(
                "" to "ignored",
                "session-a" to "hello",
                "session-b" to "",
            ),
        )

        val decoded = decodeChatDrafts(raw)

        assertEquals(mapOf("session-a" to "hello"), decoded)
    }

    @Test
    fun invalidChatDraftsFallBackToEmpty() {
        assertEquals(emptyMap<String, String>(), decodeChatDrafts("not-json"))
    }
}
