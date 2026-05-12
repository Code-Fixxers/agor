package live.agor.app.ui.messageblocks

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    onWorktreePathClick: ((String) -> Unit)? = null,
) {
    if (markdown.isEmpty()) return
    val hasMarkdownStructure = remember(markdown) { markdown.hasMarkdownStructure() }
    if (!hasMarkdownStructure) {
        if (markdown.length > MAX_PLAIN_TEXT_LINKIFY_LENGTH) {
            Text(text = markdown, modifier = modifier)
            return
        }
        SessionLinkedText(
            text = markdown,
            modifier = modifier,
            onSessionClick = onSessionClick,
            onWorktreePathClick = onWorktreePathClick,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    val linkedMarkdown = remember(markdown) { markdown.withSessionLinks().withWorktreePathLinks() }
    val linkedUriHandler = remember(uriHandler, onSessionClick, onWorktreePathClick) {
        agorUriHandler(uriHandler, onSessionClick, onWorktreePathClick)
    }
    CompositionLocalProvider(LocalUriHandler provides linkedUriHandler) {
        Markdown(
            content = linkedMarkdown,
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
    onWorktreePathClick: ((String) -> Unit)? = null,
) {
    val sessionMatches = remember(text) { sessionIdRegex.findAll(text).toList() }
    val pathMatches = remember(text) { extractWorktreePathLinks(text) }
    val spans = remember(text, sessionMatches, pathMatches, onSessionClick, onWorktreePathClick) {
        buildList {
            if (onSessionClick != null) {
                sessionMatches.forEach { match ->
                    add(LinkSpan(match.range.first, match.range.last + 1, TAG_SESSION, match.value))
                }
            }
            if (onWorktreePathClick != null) {
                pathMatches.forEach { match ->
                    add(LinkSpan(match.start, match.end, TAG_WORKTREE_PATH, match.path))
                }
            }
        }.sortedBy { it.start }.withoutOverlaps()
    }
    if (spans.isEmpty()) {
        Text(text = text, modifier = modifier)
        return
    }

    val linkColor = Color(0xFF2F80ED)
    val annotated = remember(text, spans) {
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                append(text.substring(cursor, span.start))
                pushStringAnnotation(span.tag, span.value)
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(text.substring(span.start, span.end))
                pop()
                pop()
                cursor = span.end
            }
            append(text.substring(cursor))
        }
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(TAG_SESSION, offset, offset).firstOrNull()?.let {
                onSessionClick?.invoke(it.item)
                return@ClickableText
            }
            annotated.getStringAnnotations(TAG_WORKTREE_PATH, offset, offset).firstOrNull()?.let {
                onWorktreePathClick?.invoke(it.item)
            }
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
private const val TAG_WORKTREE_PATH = "agor-worktree-path"
private const val SESSION_URI_PREFIX = "agor-session://"
private const val WORKTREE_PATH_URI_PREFIX = "agor-file://"
private const val MAX_PLAIN_TEXT_LINKIFY_LENGTH = 4_096

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

internal data class WorktreePathLink(
    val start: Int,
    val end: Int,
    val path: String,
)

private data class LinkSpan(
    val start: Int,
    val end: Int,
    val tag: String,
    val value: String,
)

private val worktreePathCandidateRegex = Regex(
    "(?<![A-Za-z0-9_:/.-])(?:\\.{1,2}/|/)?[A-Za-z0-9_~@+=%.-]+(?:/[A-Za-z0-9_~@+=%.-]+)+(?::\\d+(?::\\d+)?)?",
)

private val existingMarkdownLinkRegex = Regex("\\[[^\\]\\n]+]\\([^\\s)]+\\)")

private val commonRepositoryAnchors = listOf(
    "apps/",
    "packages/",
    "context/",
    "docs/",
    "docker/",
    "scripts/",
    "gradle/",
)

internal fun extractWorktreePathLinks(text: String): List<WorktreePathLink> {
    return worktreePathCandidateRegex.findAll(text).mapNotNull { match ->
        val normalized = normalizeWorktreePathCandidate(match.value) ?: return@mapNotNull null
        WorktreePathLink(match.range.first, match.range.last + 1, normalized)
    }.toList()
}

internal fun String.withWorktreePathLinks(): String {
    val existingLinks = existingMarkdownLinkRegex.findAll(this)
        .map { it.range.first until (it.range.last + 1) }
        .toList()
    val links = extractWorktreePathLinks(this)
        .filter { link -> existingLinks.none { protected -> link.start < protected.endInclusive + 1 && link.end > protected.first } }
        .withoutPathOverlaps()
    if (links.isEmpty()) return this

    return buildString {
        var cursor = 0
        for (link in links) {
            val linkStart = if (link.start > 0 && this@withWorktreePathLinks[link.start - 1] == '`' &&
                link.end < this@withWorktreePathLinks.length && this@withWorktreePathLinks[link.end] == '`'
            ) {
                link.start - 1
            } else {
                link.start
            }
            val linkEnd = if (linkStart != link.start) link.end + 1 else link.end
            if (linkStart < cursor) continue
            append(this@withWorktreePathLinks.substring(cursor, linkStart))
            val label = this@withWorktreePathLinks.substring(link.start, link.end)
            append("[$label]($WORKTREE_PATH_URI_PREFIX${link.path.encodeAgorUriPart()})")
            cursor = linkEnd
        }
        append(this@withWorktreePathLinks.substring(cursor))
    }
}

private fun normalizeWorktreePathCandidate(raw: String): String? {
    if (raw.contains("://")) return null
    var value = raw.trim().trimEnd('.', ',', ';', ')', ']', '}')
    value = value.replace(Regex(":\\d+(?::\\d+)?$"), "")
    value = value.removePrefix("./")
    while (value.startsWith("../")) value = value.removePrefix("../")
    if (value.startsWith("/")) {
        val withoutLeadingSlash = value.trimStart('/')
        val anchorIndex = commonRepositoryAnchors
            .mapNotNull { anchor ->
                val idx = withoutLeadingSlash.indexOf(anchor)
                if (idx >= 0) idx to anchor else null
            }
            .minByOrNull { it.first }
            ?.first
        value = if (anchorIndex != null) withoutLeadingSlash.substring(anchorIndex) else return null
    }

    if (!value.contains('/')) return null
    val hasRepositoryAnchor = commonRepositoryAnchors.any { value.startsWith(it) }
    val hasFileLikeTail = value.substringAfterLast('/').contains('.')
    if (!hasRepositoryAnchor && !hasFileLikeTail) return null
    return value.takeIf { it.isNotBlank() }
}

private fun List<LinkSpan>.withoutOverlaps(): List<LinkSpan> {
    val kept = mutableListOf<LinkSpan>()
    for (span in this) {
        if (kept.none { span.start < it.end && span.end > it.start }) {
            kept += span
        }
    }
    return kept
}

private fun List<WorktreePathLink>.withoutPathOverlaps(): List<WorktreePathLink> {
    val kept = mutableListOf<WorktreePathLink>()
    for (link in this) {
        if (kept.none { link.start < it.end && link.end > it.start }) {
            kept += link
        }
    }
    return kept
}

private fun String.encodeAgorUriPart(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.toString()).replace("+", "%20")

private fun String.decodeAgorUriPart(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.toString())

private fun agorUriHandler(
    delegate: UriHandler,
    onSessionClick: ((String) -> Unit)?,
    onWorktreePathClick: ((String) -> Unit)?,
): UriHandler = object : UriHandler {
    override fun openUri(uri: String) {
        if (uri.startsWith(SESSION_URI_PREFIX) && onSessionClick != null) {
            onSessionClick(uri.removePrefix(SESSION_URI_PREFIX))
            return
        }
        if (uri.startsWith(WORKTREE_PATH_URI_PREFIX) && onWorktreePathClick != null) {
            onWorktreePathClick(uri.removePrefix(WORKTREE_PATH_URI_PREFIX).decodeAgorUriPart())
            return
        }
        delegate.openUri(uri)
    }
}
