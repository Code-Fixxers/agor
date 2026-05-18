# Agor MCP Server Reliability Investigation Findings

## Executive Summary
This document summarizes the investigation and fixes applied to the Agor MCP (Model Context Protocol) server. The primary goals were to resolve intermittent failures, requests that hang indefinitely, and missing/empty data results returned by standard tools like `agor_boards_list`.

Through code exploration of the `McpServer` setup in Feathers and Drizzle ORM pagination, three distinct root causes were identified and fixed.

## 1. Tools Hanging or Timing Out (Stateless Fallback Issue)
### Problem
Agor's `POST /mcp` handler included a "Stateless fallback mode" block. This code assumed that if an incoming `POST` request did not have a transport `mcpSessionId` assigned to it (and was not an `initialize` request), it should spin up a brand new `McpServer` and pass the request directly via `StreamableHTTPServerTransport.handleRequest()`.

However, the official `@modelcontextprotocol/sdk` strictly requires a server to receive an `initialize` request before processing normal messages (such as `tools/call`). When the server receives a `tools/call` message without being initialized first, it simply drops the message and returns nothing. Because the HTTP handler was waiting for a response that would never arrive, the `POST` request hung indefinitely, eventually resulting in a client-side timeout.

### Fix
Replaced the broken stateless fallback block with an explicit `HTTP 400` returning a proper MCP JSON-RPC `-32000` error code. This enforces that clients must properly initialize a stateful transport session, enabling them to fail fast rather than hang when improperly configured.

## 2. Empty Results from Tools (Pagination Extraction Bug)
### Problem
Several list-based MCP tools (such as `agor_boards_list`, `agor_repos_list`, etc.) used nullish coalescing to set their default limits, e.g., `args.limit ?? 50`. Concurrently, the core `DrizzleService` handles pagination by explicitly relying on `query.$limit`.

When external agents passed `limit: 0` inside an MCP tool call in an attempt to request unlimited results, the value `0` bypassed the nullish coalescing operator. This strictly set `query.$limit = 0`. The database adapter would then slice the results as `data.slice(0, 0)`, returning an empty array even though database records existed.

### Fix
Updated the parameter parsing across all MCP tools to use strict undefined checks alongside an explicitly evaluated fallback `args.limit || 50`. This safely normalizes an explicit `0` to the intended default limit, preventing the empty array issue from occurring.

## 3. Intermittent Failures and State Issues (Stale Context)
### Problem
When the `McpServer` transport connection is initialized, it caches an `McpContext` object closure that contains the authenticated Agor `sessionId`. For external orchestrator clients using API keys and making subsequent stateful requests over the same transport, they typically pass their updated context via HTTP headers (`x-agor-session-id`) or query strings (`?sessionId=`).

Because the SDK transport routes subsequent tool calls to the original instantiated `McpServer`, those tools were evaluating against the original, stale cached `McpContext`.

### Fix
1. Modified the MCP session ID identification to check `req.query.sessionId` as a fallback when the `mcp-session-id` header is missing.
2. Wrapped the SDK's `transport.handleRequest` hook to dynamically intercept subsequent HTTP requests over the same stateful connection, manually injecting the latest parsed Agor `sessionId` into the cached `existing.mcpContext` object before dispatching the request to the inner server tools.

## Conclusion
These targeted fixes align the Agor daemon safely with the official MCP protocol semantics and resolve the major integration roadblocks for external orchestrating agents.
