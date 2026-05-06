package live.agor.app.voice

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import kotlin.math.sqrt

/**
 * Local Silero VAD backed by ONNX Runtime.
 *
 * Audio capture feeds 16kHz mono PCM frames. The detector chunks them into
 * Silero windows and emits speech start/end callbacks with silence debounce.
 * If ONNX initialization fails, it falls back to a conservative energy gate so
 * voice mode stays usable while surfacing the error in logs.
 */
class VoiceActivityDetector(
    private val context: Context? = null,
    var config: VadConfig = VadConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    enum class State { Idle, Listening, SpeechDetected }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val currentAudioLevel: StateFlow<Float> = _level.asStateFlow()

    private val _threshold = MutableStateFlow(config.threshold)
    val energyThreshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _sensitivity = MutableStateFlow(VadConfig.sensitivityFor(config.threshold))
    val sensitivityLevel: StateFlow<Float> = _sensitivity.asStateFlow()

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onCalibrationComplete: (() -> Unit)? = null

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var sileroState = FloatArray(2 * 1 * 128)
    private var chunkBuffer = ArrayList<Float>(SILERO_CHUNK_SAMPLES * 2)
    private var silenceJob: Job? = null
    private var hardStopJob: Job? = null
    private var speechStartTimeMs = 0L
    private var energyFloor = 0.001f
    private var smoothedEnergy = 0f

    fun setSensitivity(sensitivity: Float) {
        _sensitivity.value = sensitivity.coerceIn(0f, 1f)
        config.threshold = VadConfig.thresholdFor(_sensitivity.value)
        _threshold.value = config.threshold
    }

    fun start() {
        if (_state.value != State.Idle) return
        _threshold.value = config.threshold
        resetRuntimeState()
        ensureModelLoaded()
        _state.value = State.Listening
        onCalibrationComplete?.invoke()
        AppLogger.log(
            "VAD started: backend=${if (ortSession == null) "energy-fallback" else "silero-onnx"} " +
                "threshold=${config.threshold} silence=${config.silenceDurationMillis}ms",
            LogLevel.INFO,
            "Voice",
        )
    }

    fun skipCalibration() {}

    fun stop() {
        if (_state.value == State.Idle) return
        cancelTimers()
        _state.value = State.Idle
        chunkBuffer.clear()
        AppLogger.log("VAD stopped", LogLevel.INFO, "Voice")
    }

    fun process(samples: FloatArray) {
        if (_state.value == State.Idle) return
        updateEnergyLevel(samples)

        if (ortSession == null) {
            processFallbackEnergy()
            return
        }

        for (sample in samples) {
            chunkBuffer.add(sample)
            if (chunkBuffer.size >= SILERO_CHUNK_SAMPLES) {
                val chunk = FloatArray(SILERO_CHUNK_SAMPLES)
                for (i in 0 until SILERO_CHUNK_SAMPLES) chunk[i] = chunkBuffer[i]
                chunkBuffer.subList(0, SILERO_CHUNK_SAMPLES).clear()
                val probability = runSilero(chunk)
                _level.value = probability
                handleProbability(probability)
            }
        }
    }

    private fun ensureModelLoaded() {
        val appContext = context ?: return
        if (ortSession != null) return
        runCatching {
            val model = appContext.assets.open("vad/silero_vad.onnx").use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            ortSession = env.createSession(model, OrtSession.SessionOptions())
        }.onFailure {
            AppLogger.log("Silero VAD load failed: ${it.message}", LogLevel.WARNING, "Voice")
            ortSession = null
        }
    }

    private fun runSilero(chunk: FloatArray): Float {
        val env = ortEnv ?: return 0f
        val session = ortSession ?: return 0f
        return runCatching {
            val inputNames = session.inputNames.toList()
            val inputName = inputNames.firstOrNull { it == "input" } ?: inputNames.getOrElse(0) { "input" }
            val stateName = inputNames.firstOrNull { it == "state" } ?: inputNames.getOrElse(1) { "state" }
            val srName = inputNames.firstOrNull { it == "sr" } ?: inputNames.getOrElse(2) { "sr" }

            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(chunk),
                longArrayOf(1, SILERO_CHUNK_SAMPLES.toLong()),
            )
            val stateTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(sileroState),
                longArrayOf(2, 1, 128),
            )
            val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(AudioCapture.SAMPLE_RATE.toLong())), longArrayOf(1))

            session.run(mapOf(inputName to inputTensor, stateName to stateTensor, srName to srTensor)).use { result ->
                inputTensor.close()
                stateTensor.close()
                srTensor.close()
                val values = result.toList()
                val probability = firstFloat(values.getOrNull(0)?.value)
                if (result.size() > 1) {
                    sileroState = flattenFloats(values.getOrNull(1)?.value, sileroState.size)
                }
                probability
            }
        }.getOrElse {
            AppLogger.log("Silero VAD inference failed: ${it.message}", LogLevel.WARNING, "Voice")
            0f
        }
    }

    private fun handleProbability(probability: Float) {
        when (_state.value) {
            State.Listening -> {
                if (probability >= config.threshold) {
                    _state.value = State.SpeechDetected
                    speechStartTimeMs = System.currentTimeMillis()
                    cancelTimers()
                    hardStopJob = scope.launch {
                        delay(config.maxSpeechMillis)
                        if (_state.value == State.SpeechDetected) endSpeech(force = true)
                    }
                    AppLogger.log("VAD speech START prob=$probability", LogLevel.INFO, "Voice")
                    onSpeechStart?.invoke()
                }
            }
            State.SpeechDetected -> {
                if (probability >= config.threshold) {
                    silenceJob?.cancel()
                    silenceJob = null
                } else if (silenceJob == null) {
                    silenceJob = scope.launch {
                        delay(config.silenceDurationMillis)
                        endSpeech(force = false)
                    }
                }
            }
            State.Idle -> Unit
        }
    }

    private fun processFallbackEnergy() {
        val threshold = (energyFloor * 2.0f).coerceAtLeast(0.004f)
        when (_state.value) {
            State.Listening -> if (smoothedEnergy >= threshold) handleProbability(1f)
            State.SpeechDetected -> if (smoothedEnergy < threshold * 0.55f) handleProbability(0f) else handleProbability(1f)
            State.Idle -> Unit
        }
    }

    private fun endSpeech(force: Boolean) {
        if (_state.value != State.SpeechDetected) return
        val elapsed = System.currentTimeMillis() - speechStartTimeMs
        cancelTimers()
        _state.value = State.Listening
        if (!force && elapsed < config.minSpeechMillis) {
            AppLogger.log("VAD ignored short speech ${elapsed}ms", LogLevel.INFO, "Voice")
            return
        }
        AppLogger.log("VAD speech END elapsed=${elapsed}ms", LogLevel.INFO, "Voice")
        onSpeechEnd?.invoke()
    }

    private fun updateEnergyLevel(samples: FloatArray) {
        var sum = 0f
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size)
        val alpha = if (rms > smoothedEnergy) 0.25f else 0.05f
        smoothedEnergy = alpha * rms + (1f - alpha) * smoothedEnergy
        if (_state.value == State.Listening && smoothedEnergy < energyFloor * 2f) {
            energyFloor = (0.01f * smoothedEnergy + 0.99f * energyFloor).coerceIn(0.0005f, 0.02f)
        }
        if (ortSession == null) _level.value = smoothedEnergy
    }

    private fun resetRuntimeState() {
        sileroState.fill(0f)
        chunkBuffer.clear()
        silenceJob = null
        hardStopJob = null
        speechStartTimeMs = 0L
        energyFloor = 0.001f
        smoothedEnergy = 0f
    }

    private fun cancelTimers() {
        silenceJob?.cancel()
        silenceJob = null
        hardStopJob?.cancel()
        hardStopJob = null
    }

    private fun firstFloat(value: Any?): Float {
        return when (value) {
            is Float -> value
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> firstFloat(value.firstOrNull())
            else -> 0f
        }
    }

    private fun flattenFloats(value: Any?, expected: Int): FloatArray {
        val out = ArrayList<Float>(expected)
        fun visit(v: Any?) {
            when (v) {
                is Float -> out.add(v)
                is FloatArray -> for (item in v) out.add(item)
                is Array<*> -> for (item in v) visit(item)
            }
        }
        visit(value)
        if (out.size != expected) return sileroState
        return FloatArray(expected) { out[it] }
    }

    companion object {
        const val SILERO_CHUNK_SAMPLES = 512
    }
}
