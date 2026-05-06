package live.agor.app.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Runtime tuning for the local Silero VAD path.
 *
 * Silero returns a speech probability in the 0.0-1.0 range. Lower thresholds
 * are more eager; longer silence durations wait through conversational pauses.
 */
@Serializable
data class VadConfig(
    @SerialName("threshold") var threshold: Float = 0.70f,
    @SerialName("silenceDurationMillis") var silenceDurationMillis: Long = 3_000,
    @SerialName("preRollMillis") var preRollMillis: Long = 2_000,
    @SerialName("minSpeechMillis") var minSpeechMillis: Long = 450,
    @SerialName("maxSpeechMillis") var maxSpeechMillis: Long = 30_000,
) {
    fun setSensitivity(sensitivity: Float) {
        threshold = thresholdFor(sensitivity)
    }

    companion object {
        fun thresholdFor(sensitivity: Float): Float {
            return 0.9f - sensitivity.coerceIn(0f, 1f) * 0.6f
        }

        fun sensitivityFor(threshold: Float): Float {
            return ((0.9f - threshold) / 0.6f).coerceIn(0f, 1f)
        }
    }
}
