#!/usr/bin/env bash
# Vendor whisper.cpp into the cpp source tree so the NDK build can include it.
# We pin a specific commit for reproducibility.

set -euo pipefail

cd "$(dirname "$0")/.."

WHISPER_REPO="https://github.com/ggerganov/whisper.cpp.git"
WHISPER_REF="${WHISPER_REF:-v1.7.1}"
TARGET="app/src/main/cpp/whisper.cpp"

if [ -d "$TARGET/.git" ]; then
  echo "whisper.cpp already vendored at $TARGET"
  echo "Updating to $WHISPER_REF..."
  git -C "$TARGET" fetch --tags origin
  git -C "$TARGET" checkout "$WHISPER_REF"
else
  echo "Cloning whisper.cpp ($WHISPER_REF) into $TARGET..."
  git clone --depth 1 --branch "$WHISPER_REF" "$WHISPER_REPO" "$TARGET"
fi

echo "Done. Run ./gradlew :app:assembleDebug to rebuild with on-device transcription."
