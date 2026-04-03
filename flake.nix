{
  description = "Agor build + npm publish workflow";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        publishScript = pkgs.writeShellApplication {
          name = "publish-agor-live";
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
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            PKG_DIR="$ROOT/packages/agor-live"

            if [ ! -d "$PKG_DIR" ]; then
              echo "❌ Could not find packages/agor-live from: $ROOT"
              exit 1
            fi

            if [ -z "${NPM_TOKEN:-}" ]; then
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
//registry.npmjs.org/:_authToken=${NPM_TOKEN}
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
      in {
        apps = {
          publish-agor-live = {
            type = "app";
            program = "${publishScript}/bin/publish-agor-live";
          };
          default = self.apps.${system}.publish-agor-live;
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            nodejs_22
            pnpm
            jq
          ];
        };
      });
}
