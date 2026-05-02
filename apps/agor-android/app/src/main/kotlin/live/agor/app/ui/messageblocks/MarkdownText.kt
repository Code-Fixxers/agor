package live.agor.app.ui.messageblocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown rendering for chat messages.
 *
 * Performance notes — the multiplatform-markdown-renderer library re-parses on every
 * recomposition. To keep scrolling smooth we:
 *   1. Bypass the Markdown composable entirely for plain text. The previous heuristic
 *      tripped on any "_" or "[" in an English sentence — way too eager. The new one
 *      only matches actual markdown structures (fenced code, heading at line-start,
 *      bullet/quote at line-start, **bold**, `inline code`, [link](url)).
 *   2. Wrap the Markdown call in `key(markdown)` so when the same string scrolls back
 *      into view, Compose reuses the slot table for that content.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    if (markdown.isEmpty()) return
    if (!markdown.hasMarkdownStructure()) {
        Text(text = markdown, modifier = modifier)
        return
    }
    key(markdown) {
        Markdown(
            content = markdown,
            colors = markdownColor(),
            typography = markdownTypography(),
            modifier = modifier,
        )
    }
}

/**
 * True when the string contains *structural* markdown — not just punctuation that
 * happens to be a markdown character.
 */
private val markdownStructuralRegex = Regex(
    "(?m)" +
        // fenced code anywhere
        "(```)" +
        // heading at line start (1–6 #s followed by space)
        "|(^#{1,6}\\s)" +
        // bullet / quote at line start
        "|(^[-*>+]\\s)" +
        // bold (**…**) — require non-* immediately after for cheap eager match
        "|(\\*\\*[^*])" +
        // inline code (single backtick run)
        "|(`[^`\\n]+`)" +
        // markdown link [text](url)
        "|(\\[[^\\]\\n]+\\]\\()"
)

private fun String.hasMarkdownStructure(): Boolean = markdownStructuralRegex.containsMatchIn(this)
