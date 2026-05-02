# syntax=docker/dockerfile:1.7
# Multi-stage build for the Hermes orchestrator container.
#
# Stage 1 builds with full pnpm + native toolchain (better-sqlite3 needs gyp).
# Stage 2 is a slim Node 22 runtime that only carries the bundled output and
# its production node_modules.

FROM node:22-bookworm AS builder
WORKDIR /repo

# Install pnpm globally to match the workspace.
RUN corepack enable

# Copy workspace manifest so pnpm sees the workspace layout. We don't currently
# depend on @agor/core, but build with workspace context for forward-compat.
COPY pnpm-workspace.yaml package.json pnpm-lock.yaml ./
COPY apps/agor-hermes/package.json apps/agor-hermes/

# Toolchain for native modules.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 build-essential \
    && rm -rf /var/lib/apt/lists/*

RUN pnpm install --filter @agor/hermes --frozen-lockfile

COPY apps/agor-hermes/ apps/agor-hermes/

WORKDIR /repo/apps/agor-hermes
RUN pnpm build

FROM node:22-bookworm-slim AS runtime
WORKDIR /app

# Copy runtime artifacts. We re-install only @agor/hermes prod deps to keep
# the image small.
COPY --from=builder /repo/apps/agor-hermes/dist ./dist
COPY --from=builder /repo/apps/agor-hermes/package.json ./package.json
COPY --from=builder /repo/apps/agor-hermes/persona.md /etc/hermes/persona.md

RUN corepack enable && \
    pnpm install --prod --no-frozen-lockfile && \
    mkdir -p /var/lib/hermes

ENV NODE_ENV=production \
    HERMES_HOST=0.0.0.0 \
    HERMES_PORT=4040 \
    HERMES_DB_PATH=/var/lib/hermes/hermes.db \
    HERMES_PERSONA_PATH=/etc/hermes/persona.md

EXPOSE 4040
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:'+(process.env.HERMES_PORT||4040)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "dist/main.js"]
