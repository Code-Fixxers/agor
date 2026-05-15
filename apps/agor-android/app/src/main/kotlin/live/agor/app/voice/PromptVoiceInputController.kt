package live.agor.app.voice

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val liveTranscript: String? = null,
    val transcriptionEndpoint: String? = null,
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
    private val tokens: SecureTokenStore,
    private val models: VoiceModelManager = VoiceModelManager(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioCapture(context)
    private val transcriber = TranscriptionService(context, tokens, models)

    private val _state = MutableStateFlow(PromptVoiceInputState(threshold = 1f))
    val state: StateFlow<PromptVoiceInputState> = _state.asStateFlow()

    var onTranscribed: ((String) -> Unit)? = null
    var onPartialTranscribed: ((String) -> Unit)? = null

    private var modelPreparationJob: Job? = null
    private var transcriptionJob: Job? = null
    private var liveTranscriptionJob: Job? = null
    private var liveKitStream: WhisperLiveKitStream? = null
    private var liveKitFinalTranscript: CompletableDeferred<String?>? = null
    private val liveKitWebmRecorder = WhisperLiveKitWebmRecorder(context, scope)
    private var lastPartialTranscript = ""

    init {
        audio.onFrame = { samples ->
            runCatching {
                _state.value = _state.value.copy(
                    audioLevel = normalizedAudioLevel(samples),
                    threshold = 1f,
                )
            }.onFailure {
                AppLogger.log("Prompt voice frame processing failed: ${it.message}", LogLevel.ERROR, "Voice")
                stop()
                _state.value = _state.value.copy(
                    phase = PromptVoicePhase.Error,
                    errorMessage = "Voice processing failed: ${it.message}",
                )
            }
        }
        audio.onPcmFrame = { samples ->
            runCatching { liveKitStream?.sendPcm(samples) }
                .onFailure {
                    AppLogger.log("Prompt voice streaming PCM failed: ${it.message}", LogLevel.WARNING, "Voice")
                }
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
            modelDownloadInProgress = false,
        )
        startCapture()
    }

    fun stop() {
        modelPreparationJob?.cancel()
        modelPreparationJob = null
        if (_state.value.phase == PromptVoicePhase.Recording) {
            liveTranscriptionJob?.cancel()
            liveTranscriptionJob = null
            if (transcriptionJob?.isActive == true) return
            transcriptionJob = scope.launch { transcribeCurrentBuffer() }
            AppLogger.log("Prompt voice recording stopped; transcribing buffered audio", LogLevel.INFO, "Voice")
            return
        }
        transcriptionJob?.cancel()
        liveTranscriptionJob?.cancel()
        transcriptionJob = null
        liveTranscriptionJob = null
        stopLiveKitStream()
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

    private fun startCapture() {
        startLiveKitStream()
        if (!audio.start()) {
            stopLiveKitStream()
            _state.value = _state.value.copy(
                phase = PromptVoicePhase.Error,
                errorMessage = "Microphone capture could not start. Check Android microphone permission/privacy indicators.",
            )
            return
        }
        audio.startBuffering(0)
        setPhase(PromptVoicePhase.Recording)
        startLiveTranscription()
    }

    private suspend fun transcribeCurrentBuffer() {
        setPhase(PromptVoicePhase.Transcribing)
        liveTranscriptionJob?.cancel()
        liveTranscriptionJob = null
        liveKitWebmRecorder.stop()
        val liveFinal = finishLiveKitStreamAndAwaitFinal()
        val pcm = audio.stopBufferingAndDrain()
        stopCapture()
        if (!liveFinal.isNullOrBlank()) {
            AppLogger.log("Prompt voice transcript accepted from remote-stream: ${liveFinal.take(120)}", LogLevel.INFO, "Voice")
            _state.value = _state.value.copy(
                transcriptionEndpoint = "Realtime ${whisperLiveKitAsrUrl(tokens.remoteWhisperUrl ?: DEFAULT_REMOTE_WHISPER_URL)}",
            )
            onTranscribed?.invoke(liveFinal)
            resetState()
            return
        }
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
        _state.value = _state.value.copy(transcriptionEndpoint = result.endpoint ?: result.source)
        onTranscribed?.invoke(text)
        resetState()
    }

    private fun stopCapture() {
        audio.stop()
    }

    private fun startLiveTranscription() {
        val stream = liveKitStream
        if (stream?.isConnected == true) {
            if (stream.useAudioWorklet == true) {
                AppLogger.log("Prompt voice rolling REST partials skipped; WhisperLiveKit PCM stream is active", LogLevel.DEBUG, "Voice")
                return
            }
            if (liveKitWebmRecorder.start(stream)) {
                AppLogger.log("Prompt voice rolling REST partials skipped; WhisperLiveKit WebM stream is active", LogLevel.DEBUG, "Voice")
                return
            }
            AppLogger.log("Prompt voice WebM live stream unavailable; using rolling REST partials", LogLevel.WARNING, "Voice")
        }
        if (liveTranscriptionJob?.isActive == true) {
            return
        }
        liveTranscriptionJob?.cancel()
        lastPartialTranscript = ""
        liveTranscriptionJob = scope.launch {
            delay(LIVE_TRANSCRIPTION_INITIAL_DELAY_MS)
            while (isActive && _state.value.phase == PromptVoicePhase.Recording) {
                val pcm = audio.snapshotBufferedAudio()
                if (pcm.size >= LIVE_TRANSCRIPTION_MIN_SAMPLES) {
                    transcriber.transcribeRemoteOnly(pcm)?.text
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && it != lastPartialTranscript }
                        ?.let { partial ->
                            lastPartialTranscript = partial
                            _state.value = _state.value.copy(liveTranscript = partial)
                            onPartialTranscribed?.invoke(partial)
                        }
                }
                delay(LIVE_TRANSCRIPTION_INTERVAL_MS)
            }
        }
    }

    private fun startLiveKitStream() {
        val url = tokens.remoteWhisperUrl?.takeIf { it.isNotBlank() } ?: return
        stopLiveKitStream()
        val finalTranscript = CompletableDeferred<String?>()
        liveKitFinalTranscript = finalTranscript
        _state.value = _state.value.copy(transcriptionEndpoint = "Realtime ${whisperLiveKitAsrUrl(url)}")
        liveKitStream = WhisperLiveKitClient(url, tokens.remoteWhisperToken).open(
            onTranscript = { partial ->
                val cleaned = partial.trim()
                if (cleaned.isBlank() || cleaned == lastPartialTranscript) return@open
                lastPartialTranscript = cleaned
                _state.value = _state.value.copy(liveTranscript = cleaned)
                onPartialTranscribed?.invoke(cleaned)
            },
            onFinalTranscript = { final ->
                AppLogger.log("Prompt voice WhisperLiveKit final available: chars=${final.length}", LogLevel.DEBUG, "Voice")
                finalTranscript.complete(final.trim().takeIf { it.isNotBlank() })
            },
            onConfig = {
                if (_state.value.phase == PromptVoicePhase.Recording) {
                    startLiveTranscription()
                }
            },
            onFailure = {
                finalTranscript.complete(null)
                liveKitWebmRecorder.stop()
                liveKitStream = null
                _state.value = _state.value.copy(
                    transcriptionEndpoint = "Fallback ${url.trim().trimEnd('/')}/v1/audio/transcriptions",
                )
                if (liveKitFinalTranscript === finalTranscript) {
                    liveKitFinalTranscript = null
                }
            },
        )
    }

    private suspend fun finishLiveKitStreamAndAwaitFinal(): String? {
        val stream = liveKitStream ?: return null
        val finalTranscript = liveKitFinalTranscript
        stream.finish()
        val text = if (finalTranscript != null) {
            withTimeoutOrNull(LIVE_KIT_FINAL_TIMEOUT_MS) {
                finalTranscript.await()
            }?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        stopLiveKitStream()
        return text
    }

    private fun stopLiveKitStream() {
        liveKitWebmRecorder.stop()
        liveKitFinalTranscript?.complete(null)
        liveKitFinalTranscript = null
        liveKitStream?.close()
        liveKitStream = null
    }

    private fun resetState() {
        lastPartialTranscript = ""
        _state.value = PromptVoiceInputState(threshold = 1f)
    }

    private fun setPhase(phase: PromptVoicePhase) {
        _state.value = _state.value.copy(phase = phase, errorMessage = null, needsWhisperDownload = false)
    }

    private companion object {
        const val LIVE_TRANSCRIPTION_INITIAL_DELAY_MS = 1_200L
        const val LIVE_TRANSCRIPTION_INTERVAL_MS = 1_200L
        const val LIVE_TRANSCRIPTION_MIN_SAMPLES = AudioCapture.SAMPLE_RATE
        const val LIVE_KIT_FINAL_TIMEOUT_MS = 8_000L
    }
}

internal fun normalizedAudioLevel(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (sample in samples) {
        sum += sample * sample
    }
    return kotlin.math.sqrt(sum / samples.size).toFloat().coerceIn(0f, 1f)
}
