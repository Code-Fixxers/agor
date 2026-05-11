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
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Lightweight voice loop dedicated to the Hermes chat path.
 *
 * Mirrors [ContinuousVoiceService] in spirit — AudioCapture → VAD → record → Whisper
 * transcribe — but doesn't run as a foreground service. Hermes voice mode is meant
 * for active "phone in hand" use. Promote to a foreground service later if we want
 * lock-screen / background-talk parity with the Agor session voice mode.
 *
 * The controller is stateless w.r.t. who consumes the transcribed text or who feeds
 * back assistant replies — both are wired by the caller via [onTranscribed] and
 * [speakReply] / [pauseTts] / [resumeTts]. That keeps it ignorant of HermesViewModel
 * and easy to unit-test or reuse.
 */
class HermesVoiceController(
    context: Context,
) {

    enum class Phase { Idle, Calibrating, Listening, Recording, Transcribing, Speaking }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val audio = AudioCapture(context)
    private val vad = VoiceActivityDetector(context)
    private val transcription = TranscriptionService(context)
    private val tts = TextToSpeechService(context)

    var onTranscribed: ((String) -> Unit)? = null

    private var transcriptionJob: Job? = null

    init {
        vad.onSpeechStart = {
            audio.startBuffering()
            _phase.value = Phase.Recording
        }
        vad.onSpeechEnd = {
            transcriptionJob = scope.launch { onSpeechEnded() }
        }
        vad.onCalibrationComplete = { _phase.value = Phase.Listening }
        audio.onFrame = { samples ->
            // Don't feed VAD while we're speaking — prevents the agent's voice from
            // re-triggering recording.
            if (_phase.value != Phase.Speaking) vad.process(samples)
        }
        tts.onSpeechFinished = {
            // Resume listening only if voice mode is still active. If the user
            // toggled it off mid-utterance, stay Idle.
            if (_phase.value == Phase.Speaking) _phase.value = Phase.Listening
        }
    }

    fun hasMicPermission(): Boolean = audio.hasPermission()

    fun start() {
        if (_phase.value != Phase.Idle) return
        if (!audio.hasPermission()) {
            AppLogger.log("Hermes voice: missing RECORD_AUDIO", LogLevel.WARNING, "Voice")
            return
        }
        _phase.value = Phase.Calibrating
        audio.start()
        vad.start()
    }

    fun stop() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        vad.stop()
        audio.stop()
        tts.pause()
        _phase.value = Phase.Idle
    }

    /** Speak the assistant's final reply. Pauses VAD-driven listening for the duration. */
    fun speakReply(text: String) {
        if (_phase.value == Phase.Idle) return
        if (text.isBlank()) return
        _phase.value = Phase.Speaking
        tts.resume()
        tts.speakFinal(text)
    }

    fun pauseTts() = tts.pause()
    fun resumeTts() = tts.resume()

    fun release() {
        stop()
        tts.shutdown()
        audio.close()
        scope.cancel()
    }

    private suspend fun onSpeechEnded() {
        _phase.value = Phase.Transcribing
        val pcm = audio.stopBufferingAndDrain()
        if (pcm.isEmpty()) {
            _phase.value = Phase.Listening
            return
        }
        val text = transcription.transcribe(pcm).text.trim()
        if (text.isBlank()) {
            _phase.value = Phase.Listening
            return
        }
        // Hand the transcription to the caller; they'll call speakReply() once Hermes
        // produces a response. Keep listening in the meantime so a quick correction
        // ("wait, scratch that") can interrupt naturally.
        _phase.value = Phase.Listening
        onTranscribed?.invoke(text)
    }
}
