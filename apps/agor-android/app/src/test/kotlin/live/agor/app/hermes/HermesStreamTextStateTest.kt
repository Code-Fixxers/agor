package live.agor.app.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesStreamTextStateTest {
    @Test
    fun streamsReasoningAsTemporaryNonSpokenTextThenReplacesItWithFinalOutput() {
        val state = HermesStreamTextState()

        val reasoning = state.onReasoningDelta("Thinking")
        val firstFinal = state.onTextDelta("Hello")
        val secondFinal = state.onTextDelta(" world")

        assertEquals(HermesStreamTextUpdate("Thinking", replaceExisting = false, emitTextEvent = false), reasoning)
        assertEquals(HermesStreamTextUpdate("Hello", replaceExisting = true, emitTextEvent = true), firstFinal)
        assertEquals(HermesStreamTextUpdate(" world", replaceExisting = false, emitTextEvent = true), secondFinal)
        assertEquals("Hello world", state.finalText)
        assertTrue(state.finalStarted)
    }

    @Test
    fun finalOutputAppendsNormallyWhenNoReasoningWasShown() {
        val state = HermesStreamTextState()

        val firstFinal = state.onTextDelta("Hello")

        assertEquals(HermesStreamTextUpdate("Hello", replaceExisting = false, emitTextEvent = true), firstFinal)
        assertEquals("Hello", state.finalText)
        assertTrue(state.finalStarted)
        assertFalse(state.reasoningShown)
    }
}
