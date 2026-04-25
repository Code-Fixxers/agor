package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MCPServer(
    @SerialName("mcp_server_id") val mcpServerId: String,
    val name: String,
    val description: String? = null,
    val transport: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    @SerialName("oauth_authenticated") val oauthAuthenticated: Boolean? = null,
    @SerialName("requires_oauth") val requiresOauth: Boolean? = null,
)

@Serializable
data class ServerProfile(
    val id: String,
    val label: String,
    val url: String,
    val email: String? = null,
)
