package live.agor.app.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.models.MCPServer
import live.agor.app.models.SessionMCPServer
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

class McpSessionViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(mcpSessionContent(emptyList(), isLoading = true))
    val state: StateFlow<McpSessionContent> = _state.asStateFlow()

    private var lastLoaded = McpSessionStateData(emptyList(), emptyList())

    fun load() {
        viewModelScope.launch {
            _state.value = mcpSessionContent(emptyList(), isLoading = true)
            runCatching {
                loadSessionMcpState(
                    fetchWorkspaceServers = { container.client.listMcpServers() },
                    fetchSessionServers = { container.client.listSessionMcpServers(sessionId) },
                )
            }.onSuccess { loaded ->
                lastLoaded = loaded
                _state.value = mcpSessionContent(loaded.workspaceServers, loaded.sessionServers)
            }.onFailure { t ->
                showFailure("MCP server list failed", t)
            }
        }
    }

    fun addServer(serverId: String) {
        mutate(serverId) { container.client.addSessionMcpServer(sessionId, serverId) }
    }

    fun removeServer(serverId: String) {
        mutate(serverId) { container.client.removeSessionMcpServer(sessionId, serverId) }
    }

    fun setServerEnabled(serverId: String, enabled: Boolean) {
        mutate(serverId) { container.client.setSessionMcpServerEnabled(sessionId, serverId, enabled) }
    }

    private fun mutate(serverId: String, call: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = mcpSessionContent(
                workspaceServers = lastLoaded.workspaceServers,
                sessionServers = lastLoaded.sessionServers,
                mutatingServerId = serverId,
            )
            runCatching {
                call()
                loadSessionMcpState(
                    fetchWorkspaceServers = { container.client.listMcpServers() },
                    fetchSessionServers = { container.client.listSessionMcpServers(sessionId) },
                )
            }.onSuccess { loaded ->
                lastLoaded = loaded
                _state.value = mcpSessionContent(loaded.workspaceServers, loaded.sessionServers)
            }.onFailure { t ->
                showFailure("MCP server update failed", t)
            }
        }
    }

    private fun showFailure(prefix: String, t: Throwable) {
        AppLogger.log(
            "$prefix for session=${sessionId.take(8)}: ${t.message}",
            LogLevel.WARNING,
            "MCP",
        )
        _state.value = mcpSessionContent(
            workspaceServers = lastLoaded.workspaceServers,
            sessionServers = lastLoaded.sessionServers,
            errorMessage = t.message ?: "Unable to update MCP servers",
        )
    }
}

internal suspend fun loadSessionMcpState(
    fetchWorkspaceServers: suspend () -> List<MCPServer>,
    fetchSessionServers: suspend () -> List<SessionMCPServer>,
): McpSessionStateData =
    McpSessionStateData(
        workspaceServers = fetchWorkspaceServers()
            .sortedWith(compareBy<MCPServer> { it.name.lowercase() }.thenBy { it.mcpServerId }),
        sessionServers = fetchSessionServers().sortedBy { it.mcpServerId },
    )
