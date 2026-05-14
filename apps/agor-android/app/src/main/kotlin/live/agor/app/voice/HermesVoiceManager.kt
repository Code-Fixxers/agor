package live.agor.app.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import live.agor.app.util.LogEntry
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
    val lastDiagnostic: String? = null,
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
    private val tokens: SecureTokenStore,
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
    private var modelPreparationJob: Job? = null
    private var eventJob: Job? = null
    private var logJob: Job? = null
    private var streamBuffer = ""
    private var streamedCurrentReply = false
    private var hermesRunning = false
    private var liveKitStream: WhisperLiveKitStream? = null
    private val liveKitWebmRecorder = WhisperLiveKitWebmRecorder(context, scope)
    private var liveKitFinalTranscript: CompletableDeferred<String?>? = null
    private var lastLiveKitTranscript = ""

    init {
        vad.onSpeechStart = {
            if (_state.value.phase == HermesVoicePhase.Listening) {
                tts.stop()
                audio.startBuffering(vad.config.preRollMillis)
                setPhase(HermesVoicePhase.Recording)
                startLiveKitWebmIfNeeded()
            } else {
                AppLogger.log(
                    "Hermes voice ignored speech start while phase=${_state.value.phase}",
                    LogLevel.WARNING,
                    "Voice",
                )
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
            runCatching {
                if (_state.value.phase != HermesVoicePhase.Speaking && !hermesRunning) {
                    vad.process(samples)
                }
                val level = vad.currentAudioLevel.value
                _state.value = _state.value.copy(audioLevel = level, threshold = vad.energyThreshold.value)
            }.onFailure {
                AppLogger.log("Hermes voice frame processing failed: ${it.message}", LogLevel.ERROR, "Voice")
                stop()
                _state.value = _state.value.copy(
                    enabled = false,
                    phase = HermesVoicePhase.Error,
                    errorMessage = "Voice processing failed: ${it.message}",
                )
            }
        }
        audio.onPcmFrame = { samples ->
            val stream = liveKitStream
            if (stream?.useAudioWorklet == true) {
                runCatching { stream.sendPcm(samples) }
                    .onFailure {
                        AppLogger.log("Hermes voice streaming PCM failed: ${it.message}", LogLevel.WARNING, "Voice")
                    }
            }
        }
        tts.onSpeechFinished = {
            if (_state.value.enabled && !hermesRunning) {
                startCaptureIfNeeded()
            }
        }
        _state.value = _state.value.copy(lastDiagnostic = latestVoiceLog())
        logJob = scope.launch {
            AppLogger.stream.collect { entry ->
                if (entry.category == "Voice" || entry.category == "TTS") {
                    _state.value = _state.value.copy(lastDiagnostic = entry.toDiagnostic())
                }
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
            AppLogger.log("Hermes voice start rejected: missing microphone permission", LogLevel.WARNING, "Voice")
            _state.value = _state.value.copy(
                enabled = false,
                phase = HermesVoicePhase.Error,
                errorMessage = "Microphone permission is required",
            )
            return
        }
        AppLogger.log("Hermes voice start requested", LogLevel.INFO, "Voice")
        _state.value = _state.value.copy(
            enabled = true,
            phase = HermesVoicePhase.LoadingModels,
            errorMessage = null,
            modelDownloadInProgress = true,
        )
        prepareModelsAndStartCapture()
    }

    fun stop() {
        AppLogger.log("Hermes voice stop requested", LogLevel.INFO, "Voice")
        modelPreparationJob?.cancel()
        transcriptionJob?.cancel()
        reviewJob?.cancel()
        modelPreparationJob = null
        transcriptionJob = null
        reviewJob = null
        stopLiveKitStream()
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
            modelDownloadInProgress = false,
        )
    }

    fun stopForBackground() {
        if (!_state.value.enabled) return
        AppLogger.log("Hermes voice paused for background", LogLevel.INFO, "Voice")
        modelPreparationJob?.cancel()
        transcriptionJob?.cancel()
        reviewJob?.cancel()
        modelPreparationJob = null
        transcriptionJob = null
        reviewJob = null
        stopLiveKitStream()
        pauseCapture()
        tts.stop()
        streamBuffer = ""
        streamedCurrentReply = false
        hermesRunning = false
        _state.value = _state.value.copy(
            phase = HermesVoicePhase.Idle,
            modelDownloadInProgress = false,
            errorMessage = null,
        )
    }

    fun resumeForForeground() {
        if (!_state.value.enabled || hermesRunning || _state.value.pendingTranscript != null) return
        AppLogger.log("Hermes voice resumed in foreground", LogLevel.INFO, "Voice")
        startCaptureIfNeeded()
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
        logJob?.cancel()
        tts.shutdown()
        audio.close()
        scope.cancel()
    }

    private fun startCaptureIfNeeded() {
        if (!_state.value.enabled || hermesRunning) return
        if (_state.value.phase == HermesVoicePhase.Listening || _state.value.phase == HermesVoicePhase.Recording) return
        setPhase(HermesVoicePhase.LoadingModels)
        startLiveKitStream()
        if (!audio.start()) {
            stopLiveKitStream()
            vad.stop()
            _state.value = _state.value.copy(
                enabled = false,
                phase = HermesVoicePhase.Error,
                errorMessage = "Microphone capture could not start. Check Android microphone permission/privacy indicators.",
            )
            return
        }
        vad.start()
        setPhase(HermesVoicePhase.Listening)
    }

    private fun prepareModelsAndStartCapture() {
        modelPreparationJob?.cancel()
        modelPreparationJob = scope.launch {
            val vadReady = models.ensureVadModelDownloaded()
            if (!vadReady) {
                AppLogger.log(
                    "Hermes voice continuing with energy fallback VAD",
                    LogLevel.WARNING,
                    "Voice",
                )
            }
            _state.value = _state.value.copy(modelDownloadInProgress = false)
            if (!_state.value.enabled || hermesRunning) return@launch
            startCaptureIfNeeded()
        }
    }

    private fun pauseCapture() {
        liveKitWebmRecorder.stop()
        audio.stop()
        vad.stop()
    }

    private suspend fun transcribeCurrentBuffer() {
        setPhase(HermesVoicePhase.Transcribing)
        liveKitWebmRecorder.stop()
        val finalTranscript = liveKitFinalTranscript
        liveKitStream?.finish()
        val pcm = audio.stopBufferingAndDrain()
        val liveText = finalTranscript
            ?.let { withTimeoutOrNull(LIVEKIT_FINAL_TIMEOUT_MS) { it.await() } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        stopLiveKitStream()
        pauseCapture()
        if (liveText != null) {
            AppLogger.log("Hermes voice transcript accepted from whisperlivekit: ${liveText.take(120)}", LogLevel.INFO, "Voice")
            reviewTranscript(liveText)
            return
        }
        if (pcm.isEmpty()) {
            AppLogger.log("Hermes voice transcription skipped: empty audio buffer", LogLevel.WARNING, "Voice")
            startCaptureIfNeeded()
            return
        }
        AppLogger.log("Hermes voice transcribing ${pcm.size} PCM samples", LogLevel.INFO, "Voice")
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
        AppLogger.log("Hermes voice transcript accepted from ${result.source}: ${text.take(120)}", LogLevel.INFO, "Voice")
        reviewTranscript(text)
    }

    private fun reviewTranscript(text: String) {
        _state.value = _state.value.copy(phase = HermesVoicePhase.Reviewing, pendingTranscript = text)
        reviewJob?.cancel()
        reviewJob = scope.launch {
            delay(REVIEW_DELAY_MS)
            val latest = _state.value.pendingTranscript.orEmpty()
            if (latest.isNotBlank()) sendTranscript(latest)
        }
    }

    private fun startLiveKitStream() {
        val url = tokens.remoteWhisperUrl?.takeIf { it.isNotBlank() } ?: return
        stopLiveKitStream()
        lastLiveKitTranscript = ""
        val finalTranscript = CompletableDeferred<String?>()
        liveKitFinalTranscript = finalTranscript
        liveKitStream = WhisperLiveKitClient(url, tokens.remoteWhisperToken).open(
            onTranscript = { partial ->
                val cleaned = partial.trim()
                if (cleaned.isBlank() || cleaned == lastLiveKitTranscript) return@open
                lastLiveKitTranscript = cleaned
                if (_state.value.phase == HermesVoicePhase.Recording || _state.value.phase == HermesVoicePhase.Reviewing) {
                    _state.value = _state.value.copy(pendingTranscript = cleaned)
                }
            },
            onFinalTranscript = { final ->
                val cleaned = final.trim()
                if (cleaned.isNotBlank()) {
                    AppLogger.log("Hermes voice WhisperLiveKit final available: chars=${cleaned.length}", LogLevel.DEBUG, "Voice")
                    if (!finalTranscript.isCompleted) finalTranscript.complete(cleaned)
                    if (_state.value.phase == HermesVoicePhase.Recording || _state.value.phase == HermesVoicePhase.Reviewing) {
                        _state.value = _state.value.copy(pendingTranscript = cleaned)
                    }
                }
            },
            onFailure = {
                if (!finalTranscript.isCompleted) finalTranscript.complete(null)
                liveKitStream = null
                liveKitWebmRecorder.stop()
            },
        )
    }

    private fun startLiveKitWebmIfNeeded() {
        val stream = liveKitStream
        if (stream?.isConnected != true || stream.useAudioWorklet == true) return
        if (liveKitWebmRecorder.start(stream)) {
            AppLogger.log("Hermes voice WhisperLiveKit WebM stream active", LogLevel.DEBUG, "Voice")
        } else {
            AppLogger.log("Hermes voice WhisperLiveKit WebM stream unavailable; fallback transcriber remains active", LogLevel.WARNING, "Voice")
        }
    }

    private fun stopLiveKitStream() {
        liveKitWebmRecorder.stop()
        liveKitStream?.close()
        liveKitStream = null
        liveKitFinalTranscript?.takeUnless { it.isCompleted }?.complete(null)
        liveKitFinalTranscript = null
        lastLiveKitTranscript = ""
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
        const val LIVEKIT_FINAL_TIMEOUT_MS = 8_000L
        const val MAX_TTS_CHARS = 600
    }

    private fun latestVoiceLog(): String? = AppLogger.snapshot()
        .lastOrNull { it.category == "Voice" || it.category == "TTS" }
        ?.toDiagnostic()

    private fun LogEntry.toDiagnostic(): String = "[${level.name}] $category: $message"
}
