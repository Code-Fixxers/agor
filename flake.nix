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

            mkdir -p "$AGOR_HOME_DIR" "$DATA_HOME" "$LOG_DIR" "$STATE_DIR/codex-home"

            cat > "$CONFIG_PATH" <<EOF
daemon:
  host: 127.0.0.1
  port: $DAEMON_PORT
  public_url: http://127.0.0.1:$DAEMON_PORT
  base_url: http://127.0.0.1:$DAEMON_PORT
  allowAnonymous: true
  requireAuth: false
  instanceLabel: Local Test
  instanceDescription: Isolated flake test environment at $STATE_DIR
ui:
  host: 127.0.0.1
  port: $UI_PORT
database:
  dialect: sqlite
  sqlite:
    path: $DB_FILE
paths:
  data_home: $DATA_HOME
execution:
  worktree_rbac: false
  unix_user_mode: simple
  allow_web_terminal: true
codex:
  home: $STATE_DIR/codex-home
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

            pnpm --filter agor-ui dev -- --host 127.0.0.1 --port "$UI_PORT" > "$LOG_DIR/ui.log" 2>&1 &
            ui_pid="$!"

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
