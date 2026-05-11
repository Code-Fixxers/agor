package live.agor.app.ui.messageblocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import live.agor.app.ui.chat.ChatRow

private val ToolBlockShape = RoundedCornerShape(8.dp)
private const val MAX_INLINE_TOOL_PREVIEW_CHARS = 480
private const val MAX_INLINE_TOOL_PREVIEW_LINES = 6

/**
 * Renders a precomputed [ChatRow.ToolUseRow]. Crucial: the input JSON string is
 * computed once, upstream, in [live.agor.app.ui.chat.flattenChatRows] — *not*
 * re-encoded on every recomposition like the previous implementation did.
 */
@Composable
fun ToolUseBlockView(row: ChatRow.ToolUseRow) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    var showFullBody by remember(row.key) { mutableStateOf(false) }
    val container = MaterialTheme.colorScheme.surfaceVariant
    val inlinePreview = remember(row.inputJson) {
        row.inputJson
            .lineSequence()
            .take(MAX_INLINE_TOOL_PREVIEW_LINES)
            .joinToString("\n")
            .let { preview ->
                if (preview.length <= MAX_INLINE_TOOL_PREVIEW_CHARS) preview
                else preview.take(MAX_INLINE_TOOL_PREVIEW_CHARS) + "…"
            }
    }
    val bodyTruncated = row.inputJson.length > inlinePreview.length
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, ToolBlockShape)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Build, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                row.inputSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(2f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                inlinePreview,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = MAX_INLINE_TOOL_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${row.inputJson.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showFullBody = true }) {
                Text(if (bodyTruncated) "View full payload" else "Open payload")
            }
        }
    }
    if (showFullBody) {
        AlertDialog(
            onDismissRequest = { showFullBody = false },
            title = { Text(row.name) },
            text = {
                Text(
                    row.inputJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
            confirmButton = {
                TextButton(onClick = { showFullBody = false }) {
                    Text("Close")
                }
            },
        )
    }
}
