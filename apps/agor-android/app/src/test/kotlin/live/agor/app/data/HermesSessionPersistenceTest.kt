package live.agor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HermesSessionPersistenceTest {
    @Test
    fun persistedHermesSessionsKeepFailedTurnsAndErrorMessage() {
        val session = HermesSession(
            id = "session-1",
            conversationId = "conversation-1",
            title = "Broken token",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            errorMessage = "Hermes token expired or rejected.",
            turns = listOf(
                HermesTurn(
                    id = "user-1",
                    role = "user",
                    content = "Summarize my work",
                    createdAtMillis = 1L,
                ),
                HermesTurn(
                    id = "assistant-1",
                    role = "assistant",
                    content = "Partial",
                    createdAtMillis = 2L,
                    streaming = false,
                ),
            ),
        )

        val restored = decodeHermesSessions(encodeHermesSessions(listOf(session))).single()

        assertEquals("Hermes token expired or rejected.", restored.errorMessage)
        assertEquals("Partial", restored.turns.last().content)
        assertFalse(restored.turns.last().streaming)
    }

    @Test
    fun invalidHermesSessionPayloadFallsBackToEmpty() {
        assertEquals(emptyList<HermesSession>(), decodeHermesSessions("not-json"))
    }
}
