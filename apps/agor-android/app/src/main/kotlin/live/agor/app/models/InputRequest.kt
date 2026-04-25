package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class InputRequestQuestion(
    val question: String,
    val kind: InputRequestKind = InputRequestKind.FREE_TEXT,
    val options: List<String>? = null,
)

@Serializable
data class InputRequestContent(
    @SerialName("input_request_id") val inputRequestId: String,
    val questions: List<InputRequestQuestion> = emptyList(),
    val status: InputRequestStatus = InputRequestStatus.PENDING,
    val answers: List<String>? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    val context: String? = null,
)
