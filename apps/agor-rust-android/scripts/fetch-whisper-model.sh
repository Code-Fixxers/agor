#!/usr/bin/env bash
# Download a ggml whisper model into the Dioxus asset directories.
# Usage: scripts/fetch-whisper-model.sh [base.en|tiny.en|small.en|medium.en]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="${1:-base.en}"

case "$MODEL" in
  tiny.en|base.en|small.en|medium.en) ;;
  *)
    echo "Unsupported model '$MODEL'. Use one of: tiny.en base.en small.en medium.en" >&2
    exit 1
    ;;
esac

URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin"
TARGETS=(
  "$ROOT/apps/agor-hermes/assets/whisper/ggml-$MODEL.bin"
  "$ROOT/apps/hermes-only/assets/whisper/ggml-$MODEL.bin"
)

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

echo "Downloading $URL ..."
curl --fail -L "$URL" -o "$TMP"

for dest in "${TARGETS[@]}"; do
  mkdir -p "$(dirname "$dest")"
  cp "$TMP" "$dest"
  echo "Saved $dest ($(du -h "$dest" | cut -f1))"
done
