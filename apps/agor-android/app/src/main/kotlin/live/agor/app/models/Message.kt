package live.agor.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
}

@Serializable
enum class MessageType {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
    @SerialName("file-history-snapshot") FILE_HISTORY_SNAPSHOT,
    @SerialName("permission_request") PERMISSION_REQUEST,
    @SerialName("input_request") INPUT_REQUEST,
}

@Serializable
data class MessageTokens(val input: Int = 0, val output: Int = 0)

@Serializable
data class MessageMetadata(
    val model: String? = null,
    val tokens: MessageTokens? = null,
    val source: String? = null,
    @SerialName("original_id") val originalId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("is_meta") val isMeta: Boolean? = null,
)

@Serializable
data class ToolUseRef(
    val id: String,
    val name: String,
    val input: JsonObject = JsonObject(emptyMap()),
)

sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Blocks(val blocks: List<ContentBlock>) : MessageContent()
    data class Permission(val request: PermissionRequestContent) : MessageContent()
    data class InputRequest(val request: InputRequestContent) : MessageContent()
}

@Serializable(with = MessageSerializer::class)
data class Message(
    val messageId: String,
    val sessionId: String,
    val taskId: String? = null,
    val type: MessageType,
    val role: MessageRole,
    val index: Int,
    val timestamp: String,
    val contentPreview: String = "",
    val content: MessageContent,
    val toolUses: List<ToolUseRef>? = null,
    val parentToolUseId: String? = null,
    val status: String? = null,
    val metadata: MessageMetadata? = null,
) {
    val isPermissionRequest: Boolean get() = type == MessageType.PERMISSION_REQUEST
    val isInputRequest: Boolean get() = type == MessageType.INPUT_REQUEST
}

object MessageSerializer : KSerializer<Message> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Message")

    override fun deserialize(decoder: Decoder): Message {
        val jd = decoder as? JsonDecoder ?: error("Message requires JSON")
        val obj = jd.decodeJsonElement().jsonObject
        val type = jd.json.decodeFromJsonElement(MessageType.serializer(), obj["type"]!!)
        val role = jd.json.decodeFromJsonElement(MessageRole.serializer(), obj["role"]!!)
        val raw = obj["content"]
        val content: MessageContent = when (type) {
            MessageType.PERMISSION_REQUEST ->
                MessageContent.Permission(jd.json.decodeFromJsonElement(PermissionRequestContent.serializer(), raw!!))
            MessageType.INPUT_REQUEST ->
                MessageContent.InputRequest(jd.json.decodeFromJsonElement(InputRequestContent.serializer(), raw!!))
            else -> when {
                raw is JsonPrimitive && raw.isString -> MessageContent.Text(raw.content)
                raw == null -> MessageContent.Text("")
                else -> runCatching {
                    val blocks = jd.json.decodeFromJsonElement(
                        ListSerializer(ContentBlockSerializer),
                        raw,
                    )
                    MessageContent.Blocks(blocks)
                }.getOrElse {
                    MessageContent.Text(raw.toString())
                }
            }
        }
        val toolUsesEl = obj["tool_uses"] as? JsonElement
        val toolUses = toolUsesEl?.let {
            jd.json.decodeFromJsonElement(ListSerializer(ToolUseRef.serializer()), it)
        }
        val metaEl = obj["metadata"]
        val metadata = metaEl?.let {
            jd.json.decodeFromJsonElement(MessageMetadata.serializer(), it)
        }
        return Message(
            messageId = obj["message_id"]!!.jsonPrimitive.content,
            sessionId = obj["session_id"]!!.jsonPrimitive.content,
            taskId = (obj["task_id"] as? JsonPrimitive)?.contentOrNull,
            type = type,
            role = role,
            index = obj["index"]!!.jsonPrimitive.content.toInt(),
            timestamp = obj["timestamp"]!!.jsonPrimitive.content,
            contentPreview = (obj["content_preview"] as? JsonPrimitive)?.contentOrNull ?: "",
            content = content,
            toolUses = toolUses,
            parentToolUseId = (obj["parent_tool_use_id"] as? JsonPrimitive)?.contentOrNull,
            status = (obj["status"] as? JsonPrimitive)?.contentOrNull,
            metadata = metadata,
        )
    }

    override fun serialize(encoder: Encoder, value: Message) {
        val je = encoder as? JsonEncoder ?: error("Message requires JSON")
        val out = mutableMapOf<String, JsonElement>(
            "message_id" to JsonPrimitive(value.messageId),
            "session_id" to JsonPrimitive(value.sessionId),
            "type" to je.json.encodeToJsonElement(MessageType.serializer(), value.type),
            "role" to je.json.encodeToJsonElement(MessageRole.serializer(), value.role),
            "index" to JsonPrimitive(value.index),
            "timestamp" to JsonPrimitive(value.timestamp),
            "content_preview" to JsonPrimitive(value.contentPreview),
        )
        value.taskId?.let { out["task_id"] = JsonPrimitive(it) }
        value.parentToolUseId?.let { out["parent_tool_use_id"] = JsonPrimitive(it) }
        value.status?.let { out["status"] = JsonPrimitive(it) }
        value.metadata?.let { out["metadata"] = je.json.encodeToJsonElement(MessageMetadata.serializer(), it) }
        value.toolUses?.let {
            out["tool_uses"] = je.json.encodeToJsonElement(ListSerializer(ToolUseRef.serializer()), it)
        }
        out["content"] = when (val c = value.content) {
            is MessageContent.Text -> JsonPrimitive(c.text)
            is MessageContent.Blocks -> je.json.encodeToJsonElement(ListSerializer(ContentBlockSerializer), c.blocks)
            is MessageContent.Permission -> je.json.encodeToJsonElement(PermissionRequestContent.serializer(), c.request)
            is MessageContent.InputRequest -> je.json.encodeToJsonElement(InputRequestContent.serializer(), c.request)
        }
        je.encodeJsonElement(JsonObject(out))
    }
}
