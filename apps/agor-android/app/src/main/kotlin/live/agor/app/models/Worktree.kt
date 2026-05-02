package live.agor.app.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Worktree(
    @SerialName("worktree_id") val worktreeId: String,
    @SerialName("repo_id") val repoId: String,
    @SerialName("board_id") val boardId: String? = null,
    val name: String,
    val branch: String? = null,
    val path: String? = null,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    val archived: Boolean? = null,
    @SerialName("archived_reason") val archivedReason: String? = null,
    @SerialName("others_can") val othersCan: String? = null,
)
