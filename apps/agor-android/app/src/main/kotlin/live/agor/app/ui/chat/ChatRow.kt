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
 * This runs inside `remember(...)` keyed on the inputs, so it executes once per
 * state-update — *not* on every scroll-triggered recomposition.
 */
fun flattenChatRows(
    messages: List<Message>,
    tasks: List<AgorTask>,
    live: Map<String, StreamingService.StreamSnapshot>,
    showLoadEarlier: Boolean,
): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size + 8)
    if (showLoadEarlier) out += ChatRow.LoadEarlier

    val tasksById = tasks.associateByTo(HashMap(tasks.size)) { it.taskId }
    var lastTask: String? = SENTINEL_NO_TASK

    for (msg in messages) {
        if (msg.taskId != lastTask) {
            msg.taskId?.let { tasksById[it] }?.let { out += ChatRow.TaskHeaderRow(it) }
            lastTask = msg.taskId
        }

        val snap = live[msg.messageId]
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
    }

    // Streaming snapshots that aren't yet attached to a server-confirmed message.
    if (live.isNotEmpty()) {
        val seen = HashSet<String>(messages.size).apply {
            messages.forEach { add(it.messageId) }
        }
        for ((id, snap) in live) {
            if (id !in seen) {
                out += ChatRow.LiveOrphanRow("live-$id", snap.text, snap.thinking)
            }
        }
    }

    out += ChatRow.BottomSpacer
    return out
}

private const val SENTINEL_NO_TASK = "<__no_prev_task__>"
