package live.agor.app.ui.messageblocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.ContentBlock
import live.agor.app.network.StreamingService

@Composable
fun ContentBlocksView(
    blocks: List<ContentBlock>,
    liveSnapshot: StreamingService.StreamSnapshot?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is ContentBlock.Text -> {
                    val text = if (idx == blocks.size - 1 && liveSnapshot != null && liveSnapshot.text.isNotEmpty())
                        liveSnapshot.text
                    else
                        block.text
                    MarkdownText(text)
                }
                is ContentBlock.ToolUse -> ToolUseBlockView(block)
                is ContentBlock.ToolResult -> ToolResultBlockView(block)
                is ContentBlock.Thinking -> ThinkingBlockView(block, liveThinking = liveSnapshot?.thinking)
                is ContentBlock.Image -> ImageBlockView(block)
                is ContentBlock.Unknown -> Unit
            }
            if (idx != blocks.size - 1) Spacer(Modifier.height(8.dp))
        }
    }
}
