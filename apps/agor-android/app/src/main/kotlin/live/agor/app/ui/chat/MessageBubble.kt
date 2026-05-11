package live.agor.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.MessageRole
import live.agor.app.ui.common.copyOnDoubleTap
import live.agor.app.ui.messageblocks.MarkdownText

// Hoisted constants so each visible bubble doesn't allocate its own modifier values.
private val BubbleShape = RoundedCornerShape(12.dp)
private val BubbleOuterPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val BubbleInnerPadding = PaddingValues(12.dp)
private val BubbleMaxWidth = 600.dp

/**
 * Single text bubble row. Operates on a pre-resolved [ChatRow.TextBubbleRow]
 * whose [ChatRow.TextBubbleRow.text] already incorporates any live streaming
 * snapshot, so this composable is a near-trivial mapper: stable params in, layout
 * out, no recompose for unrelated messages.
 */
@Composable
fun TextBubble(
    row: ChatRow.TextBubbleRow,
    onSessionClick: (String) -> Unit,
) {
    val alignment = if (row.role == MessageRole.USER) Alignment.End else Alignment.Start
    val bubbleColor = when (row.role) {
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
                .copyOnDoubleTap(row.text)
                .padding(BubbleInnerPadding),
        ) {
            // While streaming, render as plain Text. The markdown library re-parses
            // its full AST on every recompose; with chunks arriving up to ~10 Hz the
            // parse alone burns the frame budget on Main and tanks streaming smoothness.
            // The in-progress text is half-formed markdown anyway (open code fences,
            // dangling **bold). Once the message commits, switch to the full renderer.
            if (row.streaming) {
                Text(text = row.text)
            } else {
                MarkdownText(markdown = row.text, onSessionClick = onSessionClick)
            }
        }
    }
}

/**
 * Streaming-only placeholder for a snapshot whose owning message hasn't been
 * confirmed by the server yet. Renders thinking + text inline.
 */
@Composable
fun LiveOrphanBubble(row: ChatRow.LiveOrphanRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(BubbleOuterPadding)) {
        Box(
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .background(MaterialTheme.colorScheme.surface, BubbleShape)
                .copyOnDoubleTap(listOf(row.thinking, row.text).filter { it.isNotBlank() }.joinToString("\n\n"))
                .padding(BubbleInnerPadding),
        ) {
            Column {
                if (row.thinking.isNotEmpty()) {
                    Text(
                        row.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.text.isNotEmpty()) {
                    // Orphan rows are by definition mid-stream — they only exist
                    // until the canonical message lands. Plain Text only;
                    // markdown rendering happens once the message commits and
                    // becomes a TextBubbleRow.
                    Text(text = row.text)
                }
            }
        }
    }
}
