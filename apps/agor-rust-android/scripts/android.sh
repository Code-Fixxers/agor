#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL_ROOT="$ROOT/target/dev-tools"
BIN_DIR="$TOOL_ROOT/bin"
DX_VERSION="${DX_VERSION:-0.6.3}"
PACKAGE="${AGOR_RUST_ANDROID_PACKAGE:-agor-hermes-app}"
ARCH="${AGOR_RUST_ANDROID_ARCH:-arm64}"
COMPILE_SDK="${AGOR_RUST_ANDROID_COMPILE_SDK:-35}"
TARGET_SDK="${AGOR_RUST_ANDROID_TARGET_SDK:-35}"
BUILD_TOOLS="${AGOR_RUST_ANDROID_BUILD_TOOLS:-35.0.0}"
MODE="${1:-build}"

if [[ "$MODE" == "build" || "$MODE" == "install" || "$MODE" == "run" ]]; then
  shift || true
fi

export PATH="$BIN_DIR:$PATH"
export NO_DOWNLOADS=1
export XDG_DATA_HOME="${XDG_DATA_HOME:-$ROOT/target/xdg-data}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$ROOT/target/xdg-cache}"
export RUSTC_BOOTSTRAP="${RUSTC_BOOTSTRAP:-1}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS="${CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_I686_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_I686_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"

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
}

ensure_cargo_tool() {
  local bin="$1"
  local crate="$2"
  local version="$3"

  if command -v "$bin" >/dev/null 2>&1 && "$bin" --version | grep -q "$version"; then
    return
  fi

  if [[ "$crate" == "dioxus-cli" ]]; then
    cargo install --git https://github.com/DioxusLabs/dioxus \
      --tag "v$version" "$crate" --locked --root "$TOOL_ROOT"
  else
    cargo install "$crate" --version "$version" --locked --root "$TOOL_ROOT"
  fi
}

configure_android_rustflags() {
  case " ${RUSTFLAGS:-} " in
    *"max-page-size=16384"*) ;;
    *) export RUSTFLAGS="${RUSTFLAGS:-} -Clink-arg=-Wl,-z,max-page-size=16384" ;;
  esac
}

patch_generated_android_project() {
  local gradle_root="$1"
  local app_gradle="$gradle_root/app/build.gradle.kts"
  local manifest="$gradle_root/app/src/main/AndroidManifest.xml"
  local wry_activity="$gradle_root/app/src/main/kotlin/dev/dioxus/main/WryActivity.kt"

  if [[ ! -f "$app_gradle" ]]; then
    echo "Missing generated Gradle file: $app_gradle" >&2
    return 1
  fi

  perl -0pi -e "s/compileSdk = \\d+/compileSdk = $COMPILE_SDK/" "$app_gradle"
  perl -0pi -e "s/targetSdk = \\d+/targetSdk = $TARGET_SDK/" "$app_gradle"

  if ! grep -q 'buildToolsVersion' "$app_gradle"; then
    perl -0pi -e "s/(compileSdk = $COMPILE_SDK\\n)/\$1    buildToolsVersion = \"$BUILD_TOOLS\"\\n/" "$app_gradle"
  fi

  if [[ -f "$manifest" ]] && ! grep -q 'usesCleartextTraffic' "$manifest"; then
    perl -0pi -e 's/<application /<application android:usesCleartextTraffic="true" /' "$manifest"
  fi

  if [[ -f "$wry_activity" ]]; then
    perl -0pi -e 's/return info\.versionName$/return info.versionName ?: ""/mg' "$wry_activity"
  fi
}

build_apk() {
  configure_openssl
  ensure_cargo_tool dx dioxus-cli "$DX_VERSION"
  configure_openssl
  configure_android_rustflags

  cd "$ROOT"

  set +e
  dx build \
    --platform android \
    --package "$PACKAGE" \
    --arch "$ARCH" \
    --device true \
    -- "$@" -Z build-std=std,panic_abort
  local dx_status=$?
  set -e

  local gradle_root="$ROOT/target/dx/$PACKAGE/debug/android/app"
  if [[ $dx_status -ne 0 ]]; then
    if [[ ! -d "$gradle_root" ]]; then
      return "$dx_status"
    fi
    echo "dx generated Android project but Gradle assembly failed; patching generated SDK settings."
  fi

  patch_generated_android_project "$gradle_root"

  cd "$gradle_root"
  ./gradlew :app:assembleDebug

  local apk="$gradle_root/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "APK was not produced at: $apk" >&2
    return 1
  fi

  echo "$apk"
}

install_apk() {
  local apk
  apk="$(build_apk "$@" | tail -n 1)"
  adb install -r "$apk"
}

run_app() {
  install_apk "$@"
  adb shell am start -n com.example.AgorHermesApp/dev.dioxus.main.MainActivity
}

case "$MODE" in
  build)
    build_apk "$@"
    ;;
  install)
    install_apk "$@"
    ;;
  run)
    run_app "$@"
    ;;
  *)
    echo "Usage: $0 [build|install|run] [cargo args...]" >&2
    exit 2
    ;;
esac
