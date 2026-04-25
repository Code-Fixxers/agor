package live.agor.app.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tunable VAD constants. Mirrors apps/agor-ios/AgorApp/Services/VADConfig.swift line-for-line.
 * Values are read on the audio thread; mutate freely — they take effect on the next frame.
 */
@Serializable
data class VadConfig(
    @SerialName("emaAttackAlpha") var emaAttackAlpha: Float = 0.50f,
    @SerialName("emaReleaseAlpha") var emaReleaseAlpha: Float = 0.08f,

    @SerialName("noiseFloorCalibrationAlpha") var noiseFloorCalibrationAlpha: Float = 0.15f,
    @SerialName("noiseFloorRiseAlpha") var noiseFloorRiseAlpha: Float = 0f,
    @SerialName("noiseFloorFallAlpha") var noiseFloorFallAlpha: Float = 0.008f,
    @SerialName("maxNoiseFloor") var maxNoiseFloor: Float = 0.005f,
    @SerialName("noiseFloorFreezeFrames") var noiseFloorFreezeFrames: Int = 20,

    @SerialName("calibrationFrameCount") var calibrationFrameCount: Int = 20,

    @SerialName("confirmationRequired") var confirmationRequired: Int = 3,
    @SerialName("confirmationWindow") var confirmationWindow: Int = 5,

    @SerialName("startMultiplierAtLowSensitivity") var startMultiplierAtLowSensitivity: Float = 3.0f,
    @SerialName("startMultiplierAtHighSensitivity") var startMultiplierAtHighSensitivity: Float = 1.5f,

    @SerialName("hysteresisRatio") var hysteresisRatio: Float = 0.65f,
    @SerialName("suppressRiseGateMultiplier") var suppressRiseGateMultiplier: Float = 2.0f,

    @SerialName("silenceDurationMillis") var silenceDurationMillis: Long = 3_000,
) {
    fun startMultiplier(sensitivity: Float): Float =
        startMultiplierAtLowSensitivity -
            sensitivity * (startMultiplierAtLowSensitivity - startMultiplierAtHighSensitivity)
}
