package live.agor.app.voice

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionVoiceDataStore by preferencesDataStore(name = "agor_session_voice_settings")

data class SessionVoiceSettings(
    val vadSensitivity: Float = DEFAULT_VAD_SENSITIVITY,
    val silenceBeforeSendMillis: Long = DEFAULT_SILENCE_BEFORE_SEND_MS,
) {
    fun toVadConfig(): VadConfig = VadConfig(
        threshold = VadConfig.thresholdFor(vadSensitivity),
        silenceDurationMillis = silenceBeforeSendMillis.coerceIn(
            MIN_SILENCE_BEFORE_SEND_MS,
            MAX_SILENCE_BEFORE_SEND_MS,
        ),
    )

    companion object {
        const val DEFAULT_VAD_SENSITIVITY = 0.5f
        const val MIN_SILENCE_BEFORE_SEND_MS = 700L
        const val MAX_SILENCE_BEFORE_SEND_MS = 6_000L
        const val DEFAULT_SILENCE_BEFORE_SEND_MS = 3_000L
        val Default = SessionVoiceSettings()
    }
}

class SessionVoiceSettingsStore(private val context: Context) {
    val settings: Flow<SessionVoiceSettings> = context.sessionVoiceDataStore.data.map { prefs ->
        SessionVoiceSettings(
            vadSensitivity = (prefs[VAD_SENSITIVITY] ?: SessionVoiceSettings.DEFAULT_VAD_SENSITIVITY)
                .coerceIn(0f, 1f),
            silenceBeforeSendMillis = (prefs[SILENCE_BEFORE_SEND] ?: SessionVoiceSettings.DEFAULT_SILENCE_BEFORE_SEND_MS)
                .coerceIn(
                    SessionVoiceSettings.MIN_SILENCE_BEFORE_SEND_MS,
                    SessionVoiceSettings.MAX_SILENCE_BEFORE_SEND_MS,
                ),
        )
    }

    suspend fun save(settings: SessionVoiceSettings) {
        context.sessionVoiceDataStore.edit { prefs ->
            prefs[VAD_SENSITIVITY] = settings.vadSensitivity.coerceIn(0f, 1f)
            prefs[SILENCE_BEFORE_SEND] = settings.silenceBeforeSendMillis.coerceIn(
                SessionVoiceSettings.MIN_SILENCE_BEFORE_SEND_MS,
                SessionVoiceSettings.MAX_SILENCE_BEFORE_SEND_MS,
            )
        }
    }

    suspend fun reset() = save(SessionVoiceSettings.Default)

    private companion object {
        val VAD_SENSITIVITY = floatPreferencesKey("vad_sensitivity")
        val SILENCE_BEFORE_SEND = longPreferencesKey("silence_before_send_ms")
    }
}
