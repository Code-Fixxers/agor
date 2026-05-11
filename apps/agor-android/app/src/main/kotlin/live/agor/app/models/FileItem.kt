package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileListItem(
    val path: String,
    val size: Long? = null,
    @SerialName("is_directory") val isDirectory: Boolean? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
)

@Serializable
data class FileDetail(
    val path: String,
    val content: String? = null,
    val base64: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val size: Long? = null,
    val truncated: Boolean? = null,
)

data class VirtualNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: MutableList<VirtualNode> = mutableListOf(),
    var size: Long? = null,
)
