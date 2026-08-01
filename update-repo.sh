<<<<<<< SEARCH
import { and, eq, isNull } from 'drizzle-orm';
=======
import { and, eq, inArray, isNull, or } from 'drizzle-orm';
>>>>>>> REPLACE
<<<<<<< SEARCH
  async listForUser(userId: UserID): Promise<UserMCPOAuthToken[]> {
=======
  async getTokensForServers(
    userId: UserID | null,
    serverIds: MCPServerID[]
  ): Promise<Map<string, UserMCPOAuthToken>> {
    if (serverIds.length === 0) {
      return new Map();
    }
    try {
      const conditions = [];
      if (userId === null) {
        conditions.push(isNull(userMcpOauthTokens.user_id));
      } else {
        conditions.push(or(eq(userMcpOauthTokens.user_id, userId), isNull(userMcpOauthTokens.user_id)));
      }

      const rows = await select(this.db)
        .from(userMcpOauthTokens)
        .where(
          and(
            inArray(userMcpOauthTokens.mcp_server_id, serverIds),
            ...conditions
          )
        )
        .all();

      const result = new Map<string, UserMCPOAuthToken>();
      for (const row of rows) {
        const token = rowToToken(row);
        result.set(`${token.mcp_server_id}:${token.user_id || ''}`, token);
      }
      return result;
    } catch (error) {
      throw new RepositoryError(
        `Failed to get tokens for servers: ${error instanceof Error ? error.message : String(error)}`,
        error
      );
    }
  }

  async listForUser(userId: UserID): Promise<UserMCPOAuthToken[]> {
>>>>>>> REPLACE
