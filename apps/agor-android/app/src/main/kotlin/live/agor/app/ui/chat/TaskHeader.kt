package live.agor.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.AgorTask
import live.agor.app.models.TaskStatus

@Composable
fun TaskHeader(
    task: AgorTask,
    expanded: Boolean = true,
    loadedMessageCount: Int = 0,
    onToggle: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onToggle != null) { onToggle?.invoke() }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse task" else "Expand task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = task.title ?: task.prompt?.take(80) ?: "Task",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            taskStatusLabel(task.status)?.let { status ->
                Spacer(Modifier.width(6.dp))
                val colors = taskStatusColors(task.status)
                Surface(
                    color = colors.first,
                    contentColor = colors.second,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (expanded && loadedMessageCount > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "($loadedMessageCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(Modifier.weight(1f))
        }
    }
}

internal fun taskStatusLabel(status: TaskStatus): String? = when (status) {
    TaskStatus.COMPLETED -> null
    TaskStatus.QUEUED -> "queued"
    TaskStatus.RUNNING -> "running"
    TaskStatus.FAILED -> "failed"
    TaskStatus.STOPPED -> "stopped"
    TaskStatus.TIMED_OUT -> "timed out"
    TaskStatus.AWAITING_PERMISSION -> "permission"
    TaskStatus.AWAITING_INPUT -> "input"
}

@Composable
private fun taskStatusColors(status: TaskStatus) = when (status) {
    TaskStatus.FAILED,
    TaskStatus.TIMED_OUT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    TaskStatus.AWAITING_PERMISSION,
    TaskStatus.AWAITING_INPUT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    TaskStatus.QUEUED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    TaskStatus.STOPPED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}
