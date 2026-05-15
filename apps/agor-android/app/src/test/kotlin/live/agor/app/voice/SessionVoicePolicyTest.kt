package live.agor.app.voice

import live.agor.app.models.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVoicePolicyTest {
    @Test
    fun promptableOnlyWhenIdleOrExplicitlyReady() {
        assertTrue(SessionVoicePolicy.isPromptable(SessionStatus.IDLE, readyForPrompt = false))
        assertTrue(SessionVoicePolicy.isPromptable(SessionStatus.COMPLETED, readyForPrompt = true))
        assertFalse(SessionVoicePolicy.isPromptable(SessionStatus.RUNNING, readyForPrompt = true))
        assertFalse(SessionVoicePolicy.isPromptable(SessionStatus.RUNNING, readyForPrompt = false))
        assertFalse(SessionVoicePolicy.isPromptable(SessionStatus.AWAITING_PERMISSION, readyForPrompt = false))
        assertFalse(SessionVoicePolicy.isPromptable(SessionStatus.AWAITING_INPUT, readyForPrompt = false))
    }

    @Test
    fun listeningPausesWhileSessionCannotAcceptPrompt() {
        val next = SessionVoicePolicy.phaseForPromptability(
            current = SessionVoicePhase.Listening,
            enabled = true,
            promptable = false,
        )

        assertEquals(SessionVoicePhase.Paused, next)
    }

    @Test
    fun pausedVoiceResumesRecordingWhenPromptable() {
        val next = SessionVoicePolicy.phaseForPromptability(
            current = SessionVoicePhase.Paused,
            enabled = true,
            promptable = true,
        )

        assertEquals(SessionVoicePhase.Recording, next)
    }

    @Test
    fun statusSpeechMatchesBusyAttentionAndStoppedStates() {
        assertEquals("Working", SessionVoicePolicy.statusPhrase(SessionStatus.RUNNING))
        assertEquals("I need permission", SessionVoicePolicy.statusPhrase(SessionStatus.AWAITING_PERMISSION))
        assertEquals("I need input", SessionVoicePolicy.statusPhrase(SessionStatus.AWAITING_INPUT))
        assertEquals("Stopped", SessionVoicePolicy.statusPhrase(SessionStatus.FAILED))
    }
}
