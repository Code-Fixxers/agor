package live.agor.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.MessageRole
import live.agor.app.network.StreamingService
import live.agor.app.ui.messageblocks.ContentBlocksView
import live.agor.app.ui.messageblocks.InputRequestCardView
import live.agor.app.ui.messageblocks.MarkdownText
import live.agor.app.ui.messageblocks.PermissionCardView

// Hoisted constants — allocating these per-bubble was free-ish individually but
// cumulative across hundreds of items made first-composition (when scrolling new
// content into view) noticeably janky.
private val BubbleShape = RoundedCornerShape(12.dp)
private val BubbleOuterPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 8.dp, vertical = 4.dp,
)
private val BubbleInnerPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
private val BubbleMaxWidth = 600.dp

@Composable
fun MessageBubble(
    message: Message,
    liveSnapshot: StreamingService.StreamSnapshot?,
    onDecidePermission: (String, Boolean) -> Unit,
    onAnswerInputRequest: (String, List<String>) -> Unit,
) {
    val alignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
    val bubbleColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surface
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(BubbleOuterPadding),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .background(bubbleColor, BubbleShape)
                .padding(BubbleInnerPadding),
        ) {
            when (val c = message.content) {
                is MessageContent.Text -> {
                    val text = liveSnapshot?.text?.takeIf { it.isNotEmpty() } ?: c.text
                    MarkdownText(markdown = text)
                }
                is MessageContent.Blocks -> ContentBlocksView(c.blocks, liveSnapshot)
                is MessageContent.Permission -> PermissionCardView(
                    request = c.request,
                    onApprove = { onDecidePermission(c.request.permissionId, true) },
                    onDeny = { onDecidePermission(c.request.permissionId, false) },
                )
                is MessageContent.InputRequest -> InputRequestCardView(
                    request = c.request,
                    onAnswer = { answers -> onAnswerInputRequest(c.request.inputRequestId, answers) },
                )
            }
        }
    }
}

@Composable
fun StreamingPlaceholder(snapshot: StreamingService.StreamSnapshot) {
    Column(modifier = Modifier.fillMaxWidth().padding(BubbleOuterPadding)) {
        Box(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .background(MaterialTheme.colorScheme.surface, BubbleShape)
                .padding(BubbleInnerPadding),
        ) {
            Column {
                if (snapshot.thinking.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        snapshot.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MarkdownText(markdown = snapshot.text)
            }
        }
    }
}
