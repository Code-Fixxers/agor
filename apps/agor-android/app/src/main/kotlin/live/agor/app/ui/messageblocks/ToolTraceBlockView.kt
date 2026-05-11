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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInFull
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

private val ToolTraceShape = RoundedCornerShape(8.dp)
private const val MAX_TRACE_PREVIEW_LINES = 3
private const val MAX_TRACE_PREVIEW_CHARS = 240

@Composable
fun ToolTraceBlockView(row: ChatRow.ToolTraceRow) {
    var showDetails by remember(row.key) { mutableStateOf(false) }
    val container = if (row.hasError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (row.hasError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val preview = remember(row.items) {
        row.items.take(MAX_TRACE_PREVIEW_LINES).joinToString("\n") { item ->
            val prefix = if (item.kind == ChatRow.ToolTraceKind.Use) ">" else "<"
            val headline = item.summary.ifBlank { item.body.lineSequence().firstOrNull().orEmpty() }
            val compact = headline.replace('\n', ' ').trim()
            val clipped = if (compact.length > MAX_TRACE_PREVIEW_CHARS) compact.take(MAX_TRACE_PREVIEW_CHARS) + "…" else compact
            "$prefix ${item.title}: $clipped"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, ToolTraceShape)
            .clickable { showDetails = true }
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (row.hasError) Icons.Default.ErrorOutline else Icons.Default.Build,
                contentDescription = null,
                tint = onContainer,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${row.items.size} tool events",
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.OpenInFull, contentDescription = null, tint = onContainer)
        }
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = onContainer,
                maxLines = MAX_TRACE_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap to inspect full tool trace",
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
        )
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Tool trace") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    row.items.forEachIndexed { index, item ->
                        Text(
                            "${index + 1}. ${item.title}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.summary.isNotBlank()) {
                            Text(
                                item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (item.body.isNotBlank()) {
                            Text(
                                item.body,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (index != row.items.lastIndex) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text("Close")
                }
            },
        )
    }
}
