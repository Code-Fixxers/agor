package live.agor.app.ui.mcp

import kotlinx.coroutines.runBlocking
import live.agor.app.models.MCPServer
import live.agor.app.models.SessionMCPServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionStateTest {
    @Test
    fun mapsServerRowsWithTransportEndpointAndOauthStatus() {
        val row = mcpServerRow(
            MCPServer(
                mcpServerId = "mcp-1",
                name = "github",
                description = "GitHub tools",
                transport = "http",
                url = "https://api.githubcopilot.com/mcp",
                requiresOauth = true,
                oauthAuthenticated = false,
            ),
        )

        assertEquals("mcp-1", row.id)
        assertEquals("github", row.title)
        assertEquals("HTTP - GitHub tools", row.subtitle)
        assertEquals("https://api.githubcopilot.com/mcp", row.detail)
        assertTrue(row.needsOauth)
        assertFalse(row.isAttached)
        assertTrue(row.isEnabled)
    }

    @Test
    fun mapsStdioCommandAsDetailAndAuthenticatedOauthAsReady() {
        val row = mcpServerRow(
            MCPServer(
                mcpServerId = "mcp-2",
                name = "filesystem",
                transport = "stdio",
                command = "npx",
                args = listOf("@modelcontextprotocol/server-filesystem", "/tmp"),
                requiresOauth = true,
                oauthAuthenticated = true,
            ),
        )

        assertEquals("STDIO", row.subtitle)
        assertEquals("npx @modelcontextprotocol/server-filesystem /tmp", row.detail)
        assertFalse(row.needsOauth)
    }

    @Test
    fun emptyContentReportsEmptyOnlyAfterLoadingWithoutError() {
        assertTrue(mcpSessionContent(workspaceServers = emptyList()).isEmpty)
        assertFalse(mcpSessionContent(workspaceServers = emptyList(), isLoading = true).isEmpty)
        assertFalse(mcpSessionContent(workspaceServers = emptyList(), errorMessage = "Nope").isEmpty)
    }

    @Test
    fun errorContentDoesNotExposeStaleRows() {
        val content = mcpSessionContent(
            workspaceServers = listOf(MCPServer(mcpServerId = "mcp-1", name = "github")),
            errorMessage = "HTTP 500",
        )

        assertEquals("HTTP 500", content.errorMessage)
        assertTrue(content.rows.isEmpty())
        assertFalse(content.isEmpty)
    }

    @Test
    fun workspaceServerLoaderSortsByNameThenId() = runBlocking {
        val loaded = loadSessionMcpState(
            fetchWorkspaceServers = {
                listOf(
                    MCPServer(mcpServerId = "mcp-3", name = "sentry"),
                    MCPServer(mcpServerId = "mcp-2", name = "github"),
                    MCPServer(mcpServerId = "mcp-1", name = "GitHub"),
                )
            },
            fetchSessionServers = { emptyList() },
        )

        assertEquals(listOf("mcp-1", "mcp-2", "mcp-3"), loaded.workspaceServers.map { it.mcpServerId })
    }

    @Test
    fun joinsWorkspaceServersWithActiveSessionRelationships() {
        val content = mcpSessionContent(
            workspaceServers = listOf(
                MCPServer(mcpServerId = "mcp-2", name = "sentry", transport = "http"),
                MCPServer(mcpServerId = "mcp-1", name = "github", transport = "stdio"),
            ),
            sessionServers = listOf(
                SessionMCPServer(sessionId = "session-1", mcpServerId = "mcp-1", enabled = false),
            ),
        )

        assertEquals(listOf("mcp-1", "mcp-2"), content.rows.map { it.id })
        assertTrue(content.rows[0].isAttached)
        assertFalse(content.rows[0].isEnabled)
        assertEquals("Disabled for session", content.rows[0].statusLabel)
        assertFalse(content.rows[1].isAttached)
        assertEquals("Available", content.rows[1].statusLabel)
    }

    @Test
    fun keepsMissingAttachedServersVisibleAsUnavailableRows() {
        val content = mcpSessionContent(
            workspaceServers = emptyList(),
            sessionServers = listOf(
                SessionMCPServer(sessionId = "session-1", mcpServerId = "1234567890", enabled = true),
            ),
        )

        assertEquals(1, content.rows.size)
        assertEquals("12345678", content.rows[0].title)
        assertTrue(content.rows[0].isAttached)
        assertTrue(content.rows[0].isEnabled)
        assertEquals("Attached server unavailable", content.rows[0].subtitle)
        assertEquals("Unknown workspace server", content.rows[0].detail)
    }

    @Test
    fun sessionServerLoaderSortsAndJoinsBothSources() = runBlocking {
        val loaded = loadSessionMcpState(
            fetchWorkspaceServers = {
                listOf(
                    MCPServer(mcpServerId = "mcp-2", name = "sentry"),
                    MCPServer(mcpServerId = "mcp-1", name = "github"),
                )
            },
            fetchSessionServers = {
                listOf(SessionMCPServer(sessionId = "session-1", mcpServerId = "mcp-2", enabled = true))
            },
        )

        val content = mcpSessionContent(
            workspaceServers = loaded.workspaceServers,
            sessionServers = loaded.sessionServers,
        )

        assertEquals(listOf("mcp-1", "mcp-2"), loaded.workspaceServers.map { it.mcpServerId })
        assertEquals(listOf("mcp-2"), loaded.sessionServers.map { it.mcpServerId })
        assertEquals(listOf(false, true), content.rows.map { it.isAttached })
    }

    @Test
    fun blankServerNameFallsBackToShortId() {
        val row = mcpServerRow(MCPServer(mcpServerId = "1234567890", name = ""))

        assertEquals("12345678", row.title)
        assertEquals("MCP", row.subtitle)
        assertNull(row.detail)
    }
}
