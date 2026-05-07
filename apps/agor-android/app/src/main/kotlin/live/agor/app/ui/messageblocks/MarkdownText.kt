package live.agor.app.ui.messageblocks

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
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
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onSessionClick: ((String) -> Unit)? = null,
) {
    if (markdown.isEmpty()) return
    if (!markdown.hasMarkdownStructure()) {
        SessionLinkedText(text = markdown, modifier = modifier, onSessionClick = onSessionClick)
        return
    }
    val uriHandler = LocalUriHandler.current
    CompositionLocalProvider(LocalUriHandler provides sessionUriHandler(uriHandler, onSessionClick)) {
        Markdown(
            content = markdown.withSessionLinks(),
            colors = markdownColor(),
            typography = markdownTypography(),
            modifier = modifier,
        )
    }
}

@Composable
fun SessionLinkedText(
    text: String,
    modifier: Modifier = Modifier,
    onSessionClick: ((String) -> Unit)? = null,
) {
    val matches = sessionIdRegex.findAll(text).toList()
    if (matches.isEmpty() || onSessionClick == null) {
        Text(text = text, modifier = modifier)
        return
    }

    val linkColor = Color(0xFF2F80ED)
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in matches) {
            val sessionId = match.value
            append(text.substring(cursor, match.range.first))
            pushStringAnnotation(TAG_SESSION, sessionId)
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            append(sessionId)
            pop()
            pop()
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        onClick = { offset ->
            annotated
                .getStringAnnotations(TAG_SESSION, offset, offset)
                .firstOrNull()
                ?.let { onSessionClick(it.item) }
        },
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

private const val TAG_SESSION = "agor-session"
private const val SESSION_URI_PREFIX = "agor-session://"

private val sessionIdRegex = Regex(
    "(?<![A-Za-z0-9_-])" +
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" +
        "(?![A-Za-z0-9_-])",
)

private fun String.withSessionLinks(): String {
    return sessionIdRegex.replace(this) { match ->
        val id = match.value
        "[$id]($SESSION_URI_PREFIX$id)"
    }
}

private fun sessionUriHandler(
    delegate: UriHandler,
    onSessionClick: ((String) -> Unit)?,
): UriHandler = object : UriHandler {
    override fun openUri(uri: String) {
        if (uri.startsWith(SESSION_URI_PREFIX) && onSessionClick != null) {
            onSessionClick(uri.removePrefix(SESSION_URI_PREFIX))
            return
        }
        delegate.openUri(uri)
    }
}
