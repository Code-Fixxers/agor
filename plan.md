1. **Understand the problem**:
   - In `apps/agor-daemon/src/register-hooks.ts`, when returning a list of MCP servers, the `injectPerUserOAuthTokens` hook loops over the result and calls `injectToken` for each server via `Promise.all(context.result.map(injectToken))`.
   - Each `injectToken` call instantiates `UserMCPOAuthTokenRepository` and queries `userTokenRepo.getToken(tokenUserId, server.mcp_server_id)`.
   - If there are multiple MCP servers using OAuth (either shared or per-user), this results in an N+1 query problem, making a separate DB query for each server's token.

2. **Develop the solution**:
   - We need to fetch all relevant tokens for the list of servers in one batched query to fix the N+1 issue.
   - We should add a new method to `UserMCPOAuthTokenRepository` (e.g., `getTokensForServers(userId: UserID | null, serverIds: MCPServerID[])`) to perform a batched query using Drizzle's `inArray()`.
   - However, wait, memory says:
     "The UserMCPOAuthTokenRepository provides unified storage for MCP OAuth tokens, supporting both per-user tokens (where user_id is set) and shared-mode tokens (where user_id is NULL). For batch queries across multiple servers, its getTokensForServers method resolves both types simultaneously."
     Let's check if `getTokensForServers` already exists in `UserMCPOAuthTokenRepository` or if I need to implement it.
