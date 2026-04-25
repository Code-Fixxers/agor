#!/usr/bin/env bash
# Build a debug APK and push it to the first attached device.
# Mirrors the spirit of apps/agor-ios/deploy.sh.

set -euo pipefail

cd "$(dirname "$0")"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH. Install Android platform tools."
  exit 1
fi

DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" = "0" ]; then
  echo "No attached devices. Plug in a phone with USB debugging enabled."
  exit 1
fi

./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "Build succeeded but $APK not found."
  exit 1
fi

echo "Installing $APK..."
adb install -r "$APK"
echo "Done. Launch from app drawer (Agor)."
