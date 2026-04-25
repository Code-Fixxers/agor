package live.agor.app.voice

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
 * Pure DSP/state-machine port of apps/agor-ios/AgorApp/Services/VoiceActivityDetector.swift.
 *
 * Audio capture is the caller's responsibility — feed PCM float frames via [process].
 * The detector emits onSpeechStart / onSpeechEnd callbacks on a coroutine scope.
 *
 * State changes are deterministic given identical inputs and config; this code has no
 * platform dependencies and is unit-testable.
 */
class VoiceActivityDetector(
    var config: VadConfig = VadConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    enum class State { Idle, Listening, SpeechDetected }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val currentAudioLevel: StateFlow<Float> = _level.asStateFlow()

    private val _threshold = MutableStateFlow(0f)
    val energyThreshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.5f)
    val sensitivityLevel: StateFlow<Float> = _sensitivity.asStateFlow()

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onCalibrationComplete: (() -> Unit)? = null

    private var smoothedEnergy: Float = 0f
    private var noiseFloor: Float = 0.001f
    private var calibrationFramesRemaining: Int = 0
    private val recentAbove = BooleanArray(RING_BUFFER_SIZE)
    private var frameIndex: Int = 0
    private var freezeFramesRemaining: Int = 0

    private var lastSoundTimeMs: Long = 0
    private var speechStartTimeMs: Long = 0

    private var bufferCount: Int = 0
    private var silenceCheckJob: Job? = null

    private val startMultiplier: Float
        get() = config.startMultiplier(_sensitivity.value)
    private val endMultiplier: Float
        get() = startMultiplier * config.hysteresisRatio

    fun setSensitivity(sensitivity: Float) {
        _sensitivity.value = sensitivity.coerceIn(0f, 1f)
    }

    fun start() {
        if (_state.value != State.Idle) return
        smoothedEnergy = 0f
        noiseFloor = 0.001f
        recentAbove.fill(false)
        frameIndex = 0
        freezeFramesRemaining = 0
        calibrationFramesRemaining = config.calibrationFrameCount
        _threshold.value = noiseFloor * startMultiplier
        lastSoundTimeMs = System.currentTimeMillis()
        _state.value = State.Listening
        startSilenceCheckTimer()
        AppLogger.log(
            "VAD started: emaAtk=${config.emaAttackAlpha} emaRel=${config.emaReleaseAlpha} " +
                "confirm=${config.confirmationRequired}of${config.confirmationWindow}",
            LogLevel.INFO, "Voice",
        )
    }

    fun skipCalibration() {
        calibrationFramesRemaining = 0
    }

    fun stop() {
        if (_state.value == State.Idle) return
        silenceCheckJob?.cancel()
        silenceCheckJob = null
        _state.value = State.Idle
        AppLogger.log("VAD stopped", LogLevel.INFO, "Voice")
    }

    /**
     * Feed a PCM frame in float [-1, 1] range. Caller is responsible for matching
     * sample rate to the configured frame size assumptions (~21ms at 48kHz default).
     */
    fun process(samples: FloatArray) {
        var sum = 0f
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size)
        val emaAlpha = if (rms > smoothedEnergy) config.emaAttackAlpha else config.emaReleaseAlpha
        smoothedEnergy = emaAlpha * rms + (1f - emaAlpha) * smoothedEnergy

        val startThreshold = noiseFloor * startMultiplier
        val endThreshold = noiseFloor * endMultiplier

        when (_state.value) {
            State.Listening -> {
                val isCalibrating = calibrationFramesRemaining > 0
                val riseAlpha = if (isCalibrating) config.noiseFloorCalibrationAlpha else config.noiseFloorRiseAlpha
                val suppressGate = noiseFloor * config.suppressRiseGateMultiplier

                if (!isCalibrating && (smoothedEnergy >= suppressGate || smoothedEnergy > startThreshold)) {
                    freezeFramesRemaining = config.noiseFloorFreezeFrames
                }

                val suppressRise = !isCalibrating && (smoothedEnergy >= suppressGate || freezeFramesRemaining > 0)

                if (smoothedEnergy > noiseFloor && !suppressRise) {
                    noiseFloor = riseAlpha * smoothedEnergy + (1f - riseAlpha) * noiseFloor
                } else if (smoothedEnergy < noiseFloor) {
                    noiseFloor = config.noiseFloorFallAlpha * smoothedEnergy +
                        (1f - config.noiseFloorFallAlpha) * noiseFloor
                }
            }
            State.SpeechDetected -> {
                if (smoothedEnergy < noiseFloor) {
                    noiseFloor = config.noiseFloorFallAlpha * smoothedEnergy +
                        (1f - config.noiseFloorFallAlpha) * noiseFloor
                }
            }
            State.Idle -> return
        }

        noiseFloor = noiseFloor.coerceIn(0.0005f, config.maxNoiseFloor)

        if (freezeFramesRemaining > 0) freezeFramesRemaining -= 1

        _level.value = smoothedEnergy
        _threshold.value = noiseFloor * startMultiplier

        bufferCount += 1

        if (_state.value == State.Listening) {
            if (calibrationFramesRemaining > 0) {
                calibrationFramesRemaining -= 1
                recentAbove[frameIndex % RING_BUFFER_SIZE] = false
                frameIndex += 1
                if (calibrationFramesRemaining == 0) {
                    onCalibrationComplete?.invoke()
                }
            } else {
                val isAbove = smoothedEnergy > startThreshold
                recentAbove[frameIndex % RING_BUFFER_SIZE] = isAbove
                frameIndex += 1
                val window = minOf(config.confirmationWindow, RING_BUFFER_SIZE, frameIndex)
                var hits = 0
                for (i in (frameIndex - window) until frameIndex) {
                    if (recentAbove[((i % RING_BUFFER_SIZE) + RING_BUFFER_SIZE) % RING_BUFFER_SIZE]) hits += 1
                }
                if (hits >= config.confirmationRequired) {
                    recentAbove.fill(false)
                    frameIndex = 0
                    freezeFramesRemaining = 0
                    speechStartTimeMs = System.currentTimeMillis()
                    _state.value = State.SpeechDetected
                    AppLogger.log(
                        "VAD speech START hits=$hits/$window smoothed=$smoothedEnergy floor=$noiseFloor",
                        LogLevel.INFO, "Voice",
                    )
                    onSpeechStart?.invoke()
                }
            }
        }

        if (smoothedEnergy > endThreshold) {
            lastSoundTimeMs = System.currentTimeMillis()
        }
    }

    private fun startSilenceCheckTimer() {
        silenceCheckJob?.cancel()
        silenceCheckJob = scope.launch {
            while (true) {
                delay(100)
                checkSilence()
            }
        }
    }

    private fun checkSilence() {
        if (_state.value != State.SpeechDetected) return
        val elapsed = System.currentTimeMillis() - lastSoundTimeMs
        if (elapsed >= config.silenceDurationMillis) {
            _state.value = State.Listening
            AppLogger.log("VAD speech END silence=${elapsed}ms", LogLevel.INFO, "Voice")
            onSpeechEnd?.invoke()
        }
    }

    private companion object {
        const val RING_BUFFER_SIZE = 30
    }
}
