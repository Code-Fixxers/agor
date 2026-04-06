{
  description = "Agor flake (direct agor-live launcher plus build/run/publish wrappers)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        src = self;

        runtimeInputs = with pkgs; [
          bash
          coreutils
          findutils
          gnugrep
          gnused
          jq
          nodejs_22
          pnpm
        ];

        launcher = pkgs.writeShellApplication {
          name = "agor-live";
          runtimeInputs = runtimeInputs;
          text = ''
            set -euo pipefail

            SRC_DIR="${src}"
            REV="${if self ? rev then self.rev else "dirty"}"
            WORKDIR_BASE="''${XDG_CACHE_HOME:-$HOME/.cache}/agor-live-flake"
            WORKDIR="$WORKDIR_BASE/$REV"

            mkdir -p "$WORKDIR_BASE"

            if [ ! -f "$WORKDIR/packages/agor-live/package.json" ]; then
              rm -rf "$WORKDIR"
              cp -R "$SRC_DIR" "$WORKDIR"
              chmod -R u+w "$WORKDIR"
            fi

            cd "$WORKDIR"

            if [ ! -d node_modules ]; then
              pnpm install --frozen-lockfile
            fi

            if [ ! -f packages/agor-live/dist/cli/index.js ]; then
              echo "Building agor-live..."
              ./packages/agor-live/build.sh
            fi

            exec node ./packages/agor-live/bin/agor.js "$@"
          '';
        };

        buildWrapper = pkgs.writeShellApplication {
          name = "build-agor-live";
          runtimeInputs = runtimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            echo "Building agor-live bundle..."
            (cd "$PKG_DIR" && ./build.sh)
          '';
        };

        runWrapper = pkgs.writeShellApplication {
          name = "run-agor-live";
          runtimeInputs = runtimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            (cd "$PKG_DIR" && ./build.sh)

            if [ ! -f "$PKG_DIR/bin/agor.js" ]; then
              echo "Missing agor executable at $PKG_DIR/bin/agor.js"
              exit 1
            fi

            echo "Running agor-live wrapper..."
            cd "$PKG_DIR"
            node ./bin/agor.js "$@"
          '';
        };

        publishWrapper = pkgs.writeShellApplication {
          name = "publish-agor-live";
          runtimeInputs = runtimeInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            if [ -z "''${NPM_TOKEN:-}" ]; then
              echo "NPM_TOKEN is not set."
              echo "Export your token first, e.g.:"
              echo "  export NPM_TOKEN=..."
              exit 1
            fi

            echo "Building agor-live bundle..."
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

            echo "Verifying npm identity..."
            NPM_CONFIG_USERCONFIG="$TMP_NPMRC" npm whoami

            echo "Publishing agor-live as authenticated npm user..."
            (
              cd "$PKG_DIR"
              NPM_CONFIG_USERCONFIG="$TMP_NPMRC" npm publish --access public
            )

            echo "Publish completed."
          '';
        };
      in {
        packages = {
          agor-live = launcher;
          agor-live-cli = launcher;
          build-agor-live-wrapper = buildWrapper;
          run-agor-live-wrapper = runWrapper;
          publish-agor-live-wrapper = publishWrapper;
          default = launcher;
        };

        apps = {
          agor-live = {
            type = "app";
            program = "${launcher}/bin/agor-live";
          };
          build-agor-live = {
            type = "app";
            program = "${buildWrapper}/bin/build-agor-live";
          };
          run-agor-live = {
            type = "app";
            program = "${runWrapper}/bin/run-agor-live";
          };
          publish-agor-live = {
            type = "app";
            program = "${publishWrapper}/bin/publish-agor-live";
          };
          default = self.apps.${system}.agor-live;
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            nodejs_22
            pnpm
            jq
          ];
        };

        formatter = pkgs.nixfmt-rfc-style;
      });
}
