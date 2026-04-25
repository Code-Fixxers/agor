#!/usr/bin/env bash
# Download a ggml whisper model into app/src/main/assets/whisper/.
# Usage: scripts/fetch-whisper-model.sh [base.en|small.en|tiny.en]

set -euo pipefail

cd "$(dirname "$0")/.."

MODEL="${1:-base.en}"
case "$MODEL" in
  tiny.en|base.en|small.en|medium.en) ;;
  *)
    echo "Unsupported model '$MODEL'. Use one of: tiny.en base.en small.en medium.en"
    exit 1
    ;;
esac

URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin"
DIR="app/src/main/assets/whisper"
DEST="$DIR/ggml-$MODEL.bin"

mkdir -p "$DIR"
echo "Downloading $URL ..."
curl --fail -L "$URL" -o "$DEST"
echo "Saved to $DEST ($(du -h "$DEST" | cut -f1))"
