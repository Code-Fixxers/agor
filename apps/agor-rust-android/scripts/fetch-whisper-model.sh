#!/usr/bin/env bash
# Download a ggml whisper model as a standalone artifact.
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
DEST="${AGOR_WHISPER_MODEL_DIR:-$ROOT/target/whisper-models}/ggml-$MODEL.bin"

mkdir -p "$(dirname "$DEST")"

echo "Downloading $URL ..."
curl --fail -L "$URL" -o "$DEST"
echo "Saved $DEST ($(du -h "$DEST" | cut -f1))"
echo "Configure this path or host it as an artifact URL; it is not packaged into the APK."
