package live.agor.app.ui.chat

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import live.agor.app.models.AgorTask
import live.agor.app.models.ContentBlock
import live.agor.app.models.ImageSource
import live.agor.app.models.InputRequestContent
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.MessageRole
import live.agor.app.models.PermissionRequestContent
import live.agor.app.models.ToolResultValue
import live.agor.app.network.AgorJson
import live.agor.app.network.StreamingService

/**
 * Single rendering unit for the chat LazyColumn.
 *
 * Critical: every variant is a `data class` (or `data object`). With `class`,
 * Kotlin generates identity-based `equals`, and Compose smart-skip uses
 * `equals` to decide whether a parameter changed. Since [flattenChatRows]
 * allocates fresh row instances on every emission, identity equality means
 * no row is ever "equal" to its predecessor and *every visible row*
 * recomposes per streaming chunk — about 10× per second. `data class` gives
 * us structural equals, so unchanged rows compare equal and Compose actually
 * skips them; only the bubble whose `text` changed recomposes.
 *
 * The parent is a `sealed interface` (not `sealed class`) because a `data
 * class` child can't pass a `key` up through a parent's primary constructor —
 * the property has to live on the child. Each variant declares
 * `override val key`.
 */
@Immutable
sealed interface ChatRow {
    val key: String

    @Immutable
    data object LoadEarlier : ChatRow {
        override val key: String = "load-earlier"
    }

    @Immutable
    data class TaskHeaderRow(val task: AgorTask) : ChatRow {
        override val key: String get() = "task-${task.taskId}"
    }

    @Immutable
    data class TextBubbleRow(
        override val key: String,
        val role: MessageRole,
        val text: String,
        val streaming: Boolean,
    ) : ChatRow

    @Immutable
    data class ToolUseRow(
        override val key: String,
        val name: String,
        val inputSummary: String,
        val inputJson: String,
    ) : ChatRow

    @Immutable
    data class ToolResultRow(
        override val key: String,
        val isError: Boolean,
        val preview: String,
        val full: String,
    ) : ChatRow

    @Immutable
    data class ThinkingRow(override val key: String, val text: String) : ChatRow

    @Immutable
    data class ImageRow(override val key: String, val source: ImageSource) : ChatRow

    @Immutable
    data class PermissionRow(
        override val key: String,
        val messageId: String,
        val request: PermissionRequestContent,
    ) : ChatRow

    @Immutable
    data class InputRequestRow(
        override val key: String,
        val messageId: String,
        val request: InputRequestContent,
    ) : ChatRow

    @Immutable
    data class LiveOrphanRow(
        override val key: String,
        val text: String,
        val thinking: String,
    ) : ChatRow

    @Immutable
    data object BottomSpacer : ChatRow {
        override val key: String = "bottom-spacer"
    }
}

/**
 * Flatten a chat snapshot into a stable, render-ready row list.
 *
 * Stateless compatibility wrapper. ChatViewModel owns a [ChatRowFlattener] so
 * row object identities survive across emissions; callers that do not need
 * caching can still use this helper.
 */
fun flattenChatRows(
    messages: List<Message>,
    tasks: List<AgorTask>,
    live: Map<String, StreamingService.StreamSnapshot>,
    showLoadEarlier: Boolean,
): List<ChatRow> {
    return ChatRowFlattener().flatten(messages, tasks, live, showLoadEarlier)
}

/**
 * Stateful row flattener owned by ChatViewModel.
 *
 * The previous pre-flattening pass still rebuilt every row on every live stream
 * emission. That moved JSON/string work off Main, but it still handed Compose a
 * fresh object graph ~10 times per second. Data-class equality then had to walk
 * large tool strings to prove unchanged rows were unchanged. This cache keeps
 * row object identity stable for every message whose canonical message and live
 * snapshot did not change, making Compose's skip checks effectively O(1).
 */
class ChatRowFlattener {
    private data class MessageCacheEntry(
        val message: Message,
        val snapshot: StreamingService.StreamSnapshot?,
        val rows: List<ChatRow>,
    )

    private data class OrphanCacheEntry(
        val snapshot: StreamingService.StreamSnapshot,
        val row: ChatRow.LiveOrphanRow,
    )

    private val messageRows = HashMap<String, MessageCacheEntry>()
    private val orphanRows = HashMap<String, OrphanCacheEntry>()

    fun flatten(
        messages: List<Message>,
        tasks: List<AgorTask>,
        live: Map<String, StreamingService.StreamSnapshot>,
        showLoadEarlier: Boolean,
    ): List<ChatRow> {
        val out = ArrayList<ChatRow>(messages.size * 2 + live.size + 8)
        if (showLoadEarlier) out += ChatRow.LoadEarlier

        val tasksById = tasks.associateByTo(HashMap(tasks.size)) { it.taskId }
        val seen = HashSet<String>(messages.size)
        var lastTask: String? = SENTINEL_NO_TASK

        for (msg in messages) {
            seen += msg.messageId
            if (msg.taskId != lastTask) {
                msg.taskId?.let { tasksById[it] }?.let { out += ChatRow.TaskHeaderRow(it) }
                lastTask = msg.taskId
            }
            out += rowsForMessage(msg, live[msg.messageId])
        }

        if (live.isNotEmpty()) {
            for ((id, snap) in live) {
                if (id !in seen) out += rowForOrphan(id, snap)
            }
        }

        messageRows.keys.removeAll { it !in seen }
        orphanRows.keys.removeAll { it !in live.keys || it in seen }

        out += ChatRow.BottomSpacer
        return out
    }

    private fun rowsForMessage(
        msg: Message,
        snap: StreamingService.StreamSnapshot?,
    ): List<ChatRow> {
        messageRows[msg.messageId]?.let { cached ->
            if (cached.message == msg && cached.snapshot == snap) return cached.rows
        }
        val rows = buildMessageRows(msg, snap)
        messageRows[msg.messageId] = MessageCacheEntry(msg, snap, rows)
        return rows
    }

    private fun rowForOrphan(
        id: String,
        snap: StreamingService.StreamSnapshot,
    ): ChatRow.LiveOrphanRow {
        orphanRows[id]?.let { cached ->
            if (cached.snapshot == snap) return cached.row
        }
        val row = ChatRow.LiveOrphanRow("live-$id", snap.text, snap.thinking)
        orphanRows[id] = OrphanCacheEntry(snap, row)
        return row
    }
}

private fun buildMessageRows(
    msg: Message,
    snap: StreamingService.StreamSnapshot?,
): List<ChatRow> {
    val out = ArrayList<ChatRow>(8)
    val streaming = snap != null && !snap.finished
    val mid = msg.messageId

    when (val c = msg.content) {
        is MessageContent.Text -> {
            val text = snap?.text?.takeIf { it.isNotEmpty() } ?: c.text
            if (text.isNotEmpty()) {
                out += ChatRow.TextBubbleRow("text-$mid", msg.role, text, streaming)
            }
        }

        is MessageContent.Blocks -> {
            val blocks = c.blocks
            blocks.forEachIndexed { i, block ->
                when (block) {
                    is ContentBlock.Text -> {
                        val isLastTextBlock = (i == blocks.size - 1)
                        val resolved =
                            if (isLastTextBlock && snap != null && snap.text.isNotEmpty()) snap.text
                            else block.text
                        if (resolved.isNotEmpty()) {
                            out += ChatRow.TextBubbleRow(
                                key = "text-$mid-$i",
                                role = msg.role,
                                text = resolved,
                                streaming = isLastTextBlock && streaming,
                            )
                        }
                    }

                    is ContentBlock.ToolUse -> {
                        // JSON serialization happens once here, not per-recompose.
                        val inputJson = AgorJson.encodeToString(JsonObject.serializer(), block.input)
                        out += ChatRow.ToolUseRow(
                            key = "tool-$mid-$i",
                            name = block.name,
                            inputSummary = block.inputSummary,
                            inputJson = inputJson,
                        )
                    }

                    is ContentBlock.ToolResult -> {
                        val full = when (val v = block.content) {
                            is ToolResultValue.Str -> v.text
                            is ToolResultValue.Blocks ->
                                v.blocks.asSequence().mapNotNull { it.text }.joinToString("\n")
                            null -> ""
                        }
                        out += ChatRow.ToolResultRow(
                            key = "result-$mid-$i",
                            isError = block.isError == true,
                            preview = block.content?.textPreview.orEmpty(),
                            full = full,
                        )
                    }

                    is ContentBlock.Thinking -> {
                        val isLastBlock = (i == blocks.size - 1)
                        val text =
                            if (isLastBlock && !snap?.thinking.isNullOrEmpty()) snap?.thinking.orEmpty()
                            else block.thinking.orEmpty()
                        if (text.isNotEmpty()) {
                            out += ChatRow.ThinkingRow("think-$mid-$i", text)
                        }
                    }

                    is ContentBlock.Image ->
                        out += ChatRow.ImageRow("img-$mid-$i", block.source)

                    is ContentBlock.Unknown -> Unit
                }
            }
        }

        is MessageContent.Permission ->
            out += ChatRow.PermissionRow("perm-$mid", mid, c.request)

        is MessageContent.InputRequest ->
            out += ChatRow.InputRequestRow("input-$mid", mid, c.request)
    }

    return out
}

private const val SENTINEL_NO_TASK = "<__no_prev_task__>"
