package live.agor.app.models

import androidx.compose.runtime.Immutable
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class InputRequestKind {
    @SerialName("free_text") FREE_TEXT,
    @SerialName("single_choice") SINGLE_CHOICE,
    @SerialName("multi_choice") MULTI_CHOICE,
}

@Serializable
enum class InputRequestStatus {
    @SerialName("pending") PENDING,
    @SerialName("answered") ANSWERED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("timed_out") TIMED_OUT,
}

@Serializable
@Immutable
data class InputRequestOption(
    val label: String,
    val description: String = "",
    val markdown: String? = null,
)

@Serializable(with = InputRequestQuestionSerializer::class)
@Immutable
data class InputRequestQuestion(
    val question: String,
    val header: String? = null,
    val kind: InputRequestKind = InputRequestKind.FREE_TEXT,
    val options: List<InputRequestOption>? = null,
    val multiSelect: Boolean? = null,
)

@Serializable
@Immutable
data class InputRequestContent(
    @SerialName("request_id") val requestId: String = "",
    @SerialName("input_request_id") val legacyInputRequestId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val questions: List<InputRequestQuestion> = emptyList(),
    val status: InputRequestStatus = InputRequestStatus.PENDING,
    val answers: Map<String, String>? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    val context: String? = null,
) {
    val inputRequestId: String
        get() = requestId.ifBlank { legacyInputRequestId.orEmpty() }
}

object InputRequestQuestionSerializer : KSerializer<InputRequestQuestion> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("InputRequestQuestion")

    override fun deserialize(decoder: Decoder): InputRequestQuestion {
        val jd = decoder as? JsonDecoder ?: error("InputRequestQuestion requires JSON")
        val obj = jd.decodeJsonElement().jsonObject
        val kind = obj["kind"]?.let {
            jd.json.decodeFromJsonElement(InputRequestKind.serializer(), it)
        } ?: InputRequestKind.FREE_TEXT
        val options = (obj["options"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { option ->
            when (option) {
                is JsonPrimitive -> InputRequestOption(option.contentOrNull.orEmpty())
                is JsonObject -> InputRequestOption(
                    label = option["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = option["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    markdown = option["markdown"]?.jsonPrimitive?.contentOrNull,
                )
                else -> null
            }
        }
        return InputRequestQuestion(
            question = obj["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            header = obj["header"]?.jsonPrimitive?.contentOrNull,
            kind = kind,
            options = options,
            multiSelect = obj["multiSelect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
        )
    }

    override fun serialize(encoder: Encoder, value: InputRequestQuestion) {
        val je = encoder as? JsonEncoder ?: error("InputRequestQuestion requires JSON")
        val map = buildMap<String, JsonElement> {
            put("question", JsonPrimitive(value.question))
            value.header?.let { put("header", JsonPrimitive(it)) }
            put("kind", je.json.encodeToJsonElement(InputRequestKind.serializer(), value.kind))
            value.options?.let {
                put("options", je.json.encodeToJsonElement(ListSerializer(InputRequestOption.serializer()), it))
            }
            value.multiSelect?.let { put("multiSelect", JsonPrimitive(it)) }
        }
        je.encodeJsonElement(JsonObject(map))
    }
}
