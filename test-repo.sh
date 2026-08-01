<<<<<<< SEARCH
    // Handle both single result and array/paginated results
    if (Array.isArray(context.result)) {
      context.result = await Promise.all(context.result.map(injectToken));
    } else if (context.result?.data && Array.isArray(context.result.data)) {
      context.result.data = await Promise.all(context.result.data.map(injectToken));
    } else if (context.result?.mcp_server_id) {
      context.result = await injectToken(context.result);
    }
=======
    // Handle both single result and array/paginated results
    const servers = Array.isArray(context.result)
      ? context.result
      : context.result?.data && Array.isArray(context.result.data)
        ? context.result.data
        : context.result?.mcp_server_id
          ? [context.result]
          : [];

    if (servers.length > 0) {
      const serverIds = servers
        .filter((s: MCPServer) => s.auth?.type === 'oauth')
        .map((s: MCPServer) => s.mcp_server_id);

      const userTokenRepo = new UserMCPOAuthTokenRepository(db);
      const tokenMap = serverIds.length > 0 ? await userTokenRepo.getTokensForServers(userId as import('@agor/core/types').UserID, serverIds) : new Map();

      const injectToken = async (server: MCPServer) => {
        if (server.auth?.type !== 'oauth') {
          return server;
        }

        const mode = server.auth.oauth_mode ?? 'per_user';
        const tokenUserId = mode === 'per_user' ? userId : null;
        const row = tokenMap.get(`${server.mcp_server_id}:${tokenUserId || ''}`);

        if (!row) {
          console.log(
            `[MCP OAuth] No token row for user=${tokenUserId ?? '<shared>'} server=${server.name}`
          );
          return server;
        }

        try {
          // JIT refresh — see `refreshAndPersistToken` for mutexing + invalid_grant cleanup.
          let accessToken = row.oauth_access_token;
          let expiresAt = row.oauth_token_expires_at;
          const { needsRefresh, refreshAndPersistToken, InvalidGrantError } = await import(
            '@agor/core/tools/mcp/oauth-refresh'
          );
          if (needsRefresh(row.oauth_token_expires_at) && row.oauth_refresh_token) {
            console.log(`[MCP OAuth] Token near/past expiry for ${server.name} — refreshing`);
            try {
              accessToken = await refreshAndPersistToken({
                db,
                userId: tokenUserId as import('@agor/core/types').UserID | null,
                mcpServerId: server.mcp_server_id,
              });
              // Re-read to pick up the rotated expiry for the UI.
              const fresh = await userTokenRepo.getToken(tokenUserId as import('@agor/core/types').UserID | null, server.mcp_server_id);
              if (fresh) expiresAt = fresh.oauth_token_expires_at;
            } catch (refreshErr) {
              if (refreshErr instanceof InvalidGrantError) {
                console.warn(
                  `[MCP OAuth] invalid_grant refreshing ${server.name} — user must re-auth`
                );
                return server;
              }
              // Transient error: fall through with the stale access_token. The
              // MCP call may still succeed or fail cleanly at the transport.
              console.warn(
                `[MCP OAuth] Refresh failed for ${server.name} (using stale token):`,
                refreshErr instanceof Error ? refreshErr.message : refreshErr
              );
            }
          }

          return {
            ...server,
            auth: {
              ...server.auth,
              oauth_access_token: accessToken,
              oauth_token_expires_at:
                expiresAt instanceof Date ? expiresAt.getTime() : (expiresAt ?? undefined),
            },
          };
        } catch (error) {
          console.warn(
            `[MCP OAuth] Failed to resolve OAuth token for ${server.name}:`,
            error instanceof Error ? error.message : error
          );
        }

        return server;
      };

      if (Array.isArray(context.result)) {
        context.result = await Promise.all(context.result.map(injectToken));
      } else if (context.result?.data && Array.isArray(context.result.data)) {
        context.result.data = await Promise.all(context.result.data.map(injectToken));
      } else if (context.result?.mcp_server_id) {
        context.result = await injectToken(context.result);
      }
    }
>>>>>>> REPLACE
