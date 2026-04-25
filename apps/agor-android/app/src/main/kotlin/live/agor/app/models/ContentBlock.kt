package live.agor.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

/**
 * Polymorphic content block. Discriminator field is `type`.
 * Falls back to [ContentBlock.Unknown] for forward compatibility.
 */
@Serializable(with = ContentBlockSerializer::class)
sealed class ContentBlock {
    abstract val id: String

    data class Text(val text: String) : ContentBlock() {
        override val id: String = "text-${text.hashCode()}"
    }

    data class ToolUse(
        val toolUseId: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock() {
        override val id: String = "tool-$toolUseId"

        val inputSummary: String
            get() {
                val command = (input["command"] as? JsonPrimitive)?.contentOrNull
                if (!command.isNullOrEmpty()) {
                    return if (command.length > 100) command.take(100) + "..." else command
                }
                val path = (input["file_path"] as? JsonPrimitive)?.contentOrNull
                    ?: (input["path"] as? JsonPrimitive)?.contentOrNull
                if (!path.isNullOrEmpty()) return path
                val keys = input.keys.sorted().joinToString(", ")
                return if (keys.isEmpty()) "(no input)" else "{$keys}"
            }
    }

    data class ToolResult(
        val toolUseId: String,
        val content: ToolResultValue?,
        val isError: Boolean? = null,
    ) : ContentBlock() {
        override val id: String = "result-$toolUseId"
    }

    data class Thinking(val thinking: String?) : ContentBlock() {
        override val id: String = "thinking-${thinking?.hashCode() ?: 0}"
    }

    data class Image(val source: ImageSource) : ContentBlock() {
        override val id: String =
            "image-${source.url ?: source.data?.take(16) ?: "?"}"
    }

    data class Unknown(val type: String) : ContentBlock() {
        override val id: String = "unknown-$type"
    }
}

@Serializable
data class ImageSource(
    val type: String,
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
)

@Serializable(with = ToolResultValueSerializer::class)
sealed class ToolResultValue {
    data class Str(val text: String) : ToolResultValue()
    data class Blocks(val blocks: List<ToolResultBlock>) : ToolResultValue()

    val textPreview: String
        get() = when (this) {
            is Str -> text.trim().let { if (it.length > 200) it.take(200) + "..." else it }
            is Blocks -> blocks.mapNotNull { it.text }.joinToString("\n")
                .let { if (it.length > 200) it.take(200) + "..." else it }
        }
}

@Serializable
data class ToolResultBlock(
    val type: String? = null,
    val text: String? = null,
)

object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ContentBlock")

    override fun deserialize(decoder: Decoder): ContentBlock {
        val jd = decoder as? JsonDecoder ?: error("ContentBlock requires JSON")
        val element = jd.decodeJsonElement()
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: return ContentBlock.Unknown("missing")
        return when (type) {
            "text" -> ContentBlock.Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "tool_use" -> ContentBlock.ToolUse(
                toolUseId = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                input = obj["input"] as? JsonObject ?: JsonObject(emptyMap()),
            )
            "tool_result" -> ContentBlock.ToolResult(
                toolUseId = obj["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: "",
                content = obj["content"]?.let { jd.json.decodeFromJsonElement(ToolResultValueSerializer, it) },
                isError = (obj["is_error"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
            )
            "thinking" -> ContentBlock.Thinking(obj["thinking"]?.jsonPrimitive?.contentOrNull)
            "image" -> {
                val src = obj["source"] as? JsonObject
                if (src == null) {
                    ContentBlock.Image(ImageSource(type = "url"))
                } else {
                    ContentBlock.Image(jd.json.decodeFromJsonElement(ImageSource.serializer(), src))
                }
            }
            else -> ContentBlock.Unknown(type)
        }
    }

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        val je = encoder as? JsonEncoder ?: error("ContentBlock requires JSON")
        val element: JsonElement = when (value) {
            is ContentBlock.Text -> JsonObject(
                mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(value.text)),
            )
            is ContentBlock.ToolUse -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool_use"),
                    "id" to JsonPrimitive(value.toolUseId),
                    "name" to JsonPrimitive(value.name),
                    "input" to value.input,
                ),
            )
            is ContentBlock.ToolResult -> {
                val map = mutableMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("tool_result"),
                    "tool_use_id" to JsonPrimitive(value.toolUseId),
                )
                value.content?.let {
                    map["content"] = je.json.encodeToJsonElement(ToolResultValueSerializer, it)
                }
                value.isError?.let { map["is_error"] = JsonPrimitive(it) }
                JsonObject(map)
            }
            is ContentBlock.Thinking -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("thinking"),
                    "thinking" to (value.thinking?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)),
                ),
            )
            is ContentBlock.Image -> {
                val srcEl = je.json.encodeToJsonElement(ImageSource.serializer(), value.source)
                JsonObject(mapOf("type" to JsonPrimitive("image"), "source" to srcEl))
            }
            is ContentBlock.Unknown -> JsonObject(mapOf("type" to JsonPrimitive(value.type)))
        }
        je.encodeJsonElement(element)
    }
}

object ToolResultValueSerializer : KSerializer<ToolResultValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ToolResultValue")

    override fun deserialize(decoder: Decoder): ToolResultValue {
        val jd = decoder as? JsonDecoder ?: error("ToolResultValue requires JSON")
        val el = jd.decodeJsonElement()
        if (el is JsonPrimitive && el.isString) return ToolResultValue.Str(el.content)
        return runCatching {
            val list = jd.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ToolResultBlock.serializer()),
                el,
            )
            ToolResultValue.Blocks(list)
        }.getOrElse { ToolResultValue.Str(el.toString()) }
    }

    override fun serialize(encoder: Encoder, value: ToolResultValue) {
        val je = encoder as? JsonEncoder ?: error("ToolResultValue requires JSON")
        when (value) {
            is ToolResultValue.Str -> je.encodeJsonElement(JsonPrimitive(value.text))
            is ToolResultValue.Blocks -> je.encodeJsonElement(
                je.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ToolResultBlock.serializer()),
                    value.blocks,
                ),
            )
        }
    }
}
