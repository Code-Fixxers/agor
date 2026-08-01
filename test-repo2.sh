<<<<<<< SEARCH
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
        const userTokenRepo = new UserMCPOAuthTokenRepository(db);
        const row = await userTokenRepo.getToken(tokenUserId, server.mcp_server_id);
=======
    // Handle both single result and array/paginated results
    const servers = Array.isArray(context.result)
      ? context.result
      : context.result?.data && Array.isArray(context.result.data)
        ? context.result.data
        : context.result?.mcp_server_id
          ? [context.result]
          : [];

    const serverIds = servers
      .filter((s: MCPServer) => s.auth?.type === 'oauth')
      .map((s: MCPServer) => s.mcp_server_id);

    const userTokenRepo = new UserMCPOAuthTokenRepository(db);
    const tokenMap = serverIds.length > 0
      ? await userTokenRepo.getTokensForServers(userId as import('@agor/core/types').UserID, serverIds)
      : new Map();

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
        const row = tokenMap.get(`${server.mcp_server_id}:${tokenUserId || ''}`);
>>>>>>> REPLACE
