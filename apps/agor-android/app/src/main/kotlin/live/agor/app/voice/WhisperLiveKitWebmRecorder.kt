package live.agor.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Streams microphone audio in the same container format used by the
 * WhisperLiveKit browser client when `useAudioWorklet=false`.
 */
class WhisperLiveKitWebmRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var recorder: MediaRecorder? = null
    private var readFd: ParcelFileDescriptor? = null
    private var writeFd: ParcelFileDescriptor? = null
    private var readerJob: Job? = null

    val isRecording: Boolean
        get() = recorder != null

    @SuppressLint("MissingPermission")
    fun start(stream: WhisperLiveKitStream): Boolean {
        if (isRecording) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.log("WhisperLiveKit WebM streaming requires Android 10+", LogLevel.WARNING, "Voice")
            return false
        }
        if (stream.useAudioWorklet == true) return false

        return runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            writeFd = pipe[1]

            val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            activeRecorder.setAudioChannels(1)
            activeRecorder.setAudioSamplingRate(WEBM_SAMPLE_RATE)
            activeRecorder.setAudioEncodingBitRate(WEBM_BIT_RATE)
            activeRecorder.setOutputFile(writeFd?.fileDescriptor)
            activeRecorder.prepare()

            readerJob = scope.launch(Dispatchers.IO) {
                val input = ParcelFileDescriptor.AutoCloseInputStream(readFd)
                val buffer = ByteArray(4_096)
                try {
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) stream.sendWebmChunk(buffer, read)
                    }
                } catch (error: IOException) {
                    if (isActive) {
                        AppLogger.log("WhisperLiveKit WebM pipe read failed: ${error.message}", LogLevel.WARNING, "Voice")
                    }
                }
            }

            activeRecorder.start()
            recorder = activeRecorder
            AppLogger.log("WhisperLiveKit WebM recorder started", LogLevel.INFO, "Voice")
            true
        }.getOrElse { error ->
            AppLogger.log("WhisperLiveKit WebM recorder failed: ${error.message}", LogLevel.WARNING, "Voice")
            stop()
            false
        }
    }

    fun stop() {
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
                .onFailure {
                    AppLogger.log("WhisperLiveKit WebM recorder stop failed: ${it.message}", LogLevel.DEBUG, "Voice")
                }
            runCatching { activeRecorder.release() }
        }
        runCatching { writeFd?.close() }
        writeFd = null
        readerJob?.cancel()
        readerJob = null
        runCatching { readFd?.close() }
        readFd = null
    }

    private companion object {
        const val WEBM_SAMPLE_RATE = 48_000
        const val WEBM_BIT_RATE = 48_000
    }
}
