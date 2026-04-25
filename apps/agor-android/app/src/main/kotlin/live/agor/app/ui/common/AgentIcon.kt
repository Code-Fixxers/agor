package live.agor.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.AgenticTool

@Composable
fun AgentIcon(tool: AgenticTool, modifier: Modifier = Modifier) {
    val icon = when (tool) {
        AgenticTool.CLAUDE_CODE -> Icons.Default.Star
        AgenticTool.CODEX -> Icons.Default.Code
        AgenticTool.GEMINI -> Icons.Default.AutoAwesome
        AgenticTool.OPENCODE -> Icons.Default.Hexagon
    }
    Icon(icon, contentDescription = tool.displayName, modifier = modifier.size(16.dp))
}
