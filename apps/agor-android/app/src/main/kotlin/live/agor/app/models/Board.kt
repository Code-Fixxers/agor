package live.agor.app.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Board(
    @SerialName("board_id") val boardId: String,
    val name: String,
    val description: String? = null,
    val emoji: String? = null,
    val color: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val archived: Boolean? = null,
)
