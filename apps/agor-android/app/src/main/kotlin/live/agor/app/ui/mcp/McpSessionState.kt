package live.agor.app.ui.mcp

import live.agor.app.models.MCPServer
import live.agor.app.models.SessionMCPServer

data class McpServerRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val detail: String?,
    val needsOauth: Boolean,
    val isAttached: Boolean,
    val isEnabled: Boolean,
    val statusLabel: String,
    val isMutating: Boolean = false,
)

data class McpSessionContent(
    val isLoading: Boolean,
    val errorMessage: String?,
    val rows: List<McpServerRow>,
    val mutatingServerId: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && rows.isEmpty()
}

data class McpSessionStateData(
    val workspaceServers: List<MCPServer>,
    val sessionServers: List<SessionMCPServer>,
)

internal fun mcpSessionContent(
    workspaceServers: List<MCPServer>,
    sessionServers: List<SessionMCPServer> = emptyList(),
    isLoading: Boolean = false,
    errorMessage: String? = null,
    mutatingServerId: String? = null,
): McpSessionContent = McpSessionContent(
    isLoading = isLoading,
    errorMessage = errorMessage,
    rows = if (errorMessage == null) {
        mcpServerRows(workspaceServers, sessionServers, mutatingServerId)
    } else {
        emptyList()
    },
    mutatingServerId = mutatingServerId,
)

private fun mcpServerRows(
    workspaceServers: List<MCPServer>,
    sessionServers: List<SessionMCPServer>,
    mutatingServerId: String?,
): List<McpServerRow> {
    val attachedById = sessionServers.associateBy { it.mcpServerId }
    val workspaceRows = workspaceServers
        .sortedWith(compareBy<MCPServer> { it.name.lowercase() }.thenBy { it.mcpServerId })
        .map { server ->
            mcpServerRow(
                server = server,
                attachment = attachedById[server.mcpServerId],
                isMutating = mutatingServerId == server.mcpServerId,
            )
        }
    val workspaceIds = workspaceServers.mapTo(mutableSetOf()) { it.mcpServerId }
    val missingAttachedRows = sessionServers
        .filter { it.mcpServerId !in workspaceIds }
        .sortedBy { it.mcpServerId }
        .map { attachment ->
            missingMcpServerRow(attachment, isMutating = mutatingServerId == attachment.mcpServerId)
        }
    return workspaceRows + missingAttachedRows
}

internal fun mcpServerRow(
    server: MCPServer,
    attachment: SessionMCPServer? = null,
    isMutating: Boolean = false,
): McpServerRow {
    val transport = server.transport?.takeIf { it.isNotBlank() }?.uppercase() ?: "MCP"
    val endpoint = when {
        !server.url.isNullOrBlank() -> server.url
        !server.command.isNullOrBlank() -> buildString {
            append(server.command)
            val args = server.args.orEmpty().filter { it.isNotBlank() }
            if (args.isNotEmpty()) append(" ").append(args.joinToString(" "))
        }
        else -> null
    }
    return McpServerRow(
        id = server.mcpServerId,
        title = server.name.ifBlank { server.mcpServerId.take(8) },
        subtitle = listOfNotNull(transport, server.description?.takeIf { it.isNotBlank() }).joinToString(" - "),
        detail = endpoint,
        needsOauth = server.requiresOauth == true && server.oauthAuthenticated != true,
        isAttached = attachment != null,
        isEnabled = attachment?.enabled ?: true,
        statusLabel = when {
            attachment == null -> "Available"
            attachment.enabled -> "Enabled for session"
            else -> "Disabled for session"
        },
        isMutating = isMutating,
    )
}

private fun missingMcpServerRow(attachment: SessionMCPServer, isMutating: Boolean): McpServerRow =
    McpServerRow(
        id = attachment.mcpServerId,
        title = attachment.mcpServerId.take(8),
        subtitle = "Attached server unavailable",
        detail = "Unknown workspace server",
        needsOauth = false,
        isAttached = true,
        isEnabled = attachment.enabled,
        statusLabel = if (attachment.enabled) "Enabled for session" else "Disabled for session",
        isMutating = isMutating,
    )
