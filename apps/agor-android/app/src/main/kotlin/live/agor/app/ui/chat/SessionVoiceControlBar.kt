package live.agor.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import live.agor.app.voice.SessionVoicePhase
import live.agor.app.voice.SessionVoiceSettings
import live.agor.app.voice.SessionVoiceState

@Composable
fun SessionVoiceControlBar(
    state: SessionVoiceState,
    settings: SessionVoiceSettings,
    onPendingTranscriptChange: (String) -> Unit,
    onCancelPendingTranscript: () -> Unit,
    onSendPendingTranscript: () -> Unit,
    onSkipTts: () -> Unit,
    onStopVoice: () -> Unit,
    onSettingsChange: (SessionVoiceSettings) -> Unit,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .testTag("session-voice-controls"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AssistChip(
                    onClick = {},
                    label = { Text(sessionVoiceStatusLabel(state)) },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                )
                if (state.phase == SessionVoicePhase.Speaking) {
                    IconButton(onClick = onSkipTts, modifier = Modifier.testTag("session-voice-skip-tts")) {
                        Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "Skip speech")
                    }
                }
                IconButton(onClick = onStopVoice, modifier = Modifier.testTag("session-voice-stop")) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop voice mode")
                }
            }

            state.pendingTranscript?.let { transcript ->
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = transcript,
                    onValueChange = onPendingTranscriptChange,
                    modifier = Modifier.fillMaxWidth().testTag("session-voice-transcript"),
                    minLines = 1,
                    maxLines = 4,
                    label = { Text("Transcript") },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onCancelPendingTranscript) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel transcript")
                            }
                            IconButton(onClick = onSendPendingTranscript) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send transcript")
                            }
                        }
                    },
                )
            }

            if (state.phase == SessionVoicePhase.Listening || state.phase == SessionVoicePhase.Recording) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (state.audioLevel / state.threshold.coerceAtLeast(0.01f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Sensitivity",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.vadSensitivity,
                onValueChange = { onSettingsChange(settings.copy(vadSensitivity = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.testTag("session-voice-sensitivity"),
            )
            Text(
                "Silence ${(settings.silenceBeforeSendMillis / 1000.0).format1()}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.silenceBeforeSendMillis.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(silenceBeforeSendMillis = it.toLong())) },
                valueRange = SessionVoiceSettings.MIN_SILENCE_BEFORE_SEND_MS.toFloat()..
                    SessionVoiceSettings.MAX_SILENCE_BEFORE_SEND_MS.toFloat(),
                modifier = Modifier.testTag("session-voice-silence"),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onResetSettings, modifier = Modifier.testTag("session-voice-reset-settings")) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset voice settings")
                }
            }
        }
    }
}

private fun sessionVoiceStatusLabel(state: SessionVoiceState): String = when (state.phase) {
    SessionVoicePhase.Disabled -> "Voice off"
    SessionVoicePhase.Preparing -> "Preparing voice..."
    SessionVoicePhase.Listening -> "Listening"
    SessionVoicePhase.Paused -> "Waiting for session"
    SessionVoicePhase.Recording -> "Recording"
    SessionVoicePhase.Transcribing -> "Transcribing"
    SessionVoicePhase.Reviewing -> "Reviewing transcript"
    SessionVoicePhase.Sending -> "Sending"
    SessionVoicePhase.Speaking -> "Speaking"
    SessionVoicePhase.Error -> state.errorMessage ?: "Voice failed"
}

private fun Double.format1(): String = if (this >= 10) {
    toInt().toString()
} else {
    "%.1f".format(this)
}
