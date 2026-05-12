package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the daemon's streaming events on the messages service.
 * The daemon emits these as `messages streaming:<phase>`, `messages thinking:<phase>`.
 */

@Serializable
data class StreamingStartEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val index: Int? = null,
    val timestamp: String? = null,
)

@Serializable
data class StreamingChunkEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
    val text: String,
    val index: Int? = null,
)

@Serializable
data class StreamingEndEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
    val final: String? = null,
)

@Serializable
data class StreamingErrorEvent(
    @SerialName("session_id") val sessionId: String,
    val error: String,
)

@Serializable
data class ThinkingChunkEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
    val text: String,
)

@Serializable
data class ThinkingStartEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class ThinkingEndEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String? = null,
)
