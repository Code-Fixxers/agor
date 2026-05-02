# Hermes

Standalone orchestrator service. Sits between the Android app (over Tailscale)
and the rest of the Agor stack:

- **LiteLLM proxy** — for inference. Self-hosted models (Gemma, Qwen, …) behind
  one OpenAI-compatible endpoint.
- **Agor daemon (MCP)** — for tools. Hermes spawns/inspects/prompts Agor
  sessions through `agor_*` MCP tools using a personal API key.
- **AnythingLLM** — for memory. Hermes queries before answering, writes when
  something is worth remembering.

## Architecture

```
Android phone
    │
    │  POST /chat   (Bearer ${HERMES_BEARER_TOKEN})
    │  WS  /chat/stream
    ▼
┌───────────────────┐
│   Hermes (this)   │
│   Fastify on :4040│
└─┬───────┬───────┬─┘
  │       │       │
  ▼       ▼       ▼
LiteLLM   Agor    AnythingLLM
proxy     MCP     RAG memory
```

Everything is on a Tailnet. Hermes does not need to be reachable from the
public internet.

## Configuration

All config is via environment variables. Required ones:

| Var | Purpose |
|---|---|
| `HERMES_BEARER_TOKEN` | Pre-shared secret the Android app sends in `Authorization: Bearer …`. ≥16 chars. |
| `LITELLM_BASE_URL` | e.g. `https://litellm.tail-something.ts.net/v1` |
| `LITELLM_API_KEY` | LiteLLM proxy master/virtual key |
| `AGOR_MCP_URL` | Agor daemon's MCP endpoint, e.g. `https://agor.tail-something.ts.net/mcp` |
| `AGOR_PERSONAL_API_KEY` | Personal API key (`agor_sk_…`) minted from Agor's settings UI |

Optional (memory disabled if either omitted):

| Var | Purpose |
|---|---|
| `ANYTHINGLLM_BASE_URL` | e.g. `https://anythingllm.tail-something.ts.net` |
| `ANYTHINGLLM_API_KEY` | Instance API key from AnythingLLM settings |
| `ANYTHINGLLM_WORKSPACE` | Default workspace slug. Default: `agor` |

Other tuning:

| Var | Default | Purpose |
|---|---|---|
| `HERMES_HOST` | `0.0.0.0` | Bind address |
| `HERMES_PORT` | `4040` | Listen port |
| `HERMES_LOG_LEVEL` | `info` | pino level |
| `HERMES_DEFAULT_MODEL` | `qwen3.6` | LiteLLM model codename for default turns |
| `HERMES_FALLBACK_MODEL` | `gemma-4` | Used if default fails (H.2+) |
| `HERMES_DB_PATH` | `/var/lib/hermes/hermes.db` | SQLite for conversation history |
| `HERMES_PERSONA_PATH` | `/etc/hermes/persona.md` | System prompt source |

## Endpoints

- `GET  /health` — unauthenticated liveness probe.
- `POST /chat` — unary chat. Body: `{ conversationId, text, model? }`. Returns
  `{ reply, toolHops }`.
- `WS   /chat/stream` — streaming chat (placeholder until H.2).

## Run locally

```bash
pnpm install
HERMES_BEARER_TOKEN=$(openssl rand -hex 32) \
LITELLM_BASE_URL=http://localhost:4000/v1 \
LITELLM_API_KEY=sk-… \
AGOR_MCP_URL=http://localhost:3030/mcp \
AGOR_PERSONAL_API_KEY=agor_sk_… \
pnpm --filter @agor/hermes dev
```

The dev server hot-reloads via `tsx watch`.

## Deployment

A NixOS module shipping in `flake.nix` (`packages.hermes-container`) builds an
OCI image. See the flake for the deployment recipe.

## Status

- **H.1 (this branch)** — server skeleton, config, auth, SQLite store, persona
  loader, stub LLM/MCP/memory clients.
- **H.2** — wire LLM (LiteLLM), MCP client (Agor), memory client (AnythingLLM)
  for real. Streaming over WebSocket.
- **H.3** — Android `HermesScreen` + Hermes server profile.
- **H.4** — persona iteration based on actual usage.
