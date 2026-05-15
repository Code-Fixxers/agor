package live.agor.app.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
import live.agor.app.AgorApplication
import live.agor.app.AppContainer
import live.agor.app.R
import live.agor.app.models.SessionStatus
import live.agor.app.notifications.NotificationChannels
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import java.lang.ref.WeakReference

/**
 * Foreground service that owns regular Agor session voice mode.
 *
 * The UI talks to the companion controls, but the microphone, Whisper, TTS,
 * wake lock, and foreground notification stay here so voice can continue when
 * the user leaves the chat screen.
 */
class ContinuousVoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var container: AppContainer
    private lateinit var audio: AudioCapture
    private lateinit var tts: TextToSpeechService
    private lateinit var transcription: TranscriptionService
    private var tones: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var transcriptionJob: Job? = null
    private var reviewJob: Job? = null
    private var settingsJob: Job? = null
    private var promptable = true
    private var lastStatus: SessionStatus? = null
    private val spokenMessageIds = LinkedHashSet<String>()
    private val sharedState: StateFlow<SessionVoiceState>
        get() = state

    inner class LocalBinder : Binder() { val service: ContinuousVoiceService = this@ContinuousVoiceService }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        container = (application as AgorApplication).container
        audio = AudioCapture(this)
        tts = TextToSpeechService(this)
        transcription = TranscriptionService(this, container.tokenStore, container.voiceModels)
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55) }.getOrNull()

        audio.onFrame = { samples ->
            runCatching {
                updateState {
                    it.copy(
                        audioLevel = normalizedAudioLevel(samples),
                        threshold = 1f,
                    )
                }
            }.onFailure { error ->
                AppLogger.log("Session voice frame processing failed: ${error.message}", LogLevel.ERROR, "Voice")
                updateState {
                    it.copy(
                        phase = SessionVoicePhase.Error,
                        errorMessage = "Voice processing failed: ${error.message}",
                    )
                }
                pauseCapture()
            }
        }
        tts.onSpeechFinished = {
            endSession()
        }
        settingsJob = scope.launch {
            container.sessionVoiceSettings.settings.collect { applySettings(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId != null) {
            startForeground(NOTIF_ID, buildNotification(sessionId))
            beginSession(sessionId)
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun beginSession(sessionId: String) {
        if (sharedState.value.enabled && sharedState.value.activeSessionId == sessionId) return
        promptable = true
        lastStatus = null
        spokenMessageIds.clear()
        reviewJob?.cancel()
        transcriptionJob?.cancel()
        updateState {
            SessionVoiceState(
                enabled = true,
                activeSessionId = sessionId,
                phase = SessionVoicePhase.Preparing,
                threshold = 1f,
                settings = it.settings,
            )
        }
        acquireWakeLock()
        startCaptureIfNeeded()
        AppLogger.log("Session voice enabled for ${sessionId.take(8)}", LogLevel.INFO, "Voice")
    }

    private fun endSession() {
        val sessionId = sharedState.value.activeSessionId
        transcriptionJob?.cancel()
        reviewJob?.cancel()
        settingsJob?.cancel()
        transcriptionJob = null
        reviewJob = null
        pauseCapture()
        tts.shutdown()
        releaseWakeLock()
        updateState { SessionVoiceState(settings = it.settings) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.log("Session voice disabled for ${sessionId?.take(8) ?: "none"}", LogLevel.INFO, "Voice")
    }

    fun stopVoiceMode() = endSession()

    fun finishRecording() {
        if (sharedState.value.phase != SessionVoicePhase.Recording) {
            endSession()
            return
        }
        transcriptionJob?.cancel()
        transcriptionJob = scope.launch { transcribeCurrentBuffer() }
    }

    private fun startCaptureIfNeeded() {
        if (!sharedState.value.enabled || !promptable) {
            updateState { it.copy(phase = SessionVoicePhase.Paused) }
            return
        }
        if (sharedState.value.phase == SessionVoicePhase.Listening ||
            sharedState.value.phase == SessionVoicePhase.Recording
        ) {
            return
        }
        updateState { it.copy(phase = SessionVoicePhase.Preparing, errorMessage = null) }
        if (!audio.start()) {
            updateState {
                it.copy(
                    phase = SessionVoicePhase.Error,
                    errorMessage = "Microphone capture could not start. Check Android microphone permission/privacy indicators.",
                )
            }
            return
        }
        playReadyTone()
        playRecordingTone()
        audio.startBuffering(0)
        updateState { it.copy(phase = SessionVoicePhase.Recording) }
    }

    private fun pauseCapture() {
        audio.stop()
    }

    private suspend fun transcribeCurrentBuffer() {
        updateState { it.copy(phase = SessionVoicePhase.Transcribing) }
        val pcm = audio.stopBufferingAndDrain()
        pauseCapture()
        if (pcm.isEmpty()) {
            endSession()
            return
        }
        val result = transcription.transcribe(pcm)
        val text = result.text.trim()
        if (text.isBlank()) {
            if (result.source == "local-unavailable") {
                updateState {
                    it.copy(
                        phase = SessionVoicePhase.Error,
                        needsWhisperDownload = true,
                        errorMessage = "Remote Whisper is unavailable and the local Whisper model has not been downloaded.",
                    )
                }
            } else {
                endSession()
            }
            return
        }
        updateState {
            it.copy(
                phase = SessionVoicePhase.Reviewing,
                pendingTranscript = text,
                errorMessage = null,
                transcriptionEndpoint = result.endpoint ?: result.source,
            )
        }
        reviewJob?.cancel()
        reviewJob = scope.launch {
            delay(REVIEW_DELAY_MS)
            val latest = sharedState.value.pendingTranscript.orEmpty()
            if (latest.isNotBlank()) sendTranscript(latest)
        }
    }

    private fun sendTranscript(text: String) {
        val sessionId = sharedState.value.activeSessionId
        if (sessionId.isNullOrBlank()) {
            updateState {
                it.copy(
                    phase = SessionVoicePhase.Error,
                    pendingTranscript = null,
                    errorMessage = "Select a session before using voice mode",
                )
            }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            updateState { it.copy(pendingTranscript = null) }
            endSession()
            return
        }
        reviewJob?.cancel()
        reviewJob = null
        updateState { it.copy(phase = SessionVoicePhase.Sending, pendingTranscript = null) }
        scope.launch {
            runCatching { container.client.sendPrompt(sessionId, trimmed) }
                .onFailure { error ->
                    AppLogger.log("Session voice prompt failed: ${error.message}", LogLevel.ERROR, "Voice")
                    updateState {
                        it.copy(
                            phase = SessionVoicePhase.Error,
                            errorMessage = "Voice prompt failed: ${error.message}",
                        )
                    }
                    return@launch
                }
            promptable = false
            speakStatus("Working")
        }
    }

    private fun applyPromptability(sessionId: String, canPrompt: Boolean, status: SessionStatus?) {
        if (sharedState.value.activeSessionId != sessionId || !sharedState.value.enabled) return
        promptable = canPrompt
        if (status != null && status != lastStatus) {
            lastStatus = status
            SessionVoicePolicy.statusPhrase(status)?.let(::speakStatus)
        }
        val next = SessionVoicePolicy.phaseForPromptability(
            current = sharedState.value.phase,
            enabled = sharedState.value.enabled,
            promptable = canPrompt,
        )
        if (!canPrompt && next == SessionVoicePhase.Paused) {
            reviewJob?.cancel()
            reviewJob = null
            pauseCapture()
            tts.stop()
            updateState { it.copy(phase = SessionVoicePhase.Paused, pendingTranscript = null) }
        }
    }

    private fun speakStatus(text: String) {
        if (!sharedState.value.enabled || text.isBlank()) return
        pauseCapture()
        updateState { it.copy(phase = SessionVoicePhase.Speaking) }
        tts.resume()
        tts.speakStatus(text.take(160))
    }

    private fun speakAssistant(messageId: String, text: String) {
        if (!sharedState.value.enabled || text.isBlank()) return
        if (!spokenMessageIds.add(messageId)) return
        pauseCapture()
        updateState { it.copy(phase = SessionVoicePhase.Speaking) }
        tts.resume()
        tts.speakFinal(text.take(MAX_TTS_CHARS))
    }

    private fun skipTts() {
        if (sharedState.value.phase != SessionVoicePhase.Speaking) return
        tts.stop()
        endSession()
    }

    private fun updatePendingTranscript(text: String) {
        if (sharedState.value.phase != SessionVoicePhase.Reviewing) return
        updateState { it.copy(pendingTranscript = text) }
    }

    private fun cancelPendingTranscript() {
        reviewJob?.cancel()
        reviewJob = null
        updateState { it.copy(pendingTranscript = null) }
        endSession()
    }

    private fun applySettings(settings: SessionVoiceSettings) {
        updateState {
            it.copy(
                settings = settings,
                threshold = 1f,
            )
        }
    }

    private fun downloadWhisperModel() {
        if (sharedState.value.modelDownloadInProgress) return
        updateState {
            it.copy(
                phase = SessionVoicePhase.Preparing,
                needsWhisperDownload = false,
                modelDownloadInProgress = true,
                errorMessage = null,
            )
        }
        scope.launch {
            runCatching { transcription.downloadOnDeviceModel() }
                .onSuccess {
                    updateState { it.copy(modelDownloadInProgress = false) }
                }
                .onFailure { error ->
                    updateState {
                        it.copy(
                            phase = SessionVoicePhase.Error,
                            modelDownloadInProgress = false,
                            needsWhisperDownload = true,
                            errorMessage = "Whisper model download failed: ${error.message}",
                        )
                    }
                }
        }
    }

    private fun dismissWhisperDownloadPrompt() {
        updateState { it.copy(needsWhisperDownload = false) }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agor::session-voice").also {
            it.setReferenceCounted(false)
            it.acquire(2L * 60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun playReadyTone() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 80)
    }

    private fun playRecordingTone() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    private fun buildNotification(sessionId: String): android.app.Notification {
        return NotificationCompat.Builder(this, NotificationChannels.VOICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(getString(R.string.voice_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        if (activeService?.get() === this) activeService = null
        settingsJob?.cancel()
        transcriptionJob?.cancel()
        reviewJob?.cancel()
        pauseCapture()
        tones?.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4242
        const val EXTRA_SESSION_ID = "session_id"
        private const val REVIEW_DELAY_MS = 5_000L
        private const val MAX_TTS_CHARS = 700

        private val _sharedState = MutableStateFlow(SessionVoiceState())
        val state: StateFlow<SessionVoiceState> = _sharedState.asStateFlow()
        private var activeService: WeakReference<ContinuousVoiceService>? = null

        fun start(context: Context, sessionId: String) {
            val i = Intent(context, ContinuousVoiceService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            activeService?.get()?.stopVoiceMode()
                ?: run {
                    _sharedState.value = SessionVoiceState(settings = _sharedState.value.settings)
                    context.stopService(Intent(context, ContinuousVoiceService::class.java))
                }
        }

        fun finishOrStop(context: Context) {
            activeService?.get()?.finishRecording()
                ?: run {
                    _sharedState.value = SessionVoiceState(settings = _sharedState.value.settings)
                    context.stopService(Intent(context, ContinuousVoiceService::class.java))
                }
        }

        fun updatePromptability(sessionId: String, canPrompt: Boolean, status: SessionStatus?) {
            activeService?.get()?.applyPromptability(sessionId, canPrompt, status)
        }

        fun speakAssistant(messageId: String, text: String) {
            activeService?.get()?.speakAssistant(messageId, text)
        }

        fun skipTts() {
            activeService?.get()?.skipTts()
        }

        fun updatePendingTranscript(text: String) {
            activeService?.get()?.updatePendingTranscript(text)
        }

        fun sendPendingTranscript() {
            activeService?.get()?.let { service ->
                service.sendTranscript(_sharedState.value.pendingTranscript.orEmpty())
            }
        }

        fun cancelPendingTranscript() {
            activeService?.get()?.cancelPendingTranscript()
        }

        fun downloadWhisperModel() {
            activeService?.get()?.downloadWhisperModel()
        }

        fun dismissWhisperDownloadPrompt() {
            activeService?.get()?.dismissWhisperDownloadPrompt()
        }

        private fun updateState(transform: (SessionVoiceState) -> SessionVoiceState) {
            _sharedState.value = transform(_sharedState.value)
        }
    }
}
