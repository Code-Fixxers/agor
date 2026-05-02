package live.agor.app.ui.messageblocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown rendering for chat messages.
 *
 * Performance notes:
 *
 *  1. Bypass the Markdown composable entirely for plain text — the regex below
 *     matches actual markdown structures (fenced code, heading/bullet/quote at
 *     line-start, **bold**, `inline code`, [link](url)) and skips parsing
 *     otherwise.
 *  2. **No `key(markdown)` wrapper.** A previous version wrapped the Markdown
 *     call in `key(markdown)` thinking it would memoize, but `key()` forces
 *     Compose to *dispose and recreate* the subtree whenever the key changes —
 *     i.e. on every streaming chunk for the actively-streaming bubble. The
 *     library re-parses anyway, but at least the slot table survives across
 *     chunks now, so layout/measurement caches stay warm.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    if (markdown.isEmpty()) return
    if (!markdown.hasMarkdownStructure()) {
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
