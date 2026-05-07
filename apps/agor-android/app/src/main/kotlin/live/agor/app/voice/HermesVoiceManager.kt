package live.agor.app.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.auth.SecureTokenStore
import live.agor.app.data.HermesSessionEvent
import live.agor.app.data.HermesSessionStore
import live.agor.app.hermes.HermesForegroundService
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

enum class HermesVoicePhase {
    Idle,
    LoadingModels,
    Listening,
    Recording,
    Transcribing,
    Reviewing,
    Sending,
    Speaking,
    Error,
}

data class HermesVoiceState(
    val enabled: Boolean = false,
    val phase: HermesVoicePhase = HermesVoicePhase.Idle,
    val activeSessionId: String? = null,
    val pendingTranscript: String? = null,
    val audioLevel: Float = 0f,
    val threshold: Float = 0.7f,
    val errorMessage: String? = null,
    val needsWhisperDownload: Boolean = false,
    val modelDownloadInProgress: Boolean = false,
)

/**
 * App-scoped foreground Hermes voice loop.
 *
 * Owns microphone capture, local VAD, local/remote Whisper transcription,
 * review-delay auto-send, and Hermes reply TTS. It is process scoped so voice
 * mode survives screen recomposition and Hermes navigation while the app is in
 * the foreground.
 */
class HermesVoiceManager(
    private val context: Context,
    tokens: SecureTokenStore,
    private val sessions: HermesSessionStore,
    private val models: VoiceModelManager = VoiceModelManager(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioCapture(context)
    private val vad = VoiceActivityDetector(context, VadConfig())
    private val transcriber = TranscriptionService(context, tokens, models)
    private val tts = TextToSpeechService(context)

    private val _state = MutableStateFlow(HermesVoiceState(threshold = vad.config.threshold))
    val state: StateFlow<HermesVoiceState> = _state.asStateFlow()

    private var transcriptionJob: Job? = null
    private var reviewJob: Job? = null
    private var eventJob: Job? = null
    private var streamBuffer = ""
    private var streamedCurrentReply = false
    private var hermesRunning = false

    init {
        vad.onSpeechStart = {
            if (_state.value.phase == HermesVoicePhase.Listening) {
                tts.stop()
                audio.startBuffering(vad.config.preRollMillis)
                setPhase(HermesVoicePhase.Recording)
            }
        }
        vad.onSpeechEnd = {
            transcriptionJob?.cancel()
            transcriptionJob = scope.launch { transcribeCurrentBuffer() }
        }
        vad.onCalibrationComplete = {
            if (_state.value.enabled && !hermesRunning) setPhase(HermesVoicePhase.Listening)
        }
        audio.onFrame = { samples ->
            if (_state.value.phase != HermesVoicePhase.Speaking && !hermesRunning) {
                vad.process(samples)
            }
            val level = vad.currentAudioLevel.value
            _state.value = _state.value.copy(audioLevel = level, threshold = vad.energyThreshold.value)
        }
        tts.onSpeechFinished = {
            if (_state.value.enabled && !hermesRunning) {
                startCaptureIfNeeded()
            }
        }
        eventJob = scope.launch {
            sessions.events.collect { event -> handleHermesEvent(event) }
        }
    }

    fun hasMicPermission(): Boolean = audio.hasPermission()

    fun setActiveSession(sessionId: String?) {
        _state.value = _state.value.copy(activeSessionId = sessionId)
    }

    fun setHermesRunning(active: Boolean) {
        if (hermesRunning == active) return
        hermesRunning = active
        if (active) {
            pauseCapture()
            speakStatus("Working")
        } else if (_state.value.enabled && _state.value.phase != HermesVoicePhase.Speaking) {
            startCaptureIfNeeded()
        }
    }

    fun toggleAutoListening() {
        if (_state.value.enabled) stop() else start()
    }

    fun start() {
        if (_state.value.enabled) return
        if (!audio.hasPermission()) {
            _state.value = _state.value.copy(
                enabled = false,
                phase = HermesVoicePhase.Error,
                errorMessage = "Microphone permission is required",
            )
            return
        }
        _state.value = _state.value.copy(enabled = true, phase = HermesVoicePhase.LoadingModels, errorMessage = null)
        startCaptureIfNeeded()
    }

    fun stop() {
        transcriptionJob?.cancel()
        reviewJob?.cancel()
        transcriptionJob = null
        reviewJob = null
        pauseCapture()
        tts.stop()
        streamBuffer = ""
        streamedCurrentReply = false
        hermesRunning = false
        _state.value = _state.value.copy(
            enabled = false,
            phase = HermesVoicePhase.Idle,
            pendingTranscript = null,
            errorMessage = null,
        )
    }

    fun stopForBackground() {
        stop()
    }

    fun updatePendingTranscript(text: String) {
        if (_state.value.phase != HermesVoicePhase.Reviewing) return
        _state.value = _state.value.copy(pendingTranscript = text)
    }

    fun cancelPendingTranscript() {
        reviewJob?.cancel()
        reviewJob = null
        _state.value = _state.value.copy(pendingTranscript = null)
        if (_state.value.enabled && !hermesRunning) startCaptureIfNeeded()
    }

    fun sendPendingNow() {
        reviewJob?.cancel()
        reviewJob = null
        val text = _state.value.pendingTranscript.orEmpty()
        if (text.isNotBlank()) sendTranscript(text)
    }

    fun skipTts() {
        if (_state.value.phase != HermesVoicePhase.Speaking) return
        tts.stop()
        if (_state.value.enabled && !hermesRunning) startCaptureIfNeeded()
    }

    fun downloadWhisperModel() {
        if (_state.value.modelDownloadInProgress) return
        _state.value = _state.value.copy(
            phase = HermesVoicePhase.LoadingModels,
            needsWhisperDownload = false,
            modelDownloadInProgress = true,
            errorMessage = null,
        )
        scope.launch {
            runCatching { transcriber.downloadOnDeviceModel() }
                .onSuccess {
                    _state.value = _state.value.copy(modelDownloadInProgress = false)
                    if (_state.value.enabled && !hermesRunning) startCaptureIfNeeded()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        phase = HermesVoicePhase.Error,
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
        eventJob?.cancel()
        tts.shutdown()
        audio.close()
        scope.cancel()
    }

    private fun startCaptureIfNeeded() {
        if (!_state.value.enabled || hermesRunning) return
        if (_state.value.phase == HermesVoicePhase.Listening || _state.value.phase == HermesVoicePhase.Recording) return
        setPhase(HermesVoicePhase.LoadingModels)
        audio.start()
        vad.start()
        setPhase(HermesVoicePhase.Listening)
    }

    private fun pauseCapture() {
        vad.stop()
        audio.stop()
    }

    private suspend fun transcribeCurrentBuffer() {
        setPhase(HermesVoicePhase.Transcribing)
        val pcm = audio.stopBufferingAndDrain()
        pauseCapture()
        if (pcm.isEmpty()) {
            startCaptureIfNeeded()
            return
        }
        val result = transcriber.transcribe(pcm)
        val text = result.text.trim()
        if (text.isBlank()) {
            AppLogger.log("Hermes voice ignored blank transcription from ${result.source}", LogLevel.INFO, "Voice")
            if (result.source == "local-unavailable") {
                _state.value = _state.value.copy(
                    phase = HermesVoicePhase.Error,
                    needsWhisperDownload = true,
                    errorMessage = "Remote Whisper is unavailable and the local Whisper model has not been downloaded.",
                )
                return
            }
            startCaptureIfNeeded()
            return
        }
        _state.value = _state.value.copy(phase = HermesVoicePhase.Reviewing, pendingTranscript = text)
        reviewJob?.cancel()
        reviewJob = scope.launch {
            delay(REVIEW_DELAY_MS)
            val latest = _state.value.pendingTranscript.orEmpty()
            if (latest.isNotBlank()) sendTranscript(latest)
        }
    }

    private fun sendTranscript(text: String) {
        val sessionId = _state.value.activeSessionId
        if (sessionId.isNullOrBlank()) {
            _state.value = _state.value.copy(
                phase = HermesVoicePhase.Error,
                errorMessage = "Select a Hermes session before using voice",
                pendingTranscript = null,
            )
            return
        }
        _state.value = _state.value.copy(phase = HermesVoicePhase.Sending, pendingTranscript = null)
        HermesForegroundService.startPrompt(
            context = context,
            sessionId = sessionId,
            prompt = text,
            imageDataUrls = emptyList(),
            attachments = emptyList(),
        )
    }

    private fun handleHermesEvent(event: HermesSessionEvent) {
        val active = _state.value.activeSessionId ?: return
        if (event.sessionId != active || !_state.value.enabled) return
        when (event) {
            is HermesSessionEvent.Progress -> speakStatus(event.label)
            is HermesSessionEvent.TextDelta -> {
                streamBuffer += event.text
                speakBufferedSentences()
            }
            is HermesSessionEvent.Completed -> {
                val remaining = streamBuffer.trim()
                streamBuffer = ""
                if (remaining.isNotBlank()) {
                    streamedCurrentReply = true
                    speakStream(remaining)
                } else if (!streamedCurrentReply && event.text.isNotBlank()) {
                    speakFinal(event.text)
                }
                streamedCurrentReply = false
                hermesRunning = false
            }
            is HermesSessionEvent.Failed -> {
                streamBuffer = ""
                streamedCurrentReply = false
                hermesRunning = false
                speakStatus("Hermes failed")
            }
        }
    }

    private fun speakBufferedSentences() {
        val idx = streamBuffer.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        if (idx < 0) return
        val chunk = streamBuffer.substring(0, idx + 1).trim()
        streamBuffer = streamBuffer.substring(idx + 1)
        if (chunk.isBlank() || chunk.startsWith("```") || chunk.startsWith("    ")) return
        streamedCurrentReply = true
        speakStream(chunk)
    }

    private fun speakStatus(text: String) {
        if (!_state.value.enabled || text.isBlank()) return
        setPhase(HermesVoicePhase.Speaking)
        tts.resume()
        tts.speakStatus(text.take(160))
    }

    private fun speakStream(text: String) {
        if (!_state.value.enabled || text.isBlank()) return
        setPhase(HermesVoicePhase.Speaking)
        tts.resume()
        tts.speakStreamChunk(text.take(MAX_TTS_CHARS))
    }

    private fun speakFinal(text: String) {
        if (!_state.value.enabled || text.isBlank()) return
        setPhase(HermesVoicePhase.Speaking)
        tts.resume()
        tts.speakFinal(text.take(MAX_TTS_CHARS))
    }

    private fun setPhase(phase: HermesVoicePhase) {
        _state.value = _state.value.copy(phase = phase, errorMessage = null, needsWhisperDownload = false)
    }

    private companion object {
        const val REVIEW_DELAY_MS = 5_000L
        const val MAX_TTS_CHARS = 600
    }
}
