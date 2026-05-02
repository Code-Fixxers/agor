# @agor/anythingllm-mcp

Tiny MCP server that wraps a self-hosted AnythingLLM instance and exposes
three tools: `anythingllm_query`, `anythingllm_remember`, `anythingllm_list_workspaces`.

Designed to be reachable from the **Hermes Agent container** as a remote MCP
endpoint (Streamable HTTP transport). Hermes uses these tools to read/write
its long-term memory in AnythingLLM.

## Why a wrapper

Hermes Agent already speaks MCP, and AnythingLLM has a documented HTTP API.
The mismatch is small but real:

- AnythingLLM's auth is a single global API key, not workspace-scoped.
- The chat endpoint returns prose + sources in one body — fine for a wrapper
  to format, but awkward to ask the LLM to parse.
- We want a *narrow* tool surface (3 tools) so the LLM doesn't get lost.

A 200-line server we control is the right shape.

## Configuration

All env vars:

| Var | Required | Purpose |
|---|---|---|
| `ANYTHINGLLM_BASE_URL` | yes | e.g. `http://10.233.1.1:13001` (host-side socat into the AnythingLLM container) |
| `ANYTHINGLLM_API_KEY` | yes | Instance API key from AnythingLLM settings |
| `ANYTHINGLLM_DEFAULT_WORKSPACE` | no | Default workspace slug. Default: `agor` |
| `ANYTHINGLLM_MCP_BEARER` | yes | Pre-shared bearer the MCP client (Hermes) must send. ≥16 chars |
| `ANYTHINGLLM_MCP_HOST` | no | Bind address. Default: `0.0.0.0` |
| `ANYTHINGLLM_MCP_PORT` | no | Listen port. Default: `13031` |
| `ANYTHINGLLM_MCP_LOG_LEVEL` | no | pino level. Default: `info` |

## Endpoints

- `GET  /health` — unauthenticated liveness probe
- `POST /mcp` — Streamable HTTP MCP transport. Bearer-auth required.

## Run locally

```bash
pnpm install
ANYTHINGLLM_BASE_URL=http://localhost:3001 \
ANYTHINGLLM_API_KEY=… \
ANYTHINGLLM_MCP_BEARER=$(openssl rand -hex 32) \
pnpm --filter @agor/anythingllm-mcp dev
```

## Deployment

Recommended pattern (matches the user's `nixos-llm` setup):

1. Build the OCI image (a Dockerfile drop-in mirroring `apps/agor-hermes/Dockerfile`
   would work; not committed here yet — TODO).
2. Run as a systemd service or `oci-containers` entry on the NixOS host with
   the env vars above.
3. Expose `127.0.0.1:13031` to the Hermes container via `socat`.
4. Add to `hermes-container.nix`'s config template:
   ```yaml
   mcp_servers:
     anythingllm:
       url: "http://10.233.2.1:13031/mcp"
       headers:
         Authorization: "Bearer ${ANYTHINGLLM_MCP_BEARER}"
   ```

The patch to `nixos-llm/hosts/nixos/hermes-container.nix` lives in a
parallel branch on that repo.

## Status

H.5 of the plan in `apps/agor-android/VISION.md`. Read-only path first;
the write path (`anythingllm_remember`) is wired but the persona that
calls it is still in flux.
