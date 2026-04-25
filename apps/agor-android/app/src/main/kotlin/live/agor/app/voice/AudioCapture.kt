package live.agor.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord wrapper that delivers PCM frames to a VAD and (optionally) records the
 * confirmed-speech window into a contiguous Short buffer for transcription.
 *
 * Sample rate is 16kHz mono — required by Whisper and matches AudioRecord's lowest
 * documented support floor across Android devices.
 */
class AudioCapture(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var captureJob: Job? = null

    var onFrame: ((FloatArray) -> Unit)? = null

    private val captureBuffer = ArrayList<Short>(SAMPLE_RATE * 30) // ≤30s buffer
    private var recordingActive = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.get()) return
        if (!hasPermission()) {
            AppLogger.log("AudioCapture: missing RECORD_AUDIO permission", LogLevel.WARNING, "Voice")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = (minBuf * 4).coerceAtLeast(SAMPLE_RATE)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.log("AudioRecord init failed", LogLevel.ERROR, "Voice")
            return
        }
        running.set(true)
        record.startRecording()

        captureJob = scope.launch {
            val shortBuf = ShortArray(FRAME_SAMPLES)
            val floatBuf = FloatArray(FRAME_SAMPLES)
            try {
                while (isActive && running.get()) {
                    val read = record.read(shortBuf, 0, FRAME_SAMPLES)
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        floatBuf[i] = shortBuf[i] / 32768f
                    }
                    if (read < FRAME_SAMPLES) {
                        for (i in read until FRAME_SAMPLES) floatBuf[i] = 0f
                    }
                    if (recordingActive) {
                        synchronized(captureBuffer) {
                            for (i in 0 until read) captureBuffer.add(shortBuf[i])
                        }
                    }
                    onFrame?.invoke(floatBuf)
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel()
        captureJob = null
    }

    fun startBuffering() {
        synchronized(captureBuffer) { captureBuffer.clear() }
        recordingActive = true
    }

    /** Returns the accumulated speech segment as 16-bit PCM at 16kHz, then clears. */
    fun stopBufferingAndDrain(): ShortArray {
        recordingActive = false
        return synchronized(captureBuffer) {
            val arr = ShortArray(captureBuffer.size)
            for ((i, v) in captureBuffer.withIndex()) arr[i] = v
            captureBuffer.clear()
            arr
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1024
    }
}
