package live.agor.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VadConfigTest {
    @Test
    fun thresholdMappingMakesHigherSensitivityMoreEager() {
        assertEquals(0.90f, VadConfig.thresholdFor(0f), 0.0001f)
        assertEquals(0.60f, VadConfig.thresholdFor(0.5f), 0.0001f)
        assertEquals(0.30f, VadConfig.thresholdFor(1f), 0.0001f)
    }

    @Test
    fun thresholdMappingClampsOutOfRangeSensitivity() {
        assertEquals(0.90f, VadConfig.thresholdFor(-10f), 0.0001f)
        assertEquals(0.30f, VadConfig.thresholdFor(10f), 0.0001f)
    }

    @Test
    fun sessionVoiceSettingsApplyToVadConfig() {
        val config = SessionVoiceSettings(
            vadSensitivity = 0.75f,
            silenceBeforeSendMillis = 1_800L,
        ).toVadConfig()

        assertEquals(VadConfig.thresholdFor(0.75f), config.threshold, 0.0001f)
        assertEquals(1_800L, config.silenceDurationMillis)
    }
}
