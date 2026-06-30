const fs = require('node:fs');
const filepath = 'apps/agor-daemon/src/register-hooks.ts';
let code = fs.readFileSync(filepath, 'utf8');

const searchBlock = `    // Fetch tokens in batch and build a map keyed by server ID
    const tokensMap = new Map<MCPServerID, import('@agor/core/db').UserMCPOAuthToken>();
    const userTokenRepo = new UserMCPOAuthTokenRepository(db);
    if (mcpServerIds.length > 0) {
      const typedUserId = userId as import('@agor/core/types').UserID;
      const tokens = await userTokenRepo.getTokensForServers(typedUserId, mcpServerIds);
      for (const token of tokens) {
        // Since getTokensForServers returns both per-user and shared-mode tokens,
        // we map it by mcp_server_id. If multiple modes theoretically exist,
        // we prefer per-user (user_id !== null).
        const existing = tokensMap.get(token.mcp_server_id);
        if (!existing || token.user_id !== null) {
          tokensMap.set(token.mcp_server_id, token);
        }
      }
    }

    const injectToken = async (server: MCPServer) => {
      if (server.auth?.type !== 'oauth') {
        return server;
      }

      // Tokens for both modes live in user_mcp_oauth_tokens:
      //   - per_user  → row keyed by (userId, serverId)
      //   - shared    → row keyed by (NULL, serverId)
      const mode = server.auth.oauth_mode ?? 'per_user';
      const tokenUserId: import('@agor/core/types').UserID | null =
        mode === 'per_user' ? (userId as import('@agor/core/types').UserID) : null;

      try {
        const row = tokensMap.get(server.mcp_server_id as MCPServerID);
        // Only use the token if it matches the expected user_id mode for this server
        if (!row || row.user_id !== tokenUserId) {
          console.log(
            \`[MCP OAuth] No token row for user=\${tokenUserId ?? '<shared>'} server=\${server.name}\`
          );
          return server;
        }`;

const replaceBlock = `    // Fetch tokens in batch and build a map keyed by server ID and mode
    const tokensMap = new Map<string, import('@agor/core/db').UserMCPOAuthToken>();
    const userTokenRepo = new UserMCPOAuthTokenRepository(db);
    if (mcpServerIds.length > 0) {
      const typedUserId = (userId as import('@agor/core/types').UserID) ?? null;
      const tokens = await userTokenRepo.getTokensForServers(typedUserId, mcpServerIds);
      for (const token of tokens) {
        const key = \`\${token.mcp_server_id}:\${token.user_id ?? 'shared'}\`;
        tokensMap.set(key, token);
      }
    }

    const injectToken = async (server: MCPServer) => {
      if (server.auth?.type !== 'oauth') {
        return server;
      }

      // Tokens for both modes live in user_mcp_oauth_tokens:
      //   - per_user  → row keyed by (userId, serverId)
      //   - shared    → row keyed by (NULL, serverId)
      const mode = server.auth.oauth_mode ?? 'per_user';
      const tokenUserId: import('@agor/core/types').UserID | null =
        mode === 'per_user' ? (userId as import('@agor/core/types').UserID) : null;

      try {
        const key = \`\${server.mcp_server_id}:\${tokenUserId ?? 'shared'}\`;
        const row = tokensMap.get(key);
        if (!row) {
          console.log(
            \`[MCP OAuth] No token row for user=\${tokenUserId ?? '<shared>'} server=\${server.name}\`
          );
          return server;
        }`;

if (!code.includes(searchBlock)) {
  console.log('Search block not found.');
} else {
  code = code.replace(searchBlock, replaceBlock);
  fs.writeFileSync(filepath, code);
  console.log('Successfully applied code review fixes.');
}
