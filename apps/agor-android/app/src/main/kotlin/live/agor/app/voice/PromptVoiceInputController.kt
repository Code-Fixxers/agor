package live.agor.app.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.auth.SecureTokenStore
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

enum class PromptVoicePhase {
    Idle,
    LoadingModels,
    Listening,
    Recording,
    Transcribing,
    Error,
}

data class PromptVoiceInputState(
    val phase: PromptVoicePhase = PromptVoicePhase.Idle,
    val audioLevel: Float = 0f,
    val threshold: Float = 0.6f,
    val errorMessage: String? = null,
    val needsWhisperDownload: Boolean = false,
    val modelDownloadInProgress: Boolean = false,
)

/**
 * One-shot voice dictation for normal Agor session prompts.
 *
 * This intentionally inserts transcribed text into the draft instead of sending it.
 * Regular sessions often need review/editing before prompt submission, while Hermes
 * auto-listening has its own conversational send loop.
 */
class PromptVoiceInputController(
    private val context: Context,
    tokens: SecureTokenStore,
    private val models: VoiceModelManager = VoiceModelManager(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioCapture(context)
    private val vad = VoiceActivityDetector(context, VadConfig())
    private val transcriber = TranscriptionService(context, tokens, models)

    private val _state = MutableStateFlow(PromptVoiceInputState(threshold = vad.config.threshold))
    val state: StateFlow<PromptVoiceInputState> = _state.asStateFlow()

    var onTranscribed: ((String) -> Unit)? = null

    private var modelPreparationJob: Job? = null
    private var transcriptionJob: Job? = null

    init {
        vad.onSpeechStart = {
            if (_state.value.phase == PromptVoicePhase.Listening) {
                audio.startBuffering(vad.config.preRollMillis)
                setPhase(PromptVoicePhase.Recording)
            } else {
                AppLogger.log(
                    "Prompt voice ignored speech start while phase=${_state.value.phase}",
                    LogLevel.WARNING,
                    "Voice",
                )
            }
        }
        vad.onSpeechEnd = {
            transcriptionJob?.cancel()
            transcriptionJob = scope.launch { transcribeCurrentBuffer() }
        }
        audio.onFrame = { samples ->
            vad.process(samples)
            _state.value = _state.value.copy(
                audioLevel = vad.currentAudioLevel.value,
                threshold = vad.energyThreshold.value,
            )
        }
    }

    fun hasMicPermission(): Boolean = audio.hasPermission()

    fun start() {
        if (_state.value.phase != PromptVoicePhase.Idle && _state.value.phase != PromptVoicePhase.Error) return
        if (!audio.hasPermission()) {
            AppLogger.log("Prompt voice start rejected: missing microphone permission", LogLevel.WARNING, "Voice")
            _state.value = _state.value.copy(
                phase = PromptVoicePhase.Error,
                errorMessage = "Microphone permission is required",
            )
            return
        }
        AppLogger.log("Prompt voice dictation requested", LogLevel.INFO, "Voice")
        _state.value = _state.value.copy(
            phase = PromptVoicePhase.LoadingModels,
            errorMessage = null,
            needsWhisperDownload = false,
            modelDownloadInProgress = true,
        )
        prepareVadAndStartCapture()
    }

    fun stop() {
        modelPreparationJob?.cancel()
        modelPreparationJob = null
        if (_state.value.phase == PromptVoicePhase.Recording) {
            if (transcriptionJob?.isActive == true) return
            transcriptionJob = scope.launch { transcribeCurrentBuffer() }
            AppLogger.log("Prompt voice recording stopped; transcribing buffered audio", LogLevel.INFO, "Voice")
            return
        }
        transcriptionJob?.cancel()
        transcriptionJob = null
        stopCapture()
        resetState()
        AppLogger.log("Prompt voice dictation cancelled", LogLevel.INFO, "Voice")
    }

    fun downloadWhisperModel() {
        if (_state.value.modelDownloadInProgress) return
        _state.value = _state.value.copy(
            phase = PromptVoicePhase.LoadingModels,
            needsWhisperDownload = false,
            modelDownloadInProgress = true,
            errorMessage = null,
        )
        scope.launch {
            runCatching { transcriber.downloadOnDeviceModel() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        phase = PromptVoicePhase.Idle,
                        modelDownloadInProgress = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        phase = PromptVoicePhase.Error,
                        modelDownloadInProgress = false,
                        needsWhisperDownload = true,
                        errorMessage = "Whisper model download failed: ${it.message}",
                    )
                }
        }
    }

    fun dismissWhisperDownloadPrompt() {
        _state.value = _state.value.copy(needsWhisperDownload = false)
    }

    fun release() {
        stop()
        audio.close()
        scope.cancel()
    }

    private fun prepareVadAndStartCapture() {
        modelPreparationJob?.cancel()
        modelPreparationJob = scope.launch {
            val vadReady = models.ensureVadModelDownloaded()
            if (!vadReady) {
                AppLogger.log(
                    "Prompt voice continuing with energy fallback VAD",
                    LogLevel.WARNING,
                    "Voice",
                )
            }
            _state.value = _state.value.copy(modelDownloadInProgress = false)
            startCapture()
        }
    }

    private fun startCapture() {
        if (!audio.start()) {
            vad.stop()
            _state.value = _state.value.copy(
                phase = PromptVoicePhase.Error,
                errorMessage = "Microphone capture could not start. Check Android microphone permission/privacy indicators.",
            )
            return
        }
        vad.start()
        setPhase(PromptVoicePhase.Listening)
    }

    private suspend fun transcribeCurrentBuffer() {
        setPhase(PromptVoicePhase.Transcribing)
        val pcm = audio.stopBufferingAndDrain()
        stopCapture()
        if (pcm.isEmpty()) {
            AppLogger.log("Prompt voice transcription skipped: empty audio buffer", LogLevel.WARNING, "Voice")
            resetState()
            return
        }
        AppLogger.log("Prompt voice transcribing ${pcm.size} PCM samples", LogLevel.INFO, "Voice")
        val result = transcriber.transcribe(pcm)
        val text = result.text.trim()
        if (text.isBlank()) {
            AppLogger.log("Prompt voice ignored blank transcription from ${result.source}", LogLevel.INFO, "Voice")
            if (result.source == "local-unavailable") {
                _state.value = _state.value.copy(
                    phase = PromptVoicePhase.Error,
                    needsWhisperDownload = true,
                    errorMessage = "Remote Whisper is unavailable and the local Whisper model has not been downloaded.",
                )
            } else {
                resetState()
            }
            return
        }
        AppLogger.log("Prompt voice transcript accepted from ${result.source}: ${text.take(120)}", LogLevel.INFO, "Voice")
        onTranscribed?.invoke(text)
        resetState()
    }

    private fun stopCapture() {
        audio.stop()
        vad.stop()
    }

    private fun resetState() {
        _state.value = PromptVoiceInputState(threshold = vad.config.threshold)
    }

    private fun setPhase(phase: PromptVoicePhase) {
        _state.value = _state.value.copy(phase = phase, errorMessage = null, needsWhisperDownload = false)
    }
}
