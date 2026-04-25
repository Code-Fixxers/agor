package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Repo(
    @SerialName("repo_id") val repoId: String,
    val name: String,
    val url: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val path: String? = null,
)
