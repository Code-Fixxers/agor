package live.agor.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.voice.jni.WhisperJni
import java.io.File

/**
 * Two-tier transcription:
 *   1. whisper.cpp via NDK (on-device, privacy-preserving) when a model is available.
 *   2. Android [SpeechRecognizer] fallback otherwise.
 *
 * The Whisper model lives at `<context.filesDir>/whisper/ggml-base.en.bin` by default;
 * download is the user's responsibility (settings UI offers a one-tap download).
 */
class TranscriptionService(private val context: Context) {

    private val whisper: WhisperJni? by lazy {
        if (WhisperJni.loadNative()) {
            val modelPath = defaultModelPath()
            if (modelPath.exists()) {
                val w = WhisperJni()
                if (w.init(modelPath.absolutePath)) w else null
            } else null
        } else null
    }

    fun defaultModelPath(): File =
        File(context.filesDir, "whisper/ggml-base.en.bin")

    fun isOnDeviceReady(): Boolean = whisper != null

    /** Transcribe a 16kHz mono PCM buffer. Falls back to platform recognizer if needed. */
    suspend fun transcribe(samples: ShortArray): String {
        val w = whisper
        if (w != null) {
            val floats = FloatArray(samples.size)
            for (i in samples.indices) floats[i] = samples[i] / 32768f
            return withContext(Dispatchers.Default) { w.transcribe(floats, AudioCapture.SAMPLE_RATE) }.trim()
        }
        return platformRecognize(samples)
    }

    private suspend fun platformRecognize(samples: ShortArray): String =
        withContext(Dispatchers.Main) {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            if (!available) {
                AppLogger.log(
                    "SpeechRecognizer unavailable on device — transcription unavailable",
                    LogLevel.WARNING, "Voice",
                )
                return@withContext ""
            }
            val deferred = CompletableDeferred<String>()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!deferred.isCompleted) deferred.complete("")
                }
                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!deferred.isCompleted) deferred.complete(texts?.firstOrNull().orEmpty())
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            // Note: SpeechRecognizer expects live audio, not a buffer. This fallback
            // is therefore only useful when called as a "start listening" entry point.
            // Real-world callers should prefer whisper.cpp; if that's unavailable the
            // continuous voice service routes audio capture through SpeechRecognizer
            // directly instead of via this method.
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            }
            recognizer.startListening(intent)
            deferred.await().also { recognizer.destroy() }
        }
}
