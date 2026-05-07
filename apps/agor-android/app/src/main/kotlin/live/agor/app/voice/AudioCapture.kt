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
    private val preRollBuffer = ArrayList<Short>(SAMPLE_RATE * 2)
    private var preRollCapacity = SAMPLE_RATE * 2
    private var recordingActive = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        if (!hasPermission()) {
            AppLogger.log("AudioCapture: missing RECORD_AUDIO permission", LogLevel.WARNING, "Voice")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            AppLogger.log("AudioRecord min buffer invalid: $minBuf", LogLevel.ERROR, "Voice")
            return false
        }
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
            record.release()
            return false
        }
        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrElse {
            AppLogger.log("AudioRecord start failed: ${it.message}", LogLevel.ERROR, "Voice")
            false
        }
        if (!started) {
            AppLogger.log("AudioRecord did not enter recording state", LogLevel.ERROR, "Voice")
            record.release()
            return false
        }
        running.set(true)
        AppLogger.log("AudioCapture started: minBuf=$minBuf bufferSize=$bufferSize frame=$FRAME_SAMPLES", LogLevel.INFO, "Voice")

        captureJob = scope.launch {
            val shortBuf = ShortArray(FRAME_SAMPLES)
            val floatBuf = FloatArray(FRAME_SAMPLES)
            var lastReadError = 0
            try {
                while (isActive && running.get()) {
                    val read = record.read(shortBuf, 0, FRAME_SAMPLES)
                    if (read <= 0) {
                        if (read != lastReadError) {
                            lastReadError = read
                            AppLogger.log("AudioRecord read returned $read", LogLevel.WARNING, "Voice")
                        }
                        continue
                    }
                    lastReadError = 0
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
                    } else {
                        synchronized(preRollBuffer) {
                            for (i in 0 until read) preRollBuffer.add(shortBuf[i])
                            val overflow = preRollBuffer.size - preRollCapacity
                            if (overflow > 0) preRollBuffer.subList(0, overflow).clear()
                        }
                    }
                    onFrame?.invoke(floatBuf)
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                AppLogger.log("AudioCapture stopped", LogLevel.INFO, "Voice")
            }
        }
        return true
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel()
        captureJob = null
    }

    fun startBuffering(preRollMillis: Long = 0) {
        preRollCapacity = ((SAMPLE_RATE * preRollMillis) / 1_000L).toInt().coerceIn(0, SAMPLE_RATE * 5)
        synchronized(captureBuffer) {
            captureBuffer.clear()
            if (preRollCapacity > 0) {
                synchronized(preRollBuffer) {
                    val start = (preRollBuffer.size - preRollCapacity).coerceAtLeast(0)
                    for (i in start until preRollBuffer.size) captureBuffer.add(preRollBuffer[i])
                }
            }
        }
        recordingActive = true
        AppLogger.log("AudioCapture buffering started: preRoll=${preRollCapacity} samples", LogLevel.DEBUG, "Voice")
    }

    /** Returns the accumulated speech segment as 16-bit PCM at 16kHz, then clears. */
    fun stopBufferingAndDrain(): ShortArray {
        recordingActive = false
        return synchronized(captureBuffer) {
            val arr = ShortArray(captureBuffer.size)
            for ((i, v) in captureBuffer.withIndex()) arr[i] = v
            captureBuffer.clear()
            AppLogger.log("AudioCapture drained ${arr.size} samples", LogLevel.INFO, "Voice")
            arr
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
    }
}
