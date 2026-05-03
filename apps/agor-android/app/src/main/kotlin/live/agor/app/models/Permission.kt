package live.agor.app.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class PermissionStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("timed_out") TIMED_OUT,
}

@Serializable
@Immutable
data class PermissionRequestContent(
    @SerialName("permission_id") val permissionId: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonObject = JsonObject(emptyMap()),
    val description: String? = null,
    val status: PermissionStatus = PermissionStatus.PENDING,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("decision_note") val decisionNote: String? = null,
)
