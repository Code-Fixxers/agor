package live.agor.app.ui.messageblocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown rendering for chat messages. Uses multiplatform-markdown-renderer.
 * For very small strings (single-line, no markdown features) it falls back to plain Text
 * so we don't pay parsing overhead during streaming.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    if (markdown.isEmpty()) return
    if (markdown.length < 64 && !markdown.containsMarkdownTokens()) {
        Text(text = markdown, modifier = modifier)
        return
    }
    Markdown(
        content = markdown,
        colors = markdownColor(),
        typography = markdownTypography(),
        modifier = modifier,
    )
}

private fun String.containsMarkdownTokens(): Boolean =
    contains("```") || contains("**") || contains("_") || contains("[") ||
        contains("#") || contains(">")
