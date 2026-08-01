1. **Add `getTokensForServers` batched method to `UserMCPOAuthTokenRepository`**
   - Update `packages/core/src/db/repositories/user-mcp-oauth-tokens.ts`.
   - The method uses `inArray` to query `mcp_server_id` for multiple servers in one go, filtering by `user_id` appropriately (either the specified `userId` or `null` for shared modes).
   - We will combine both queries by requesting either the given `userId` or `null`.
   - Store the fetched tokens in a Map using a compound key `<server_id>:<user_id>` (where `user_id` is an empty string if null).

2. **Modify `injectPerUserOAuthTokens` hook to utilize the batched method**
   - Update `apps/agor-daemon/src/register-hooks.ts`.
   - Before applying the `injectToken` map function, extract all `mcp_server_id`s from the result context.
   - Call `getTokensForServers` once to retrieve a map of tokens.
   - Use the pre-fetched map inside `injectToken` to lookup `oauth_access_token` synchronously, instead of `userTokenRepo.getToken` hitting the database on every server.

3. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
   - Run `pnpm lint:fix` and `pnpm test` to ensure stability.
