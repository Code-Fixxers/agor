package live.agor.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import live.agor.app.models.SessionStatus

private val StatusBadgeShape = RoundedCornerShape(6.dp)

@Composable
fun StatusBadge(status: SessionStatus) {
    val (label, bg, fg) = when (status) {
        SessionStatus.IDLE -> Triple("idle", Color(0xFF1F242B), Color(0xFFB6BEC8))
        SessionStatus.RUNNING -> Triple("running", Color(0xFF2A6BFF), Color.White)
        SessionStatus.STOPPING -> Triple("stopping", Color(0xFFB99000), Color.White)
        SessionStatus.AWAITING_PERMISSION -> Triple("perm", Color(0xFFFFAA00), Color.Black)
        SessionStatus.AWAITING_INPUT -> Triple("input", Color(0xFFAA66FF), Color.White)
        SessionStatus.TIMED_OUT -> Triple("timed out", Color(0xFF8B6E2A), Color.White)
        SessionStatus.COMPLETED -> Triple("done", Color(0xFF2EA05A), Color.White)
        SessionStatus.FAILED -> Triple("failed", Color(0xFFD64545), Color.White)
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = fg,
        modifier = Modifier
            .background(bg, StatusBadgeShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
