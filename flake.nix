{
  description = "Agor build/run/publish workflow";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        sharedRuntimeInputs = with pkgs; [
          bash
          coreutils
          curl
          findutils
          git
          gnugrep
          gnused
          gnumake
          jq
          nodejs_22
          pkg-config
          pnpm
          python3
          stdenv.cc
          sqlite
        ];

        buildScript = pkgs.writeShellApplication {
          name = "build-agor-live";
          runtimeInputs = sharedRuntimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "❌ Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            echo "📦 Building agor-live bundle..."
            (cd "$PKG_DIR" && ./build.sh)
          '';
        };

        runScript = pkgs.writeShellApplication {
          name = "run-agor-live";
          runtimeInputs = sharedRuntimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "❌ Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            # Ensure dist artifacts are up-to-date before running.
            (cd "$PKG_DIR" && ./build.sh)

            if [ ! -f "$PKG_DIR/bin/agor.js" ]; then
              echo "❌ Missing agor executable at $PKG_DIR/bin/agor.js"
              exit 1
            fi

            echo "▶️  Running agor-live wrapper..."
            cd "$PKG_DIR"
            node ./bin/agor.js "$@"
          '';
        };

        publishScript = pkgs.writeShellApplication {
          name = "publish-agor-live";
          runtimeInputs = sharedRuntimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "❌ Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            if [ -z "''${NPM_TOKEN:-}" ]; then
              echo "❌ NPM_TOKEN is not set."
              echo "Export your token first, e.g.:"
              echo "  export NPM_TOKEN=..."
              exit 1
            fi

            echo "📦 Building agor-live bundle..."
            (cd "$PKG_DIR" && ./build.sh)

            TMP_NPMRC="$(mktemp)"
            cleanup() {
              rm -f "$TMP_NPMRC"
            }
            trap cleanup EXIT

            cat > "$TMP_NPMRC" <<NPMRC
//registry.npmjs.org/:_authToken=''${NPM_TOKEN}
always-auth=true
NPMRC

            echo "🔐 Verifying npm identity..."
            NPM_CONFIG_USERCONFIG="$TMP_NPMRC" npm whoami

            echo "🚀 Publishing agor-live as authenticated npm user (expected: @donach)..."
            (
              cd "$PKG_DIR"
              NPM_CONFIG_USERCONFIG="$TMP_NPMRC" npm publish --access public
            )

            echo "✅ Publish completed."
          '';
        };

        testEnvScript = pkgs.writeShellApplication {
          name = "agor-test-env";
          runtimeInputs = sharedRuntimeInputs;
          text = ''
            set -euo pipefail

            usage() {
              cat <<'EOF'
agor-test-env

Run an isolated local Agor instance for human testing branch changes.

Usage:
  nix run .#agor-test-env -- [options]

Options:
  --state-dir DIR       Runtime state root (default: ./.agor-local)
  --daemon-port PORT    Daemon port (default: 3031)
  --ui-port PORT        UI dev server port (default: 5174)
  --no-install          Skip pnpm install bootstrap
  -h, --help            Show this help

Environment overrides:
  AGOR_TEST_STATE_DIR
  AGOR_TEST_DAEMON_PORT
  AGOR_TEST_UI_PORT

The wrapper sets HOME, AGOR_DATA_HOME, AGOR_DB_PATH, PORT, UI_PORT,
VITE_DAEMON_URL, and VITE_DAEMON_PORT to keep this instance separate
from ~/.agor and from the normal dev ports.
EOF
            }

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            STATE_DIR="''${AGOR_TEST_STATE_DIR:-$ROOT/.agor-local}"
            DAEMON_PORT="''${AGOR_TEST_DAEMON_PORT:-3031}"
            UI_PORT="''${AGOR_TEST_UI_PORT:-5174}"
            INSTALL=1

            while [ "$#" -gt 0 ]; do
              case "$1" in
                --state-dir)
                  if [ "$#" -lt 2 ]; then
                    echo "--state-dir requires a value" >&2
                    exit 2
                  fi
                  STATE_DIR="$2"
                  shift 2
                  ;;
                --daemon-port)
                  if [ "$#" -lt 2 ]; then
                    echo "--daemon-port requires a value" >&2
                    exit 2
                  fi
                  DAEMON_PORT="$2"
                  shift 2
                  ;;
                --ui-port)
                  if [ "$#" -lt 2 ]; then
                    echo "--ui-port requires a value" >&2
                    exit 2
                  fi
                  UI_PORT="$2"
                  shift 2
                  ;;
                --no-install)
                  INSTALL=0
                  shift
                  ;;
                -h|--help)
                  usage
                  exit 0
                  ;;
                *)
                  echo "Unknown option: $1" >&2
                  usage >&2
                  exit 2
                  ;;
              esac
            done

            STATE_DIR="$(mkdir -p "$STATE_DIR" && cd "$STATE_DIR" && pwd)"
            TEST_HOME="$STATE_DIR/home"
            AGOR_HOME_DIR="$TEST_HOME/.agor"
            DATA_HOME="$STATE_DIR/data"
            LOG_DIR="$STATE_DIR/logs"
            CONFIG_PATH="$AGOR_HOME_DIR/config.yaml"
            DB_FILE="$STATE_DIR/agor.db"
            SEEDED_REPO_SLUG="local/agor"
            SEEDED_WORKTREE_NAME="junie-smoke-test"
            SEEDED_WORKTREE_BRANCH="junie-smoke-test"

            mkdir -p "$AGOR_HOME_DIR" "$DATA_HOME" "$LOG_DIR" "$STATE_DIR/codex-home"

            CONFIG_JWT_SECRET=""
            CONFIG_MASTER_SECRET=""
            if [ -f "$CONFIG_PATH" ]; then
              CONFIG_JWT_SECRET="$(awk '/^[[:space:]]+jwtSecret:/ { print $2; exit }' "$CONFIG_PATH")"
              CONFIG_MASTER_SECRET="$(awk '/^[[:space:]]+masterSecret:/ { print $2; exit }' "$CONFIG_PATH")"
            fi
            if [ -z "$CONFIG_JWT_SECRET" ]; then
              CONFIG_JWT_SECRET="$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")"
            fi
            if [ -z "$CONFIG_MASTER_SECRET" ]; then
              CONFIG_MASTER_SECRET="$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")"
            fi
            if [ -f "$ROOT/.env" ]; then
              set -a
              # shellcheck disable=SC1091
              . "$ROOT/.env"
              set +a
            fi
            JUNIE_LITELLM_BASE_URL="''${JUNIE_LITELLM_BASE_URL:-https://llm.bitp.cz}"
            JUNIE_DEFAULT_MODEL="''${JUNIE_DEFAULT_MODEL:-qwen-3.6-27b}"
            JUNIE_FASTER_MODEL="''${JUNIE_FASTER_MODEL:-qwen-3.5-35b-a3b}"

            cat > "$CONFIG_PATH" <<EOF
daemon:
  host: 127.0.0.1
  port: $DAEMON_PORT
  public_url: http://127.0.0.1:$DAEMON_PORT
  base_url: http://127.0.0.1:$DAEMON_PORT
  allowAnonymous: false
  requireAuth: true
  instanceLabel: Local Test
  instanceDescription: Isolated flake test environment at $STATE_DIR
  jwtSecret: $CONFIG_JWT_SECRET
  masterSecret: $CONFIG_MASTER_SECRET
ui:
  host: 127.0.0.1
  port: $UI_PORT
database:
  dialect: sqlite
  sqlite:
    path: $DB_FILE
paths:
  data_home: $DATA_HOME
security:
  cors:
    origins:
      - http://127.0.0.1:$UI_PORT
      - http://localhost:$UI_PORT
execution:
  worktree_rbac: false
  unix_user_mode: simple
  allow_web_terminal: true
codex:
  home: $STATE_DIR/codex-home
credentials:
  JUNIE_LITELLM_API_KEY: ''${JUNIE_LITELLM_API_KEY:-}
junie:
  litellmBaseUrl: $JUNIE_LITELLM_BASE_URL
  defaultModel: $JUNIE_DEFAULT_MODEL
  fasterModel: $JUNIE_FASTER_MODEL
  apiType: OpenAICompletion
EOF

            cd "$ROOT"

            if [ "$INSTALL" = 1 ] && [ ! -d "$ROOT/node_modules" ]; then
              echo "Installing workspace dependencies with pnpm..."
              pnpm install --frozen-lockfile
            fi

            export HOME="$TEST_HOME"
            export AGOR_DATA_HOME="$DATA_HOME"
            export AGOR_DB_DIALECT=sqlite
            export AGOR_DB_PATH="file:$DB_FILE"
            export DATABASE_URL="file:$DB_FILE"
            export PORT="$DAEMON_PORT"
            export UI_PORT="$UI_PORT"
            export VITE_DAEMON_URL="http://127.0.0.1:$DAEMON_PORT"
            export VITE_DAEMON_PORT="$DAEMON_PORT"
            export CODEX_HOME="$STATE_DIR/codex-home"
            export NODE_ENV=development

            echo "Agor local test environment"
            echo "  State:  $STATE_DIR"
            echo "  Config: $CONFIG_PATH"
            echo "  DB:     $DB_FILE"
            echo "  Daemon: http://127.0.0.1:$DAEMON_PORT"
            echo "  UI:     http://127.0.0.1:$UI_PORT"
            echo "  Seed:   $SEEDED_REPO_SLUG / $SEEDED_WORKTREE_NAME"
            echo ""

            echo "Building executor for local worktree and agent runs..."
            pnpm --filter @agor/executor build
            echo ""

            echo "Applying database migrations for isolated test DB..."
            pnpm agor db migrate --yes
            echo ""

            echo "Ensuring default local admin exists..."
            pnpm agor user create-admin
            echo ""

            if curl -fsS "http://127.0.0.1:$DAEMON_PORT/health" >/dev/null 2>&1; then
              echo "Port $DAEMON_PORT already has an Agor daemon responding. Pick another --daemon-port." >&2
              exit 1
            fi

            if curl -fsS "http://127.0.0.1:$UI_PORT" >/dev/null 2>&1; then
              echo "Port $UI_PORT already has an HTTP server responding. Pick another --ui-port." >&2
              exit 1
            fi

            daemon_pid=""
            ui_pid=""
            cleanup() {
              status="$?"
              trap - EXIT INT TERM
              if [ -n "$daemon_pid" ]; then kill "$daemon_pid" 2>/dev/null || true; fi
              if [ -n "$ui_pid" ]; then kill "$ui_pid" 2>/dev/null || true; fi
              wait "$daemon_pid" "$ui_pid" 2>/dev/null || true
              exit "$status"
            }
            trap cleanup EXIT INT TERM

            pnpm --filter @agor/daemon dev:daemon-only > "$LOG_DIR/daemon.log" 2>&1 &
            daemon_pid="$!"

            for _ in $(seq 1 60); do
              if curl -fsS "http://127.0.0.1:$DAEMON_PORT/health" >/dev/null 2>&1; then
                break
              fi
              if ! kill -0 "$daemon_pid" 2>/dev/null; then
                echo "Daemon exited early. Log:"
                tail -100 "$LOG_DIR/daemon.log" || true
                exit 1
              fi
              sleep 1
            done

            if ! curl -fsS "http://127.0.0.1:$DAEMON_PORT/health" >/dev/null 2>&1; then
              echo "Daemon did not become healthy within 60 seconds. Log:"
              tail -100 "$LOG_DIR/daemon.log" || true
              exit 1
            fi

            echo "Seeding local Agor repository..."
            auth_json="$(curl -fsS \
              -H 'Content-Type: application/json' \
              -d '{"strategy":"local","email":"admin@agor.live","password":"admin"}' \
              "http://127.0.0.1:$DAEMON_PORT/authentication")"
            access_token="$(printf '%s' "$auth_json" | jq -r '.accessToken')"
            if [ -z "$access_token" ] || [ "$access_token" = "null" ]; then
              echo "Failed to authenticate default local admin for seed setup" >&2
              exit 1
            fi

            auth_curl() {
              curl -fsS \
                -H "Authorization: Bearer $access_token" \
                -H 'Content-Type: application/json' \
                "$@"
            }

            repo_json="$(auth_curl "http://127.0.0.1:$DAEMON_PORT/repos?slug=local%2Fagor")"
            repo_id="$(printf '%s' "$repo_json" | jq -r '.data[0].repo_id // empty')"
            if [ -z "$repo_id" ]; then
              repo_json="$(auth_curl \
                -d "{\"path\":\"$ROOT\",\"slug\":\"$SEEDED_REPO_SLUG\"}" \
                "http://127.0.0.1:$DAEMON_PORT/repos/local")"
              repo_id="$(printf '%s' "$repo_json" | jq -r '.repo_id')"
              echo "  Registered repo $SEEDED_REPO_SLUG"
            else
              echo "  Repo $SEEDED_REPO_SLUG already registered"
            fi

            board_json="$(auth_curl "http://127.0.0.1:$DAEMON_PORT/boards?slug=default")"
            board_id="$(printf '%s' "$board_json" | jq -r '.data[0].board_id // empty')"
            if [ -z "$board_id" ]; then
              echo "Default board not found after seed initialization" >&2
              exit 1
            fi

            worktree_json="$(auth_curl "http://127.0.0.1:$DAEMON_PORT/worktrees?repo_id=$repo_id&name=$SEEDED_WORKTREE_NAME")"
            worktree_id="$(printf '%s' "$worktree_json" | jq -r '.data[0].worktree_id // empty')"
            if [ -n "$worktree_id" ]; then
              seed_status="$(printf '%s' "$worktree_json" | jq -r '.data[0].filesystem_status // "unknown"')"
              if [ "$seed_status" != "ready" ]; then
                echo "  Removing stale worktree $SEEDED_WORKTREE_NAME (status: $seed_status)"
                auth_curl -X DELETE "http://127.0.0.1:$DAEMON_PORT/worktrees/$worktree_id?deleteFromFilesystem=true" >/dev/null
                worktree_id=""
              fi
            fi

            if [ -z "$worktree_id" ]; then
              source_branch="$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
              if [ -z "$source_branch" ]; then
                source_branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
              fi
              worktree_payload="$(jq -nc \
                --arg name "$SEEDED_WORKTREE_NAME" \
                --arg ref "$SEEDED_WORKTREE_BRANCH" \
                --arg sourceBranch "$source_branch" \
                --arg boardId "$board_id" \
                '{name:$name, ref:$ref, createBranch:true, sourceBranch:$sourceBranch, boardId:$boardId}')"
              worktree_json="$(auth_curl \
                -d "$worktree_payload" \
                "http://127.0.0.1:$DAEMON_PORT/repos/$repo_id/worktrees")"
              worktree_id="$(printf '%s' "$worktree_json" | jq -r '.worktree_id')"
              echo "  Created worktree $SEEDED_WORKTREE_NAME from $source_branch"
            else
              echo "  Worktree $SEEDED_WORKTREE_NAME already exists"
            fi

            for _ in $(seq 1 60); do
              worktree_json="$(auth_curl "http://127.0.0.1:$DAEMON_PORT/worktrees/$worktree_id")"
              status="$(printf '%s' "$worktree_json" | jq -r '.filesystem_status // "unknown"')"
              if [ "$status" = "ready" ]; then
                break
              fi
              if [ "$status" = "failed" ]; then
                printf '%s\n' "$worktree_json" | jq .
                echo "Seeded worktree failed to become ready" >&2
                exit 1
              fi
              sleep 1
            done

            worktree_json="$(auth_curl "http://127.0.0.1:$DAEMON_PORT/worktrees/$worktree_id")"
            status="$(printf '%s' "$worktree_json" | jq -r '.filesystem_status // "unknown"')"
            worktree_path="$(printf '%s' "$worktree_json" | jq -r '.path // empty')"
            if [ "$status" != "ready" ]; then
              echo "Seeded worktree did not become ready within 60 seconds (status: $status)" >&2
              exit 1
            fi
            echo "  Worktree ready at $worktree_path"
            echo ""

            pnpm --filter agor-ui exec vite --host 127.0.0.1 --port "$UI_PORT" --strictPort > "$LOG_DIR/ui.log" 2>&1 &
            ui_pid="$!"

            for _ in $(seq 1 60); do
              if curl -fsS "http://127.0.0.1:$UI_PORT" >/dev/null 2>&1; then
                break
              fi
              if ! kill -0 "$ui_pid" 2>/dev/null; then
                echo "UI exited early. Log:"
                tail -100 "$LOG_DIR/ui.log" || true
                exit 1
              fi
              sleep 1
            done

            if ! curl -fsS "http://127.0.0.1:$UI_PORT" >/dev/null 2>&1; then
              echo "UI did not become reachable within 60 seconds. Log:"
              tail -100 "$LOG_DIR/ui.log" || true
              exit 1
            fi

            echo "Ready."
            echo "  Open: http://127.0.0.1:$UI_PORT"
            echo "  Logs: tail -f $LOG_DIR/daemon.log $LOG_DIR/ui.log"
            echo ""
            echo "Press Ctrl-C to stop both processes."

            wait -n "$daemon_pid" "$ui_pid"
          '';
        };

        testChecksScript = pkgs.writeShellApplication {
          name = "agor-test-checks";
          runtimeInputs = sharedRuntimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            cd "$ROOT"

            if [ ! -d "$ROOT/node_modules" ]; then
              echo "Installing workspace dependencies with pnpm..."
              pnpm install --frozen-lockfile
            fi

            echo "Running workspace typecheck..."
            pnpm typecheck

            echo "Running focused Junie/core checks..."
            pnpm --filter @agor/core test src/types/session.test.ts src/utils/permission-mode-mapper.test.ts src/config/config-manager.test.ts
            pnpm --filter @agor/executor test src/payload-types.test.ts src/sdk-handlers/junie/profile.test.ts src/sdk-handlers/junie/normalizer.test.ts src/sdk-handlers/junie/junie-tool.test.ts
            pnpm --filter @agor/daemon test src/utils/spawn-executor.secrets.test.ts
            pnpm --filter agor-ui typecheck

            echo "Running repo lint..."
            pnpm lint
          '';
        };
      in {
        packages = {
          build-agor-live-wrapper = buildScript;
          run-agor-live-wrapper = runScript;
          publish-agor-live-wrapper = publishScript;
          agor-test-env-wrapper = testEnvScript;
          agor-test-checks-wrapper = testChecksScript;
          default = runScript;
        };

        apps = {
          build-agor-live = {
            type = "app";
            program = "${buildScript}/bin/build-agor-live";
          };
          run-agor-live = {
            type = "app";
            program = "${runScript}/bin/run-agor-live";
          };
          publish-agor-live = {
            type = "app";
            program = "${publishScript}/bin/publish-agor-live";
          };
          agor-test-env = {
            type = "app";
            program = "${testEnvScript}/bin/agor-test-env";
          };
          agor-test-checks = {
            type = "app";
            program = "${testChecksScript}/bin/agor-test-checks";
          };
          default = self.apps.${system}.run-agor-live;
        };

        devShells.default = pkgs.mkShell {
          packages = sharedRuntimeInputs;

          shellHook = ''
            export npm_config_python="${pkgs.python3}/bin/python3"
            echo "Agor dev shell: node $(node --version), pnpm $(pnpm --version)"
          '';
        };
      });
}
