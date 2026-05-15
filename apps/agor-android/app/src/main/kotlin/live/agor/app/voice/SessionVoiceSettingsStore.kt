package live.agor.app.voice

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class SessionVoiceSettings(
    val reserved: Boolean = false,
) {
    companion object {
        val Default = SessionVoiceSettings()
    }
}

class SessionVoiceSettingsStore(@Suppress("UNUSED_PARAMETER") context: Context) {
    val settings: Flow<SessionVoiceSettings> = flowOf(SessionVoiceSettings.Default)

    suspend fun save(settings: SessionVoiceSettings) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = settings
    }

    suspend fun reset() = save(SessionVoiceSettings.Default)
}
