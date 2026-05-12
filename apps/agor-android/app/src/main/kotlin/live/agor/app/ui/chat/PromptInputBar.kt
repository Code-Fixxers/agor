package live.agor.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import live.agor.app.viewmodels.ChatViewModel
import live.agor.app.voice.PromptVoicePhase
import live.agor.app.voice.PromptVoiceInputState

@Composable
fun PromptInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    attachments: List<ChatViewModel.PendingSessionAttachment> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    voiceState: PromptVoiceInputState = PromptVoiceInputState(),
    onVoiceInputClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val voiceActive = voiceState.phase == PromptVoicePhase.LoadingModels ||
        voiceState.phase == PromptVoicePhase.Listening ||
        voiceState.phase == PromptVoicePhase.Recording ||
        voiceState.phase == PromptVoicePhase.Transcribing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(attachment.id) },
                            label = {
                                Text(
                                    attachment.uploadedPath
                                        ?: "${attachment.filename} (${formatAttachmentSize(attachment.sizeBytes)})",
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onAttachClick,
                    enabled = enabled,
                    modifier = Modifier.testTag("prompt-attach"),
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(if (enabled) "Send a prompt…" else "Session is busy") },
                    modifier = Modifier.weight(1f).testTag("prompt-input"),
                    maxLines = 6,
                    enabled = enabled,
                )
                IconButton(
                    onClick = onVoiceInputClick,
                    enabled = enabled || voiceActive,
                    modifier = Modifier.testTag("prompt-voice"),
                ) {
                    Icon(
                        if (voiceActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (voiceActive) "Stop voice input" else "Voice input",
                    )
                }
                IconButton(
                    onClick = onSend,
                    enabled = enabled && (draft.isNotBlank() || attachments.isNotEmpty()),
                    modifier = Modifier.testTag("prompt-send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
            if (voiceActive || voiceState.phase == PromptVoicePhase.Error) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = voiceStatusLabel(voiceState),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (voiceState.phase == PromptVoicePhase.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("prompt-voice-status"),
                )
                voiceState.liveTranscript?.takeIf { it.isNotBlank() }?.let { partial ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = partial,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("prompt-voice-partial"),
                    )
                }
                if (voiceState.phase == PromptVoicePhase.Listening || voiceState.phase == PromptVoicePhase.Recording) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (voiceState.audioLevel / voiceState.threshold.coerceAtLeast(0.01f)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "${kib.format1()} KB"
    return "${(kib / 1024.0).format1()} MB"
}

private fun Double.format1(): String = if (this >= 10) {
    toInt().toString()
} else {
    "%.1f".format(this)
}

private fun voiceStatusLabel(state: PromptVoiceInputState): String {
    return when (state.phase) {
        PromptVoicePhase.Idle -> ""
        PromptVoicePhase.LoadingModels -> "Loading voice input..."
        PromptVoicePhase.Listening -> "Listening..."
        PromptVoicePhase.Recording -> if (state.liveTranscript.isNullOrBlank()) "Recording..." else "Live transcribing..."
        PromptVoicePhase.Transcribing -> "Transcribing..."
        PromptVoicePhase.Error -> state.errorMessage ?: "Voice input failed"
    }
}
