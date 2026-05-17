#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL_ROOT="$ROOT/target/dev-tools"
BIN_DIR="$TOOL_ROOT/bin"
DX_VERSION="${DX_VERSION:-0.6.3}"
WASM_BINDGEN_VERSION="${WASM_BINDGEN_VERSION:-0.2.121}"
PACKAGE="${AGOR_RUST_ANDROID_PACKAGE:-agor-hermes-app}"
PORT="${AGOR_RUST_ANDROID_PORT:-6173}"
ADDR="${AGOR_RUST_ANDROID_ADDR:-127.0.0.1}"
MODE="${1:-serve}"

if [[ "$MODE" == "serve" || "$MODE" == "build" ]]; then
  shift || true
fi

export PATH="$BIN_DIR:$PATH"
export NO_DOWNLOADS=1
export XDG_DATA_HOME="${XDG_DATA_HOME:-$ROOT/target/xdg-data}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$ROOT/target/xdg-cache}"

configure_openssl() {
  if ldconfig -p 2>/dev/null | grep -q 'libssl\.so\.3'; then
    return
  fi

  local ssl_lib
  for ssl_lib in /run/current-system/sw/lib/libssl.so.3 /nix/store/*-openssl-*/lib/libssl.so.3; do
    if [[ -e "$ssl_lib" ]]; then
      export LD_LIBRARY_PATH="$(dirname "$ssl_lib"):${LD_LIBRARY_PATH:-}"
      return
    fi
  done

  if [[ -z "${LD_LIBRARY_PATH:-}" ]]; then
    echo "warning: libssl.so.3 not found; locally installed dx may fail to start" >&2
  fi
}

ensure_cargo_tool() {
  local bin="$1"
  local crate="$2"
  local version="$3"

  if command -v "$bin" >/dev/null 2>&1 && "$bin" --version | grep -q "$version"; then
    return
  fi

  cargo install "$crate" --version "$version" --locked --root "$TOOL_ROOT"
}

configure_openssl
ensure_cargo_tool dx dioxus-cli "$DX_VERSION"
ensure_cargo_tool wasm-bindgen wasm-bindgen-cli "$WASM_BINDGEN_VERSION"
configure_openssl

cd "$ROOT"

case "$MODE" in
  build)
    exec dx build --platform web --package "$PACKAGE" -- "$@"
    ;;
  serve)
    exec dx serve \
      --platform web \
      --package "$PACKAGE" \
      --addr "$ADDR" \
      --port "$PORT" \
      --open false \
      -- "$@"
    ;;
  *)
    echo "Usage: $0 [serve|build] [cargo args...]" >&2
    exit 2
    ;;
esac
