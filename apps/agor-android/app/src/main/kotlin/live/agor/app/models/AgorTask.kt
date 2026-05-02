package live.agor.app.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("stopped") STOPPED,
    @SerialName("timed_out") TIMED_OUT,
    @SerialName("awaiting_permission") AWAITING_PERMISSION,
    @SerialName("awaiting_input") AWAITING_INPUT,
}

@Immutable
@Serializable
data class AgorTask(
    @SerialName("task_id") val taskId: String,
    @SerialName("session_id") val sessionId: String,
    val status: TaskStatus,
    val prompt: String? = null,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    @SerialName("first_message_index") val firstMessageIndex: Int? = null,
    @SerialName("last_message_index") val lastMessageIndex: Int? = null,
)
