package live.agor.app.voice

import live.agor.app.models.SessionStatus

enum class SessionVoicePhase {
    Disabled,
    Preparing,
    Listening,
    Paused,
    Recording,
    Transcribing,
    Reviewing,
    Sending,
    Speaking,
    Error,
}

data class SessionVoiceState(
    val enabled: Boolean = false,
    val activeSessionId: String? = null,
    val phase: SessionVoicePhase = SessionVoicePhase.Disabled,
    val pendingTranscript: String? = null,
    val audioLevel: Float = 0f,
    val threshold: Float = VadConfig().threshold,
    val settings: SessionVoiceSettings = SessionVoiceSettings.Default,
    val errorMessage: String? = null,
    val needsWhisperDownload: Boolean = false,
    val modelDownloadInProgress: Boolean = false,
)

object SessionVoicePolicy {
    fun isPromptable(status: SessionStatus, readyForPrompt: Boolean?): Boolean =
        status == SessionStatus.IDLE ||
            (readyForPrompt == true && status != SessionStatus.RUNNING && status != SessionStatus.STOPPING && !status.needsAttention)

    fun phaseForPromptability(
        current: SessionVoicePhase,
        enabled: Boolean,
        promptable: Boolean,
    ): SessionVoicePhase {
        if (!enabled) return SessionVoicePhase.Disabled
        return when {
            !promptable && current in promptableCapturePhases -> SessionVoicePhase.Paused
            promptable && current == SessionVoicePhase.Paused -> SessionVoicePhase.Listening
            else -> current
        }
    }

    fun statusPhrase(status: SessionStatus): String? = when (status) {
        SessionStatus.RUNNING -> "Working"
        SessionStatus.AWAITING_PERMISSION -> "I need permission"
        SessionStatus.AWAITING_INPUT -> "I need input"
        SessionStatus.STOPPING,
        SessionStatus.TIMED_OUT,
        SessionStatus.COMPLETED,
        SessionStatus.FAILED -> "Stopped"
        SessionStatus.IDLE -> null
    }

    private val promptableCapturePhases = setOf(
        SessionVoicePhase.Preparing,
        SessionVoicePhase.Listening,
        SessionVoicePhase.Recording,
        SessionVoicePhase.Transcribing,
        SessionVoicePhase.Reviewing,
    )
}
