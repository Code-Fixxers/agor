package live.agor.app.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AgorApplication
import live.agor.app.AppContainer
import live.agor.app.R
import live.agor.app.notifications.NotificationChannels
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Foreground service that owns the continuous voice loop:
 *   AudioCapture → VAD → record → Whisper transcribe → daemon prompt
 *
 * Runs in the foreground with `microphone|mediaPlayback` so it survives the user
 * switching apps. Holds a partial wake lock while voice mode is active.
 *
 * The service is bound by the UI to expose the active session id and listening state;
 * stopping the service tears down the loop.
 */
class ContinuousVoiceService : Service() {

    enum class Phase { Idle, Calibrating, Listening, Recording, Transcribing, Sending, Speaking }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private lateinit var container: AppContainer
    private lateinit var audio: AudioCapture
    private lateinit var vad: VoiceActivityDetector
    private lateinit var tts: TextToSpeechService
    private lateinit var transcription: TranscriptionService
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    inner class LocalBinder : Binder() { val service: ContinuousVoiceService = this@ContinuousVoiceService }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        container = (application as AgorApplication).container
        audio = AudioCapture(this)
        vad = VoiceActivityDetector(this)
        tts = TextToSpeechService(this)
        transcription = TranscriptionService(this)

        vad.onSpeechStart = {
            audio.startBuffering()
            _phase.value = Phase.Recording
        }
        vad.onSpeechEnd = {
            scope.launch { onSpeechEnded() }
        }
        vad.onCalibrationComplete = { _phase.value = Phase.Listening }
        audio.onFrame = { samples -> if (_phase.value != Phase.Speaking) vad.process(samples) }
        tts.onSpeechFinished = { _phase.value = Phase.Listening }
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
        if (_activeSessionId.value == sessionId) return
        _activeSessionId.value = sessionId
        _phase.value = Phase.Calibrating
        acquireWakeLock()
        audio.start()
        vad.start()
    }

    private fun endSession() {
        _activeSessionId.value = null
        loopJob?.cancel()
        vad.stop()
        audio.stop()
        tts.shutdown()
        releaseWakeLock()
        _phase.value = Phase.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopVoiceMode() = endSession()

    private suspend fun onSpeechEnded() {
        _phase.value = Phase.Transcribing
        val pcm = audio.stopBufferingAndDrain()
        if (pcm.isEmpty()) {
            _phase.value = Phase.Listening
            return
        }
        val text = transcription.transcribe(pcm).text
        if (text.isBlank()) {
            _phase.value = Phase.Listening
            return
        }
        val sid = _activeSessionId.value ?: return
        _phase.value = Phase.Sending
        runCatching { container.client.sendPrompt(sid, text) }
            .onFailure { AppLogger.log("Voice prompt failed: ${it.message}", LogLevel.ERROR, "Voice") }
        _phase.value = Phase.Listening
    }

    /** Speak an intermediate working-phrase. Called by the chat VM when the agent thinks. */
    fun speakIntermediate(text: String) {
        _phase.value = Phase.Speaking
        tts.speakIntermediate(text)
    }

    /** Speak the final agent response. */
    fun speakFinal(text: String) {
        _phase.value = Phase.Speaking
        tts.speakFinal(text)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agor::voice").also {
            it.setReferenceCounted(false)
            it.acquire(2L * 60L * 60L * 1000L) // 2h cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4242
        const val EXTRA_SESSION_ID = "session_id"

        fun start(context: Context, sessionId: String) {
            val i = Intent(context, ContinuousVoiceService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ContinuousVoiceService::class.java))
        }
    }
}
