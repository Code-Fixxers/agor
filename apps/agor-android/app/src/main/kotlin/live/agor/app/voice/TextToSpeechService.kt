package live.agor.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps Android's [TextToSpeech] with a queue + interruption rules that mirror
 * apps/agor-ios/AgorApp/Services/TextToSpeechService.swift:
 *
 * - speakIntermediate(): never interrupts; queued naturally.
 * - speakFinal(): clears queue + speaks immediately (the "agent's response" path).
 * - pause()/resume(): pauses TTS while user is recording, resumes after.
 *
 * The service reports a "speaking" state so voice mode can avoid recording while
 * the agent is talking.
 */
class TextToSpeechService(context: Context) {

    private val ready = AtomicBoolean(false)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    var onSpeechFinished: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ready.set(true)
        } else {
            AppLogger.log("TTS init failed: $status", LogLevel.WARNING, "TTS")
        }
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) {
                _speaking.value = false
                if (!_paused.value) onSpeechFinished?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _speaking.value = false }
            override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
        })
    }

    fun speakIntermediate(text: String) {
        if (!ready.get() || _paused.value) return
        val id = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun speakStatus(text: String) {
        if (!ready.get() || _paused.value) return
        val id = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun speakStreamChunk(text: String) = speakIntermediate(text)

    fun speakFinal(text: String) {
        if (!ready.get() || _paused.value) return
        val id = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun pause() {
        _paused.value = true
        tts.stop()
        _speaking.value = false
    }

    fun stop() {
        tts.stop()
        _speaking.value = false
    }

    fun resume() { _paused.value = false }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }
}
