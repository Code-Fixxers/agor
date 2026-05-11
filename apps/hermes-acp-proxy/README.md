# Hermes ACP Proxy

Small stdio bridge that lets JetBrains AI Chat launch Hermes as an ACP agent.

The proxy speaks line-delimited JSON-RPC over `stdin`/`stdout`, forwards prompt
turns to Hermes' OpenAI-compatible `/v1/chat/completions` streaming endpoint,
and maps response chunks back to ACP `session/update` notifications.

## Configuration

Set these environment variables in `~/.jetbrains/acp.json` or through the
Home Manager module:

| Variable | Purpose |
| --- | --- |
| `HERMES_URL` | Hermes base URL, for example `http://localhost:8642` |
| `HERMES_MODEL` | Model name passed to Hermes, defaults to `hermes` |
| `HERMES_TOKEN` | Literal bearer token |
| `HERMES_TOKEN_COMMAND` | Shell command that prints the bearer token |
| `HERMES_TOKEN_FILE` | File containing the bearer token |

Token resolution order is literal token, command, then file.

## Development

```bash
pnpm --filter @agor/hermes-acp-proxy test
pnpm --filter @agor/hermes-acp-proxy build
```

The Nix flake package runs `standalone/hermes-acp-proxy.mjs`, a dependency-free
Node entrypoint kept in sync with the TypeScript implementation for declarative
Home Manager installs.
