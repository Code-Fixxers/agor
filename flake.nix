{
  description = "Agor build/run/publish workflow";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        sharedRuntimeInputs = with pkgs; [
          direnv
          bash
          curl
          coreutils
          gawk
          findutils
          git
          gnugrep
          gnused
          jq
          lsof
          procps
          python3
          strace
          scrcpy
          sqlite
          nodejs_22
          pnpm
          which
        ];

        # ------------------------------------------------------------------
        # Android build environment
        #
        # Composes a fully-pinned Android SDK + NDK + CMake from nixpkgs so
        # the APK can be built on NixOS without manual SDK setup. Versions
        # match apps/agor-android/app/build.gradle.kts.
        # ------------------------------------------------------------------
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ "35.0.0" ];
          platformToolsVersion = "35.0.2";
          ndkVersions = [ "27.1.12297006" ];
          cmakeVersions = [ "3.22.1" ];
          includeNDK = true;
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
        };

        androidSdkRoot = "${androidComposition.androidsdk}/libexec/android-sdk";
        androidNdkRoot = "${androidSdkRoot}/ndk/27.1.12297006";

        androidBuildInputs = with pkgs; [
          direnv
          jdk17
          android-tools
          gradle
          cacert
          findutils
          unzip
          lsof
          procps
          python3
          scrcpy
          sqlite
          androidComposition.androidsdk
          imagemagick
          strace
        ];

        androidDebugInputs = androidBuildInputs ++ (with pkgs; [
          bash
          coreutils
          gawk
          gnumake
          gnused
          jq
          procps
          which
        ]);

        androidEnvHook = ''
          export JAVA_HOME="${pkgs.jdk17.home}"
          export ANDROID_SDK_ROOT="${androidSdkRoot}"
          export ANDROID_HOME="${androidSdkRoot}"
          export ANDROID_NDK_HOME="${androidNdkRoot}"
          export ANDROID_NDK_ROOT="${androidNdkRoot}"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkRoot}/build-tools/35.0.0/aapt2 ''${GRADLE_OPTS:-}"
          export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/35.0.0:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:''${PATH:-}"
        '';

        # ------------------------------------------------------------------
        # Rust + Dioxus Android build environment
        #
        # Uses nixpkgs rustc/cargo with -Z build-std to cross-compile for
        # Android without needing pre-built target std libraries. A merged
        # sysroot provides rust-src from nixpkgs alongside the compiler.
        # ------------------------------------------------------------------
        rustLibSrc = pkgs.rustPlatform.rustLibSrc;

        # Detect the real rustc sysroot at shell entry time. On NixOS,
        # "${pkgs.rustc}" resolves to the *wrapper* derivation, not the
        # actual sysroot that contains lib/rustlib/. `rustc --print sysroot`
        # always returns the real path.
        setupRustAndroidSysroot = ''
          NIXPKGS_SYSROOT="$(rustc --print sysroot)"
          RUST_ANDROID_SYSROOT="''${XDG_CACHE_HOME:-$HOME/.cache}/rust-android-sysroot"
          MARKER_HASH=$(echo "$NIXPKGS_SYSROOT" | sha256sum | cut -c1-12)
          MARKER="$RUST_ANDROID_SYSROOT/.marker-$MARKER_HASH"

          if [ ! -f "$MARKER" ]; then
            rm -rf "$RUST_ANDROID_SYSROOT"
            mkdir -p "$RUST_ANDROID_SYSROOT/lib/rustlib/src/rust"
            mkdir -p "$RUST_ANDROID_SYSROOT/bin"
            for f in "$NIXPKGS_SYSROOT"/bin/*; do
              ln -sf "$f" "$RUST_ANDROID_SYSROOT/bin/"
            done
            mkdir -p "$RUST_ANDROID_SYSROOT/lib"
            for f in "$NIXPKGS_SYSROOT"/lib/*; do
              base="$(basename "$f")"
              [ "$base" = "rustlib" ] && continue
              ln -sf "$f" "$RUST_ANDROID_SYSROOT/lib/$base"
            done
            mkdir -p "$RUST_ANDROID_SYSROOT/lib/rustlib"
            for f in "$NIXPKGS_SYSROOT"/lib/rustlib/*; do
              base="$(basename "$f")"
              [ "$base" = "src" ] && continue
              ln -sf "$f" "$RUST_ANDROID_SYSROOT/lib/rustlib/$base"
            done
            ln -sf "${rustLibSrc}" "$RUST_ANDROID_SYSROOT/lib/rustlib/src/rust/library"
            cp "${rustLibSrc}/Cargo.lock" "$RUST_ANDROID_SYSROOT/lib/rustlib/src/rust/Cargo.lock"
            touch "$MARKER"
          fi

          REAL_RUSTC="$NIXPKGS_SYSROOT/bin/rustc"
          RUSTC_ANDROID="$RUST_ANDROID_SYSROOT/bin/rustc-android"
          cat > "$RUSTC_ANDROID" <<RCEOF
#!/bin/sh
exec "$REAL_RUSTC" --sysroot "$RUST_ANDROID_SYSROOT" "\$@"
RCEOF
          chmod +x "$RUSTC_ANDROID"
          export RUSTC="$RUSTC_ANDROID"
          unset RUSTC_WRAPPER
        '';

        rustAndroidBuildInputs = [
          pkgs.rustc
          pkgs.cargo
          pkgs.rustPlatform.rustLibSrc
          pkgs.jdk17
          pkgs.pkg-config
          pkgs.openssl
          androidComposition.androidsdk
        ] ++ (with pkgs; [ git findutils coreutils bash cacert ]);

        ndkToolchainBin = "${androidNdkRoot}/toolchains/llvm/prebuilt/linux-x86_64/bin";

        rustAndroidEnvHook = ''
          ${androidEnvHook}
          export PATH="${ndkToolchainBin}:''${PATH:-}"

          # Tell the `cc` crate (used by ring, openssl-sys, etc.) which
          # compiler and archiver to use for each Android target. Without
          # these, cc falls back to the host x86_64 compiler and produces
          # incompatible object files.
          export CC_aarch64_linux_android="${ndkToolchainBin}/aarch64-linux-android21-clang"
          export AR_aarch64_linux_android="${ndkToolchainBin}/llvm-ar"
          export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${ndkToolchainBin}/aarch64-linux-android21-clang"

          export CC_armv7_linux_androideabi="${ndkToolchainBin}/armv7a-linux-androideabi21-clang"
          export AR_armv7_linux_androideabi="${ndkToolchainBin}/llvm-ar"
          export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="${ndkToolchainBin}/armv7a-linux-androideabi21-clang"

          export CC_x86_64_linux_android="${ndkToolchainBin}/x86_64-linux-android21-clang"
          export AR_x86_64_linux_android="${ndkToolchainBin}/llvm-ar"
          export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${ndkToolchainBin}/x86_64-linux-android21-clang"

          export CC_i686_linux_android="${ndkToolchainBin}/i686-linux-android21-clang"
          export AR_i686_linux_android="${ndkToolchainBin}/llvm-ar"
          export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="${ndkToolchainBin}/i686-linux-android21-clang"

          ${setupRustAndroidSysroot}
        '';

        buildRustAndroidApkScript = pkgs.writeShellApplication {
          name = "build-rust-android-apk";
          runtimeInputs = rustAndroidBuildInputs ++ (with pkgs; [ unzip ]);
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            APP_DIR="$ROOT/apps/agor-rust-android"

            if [ ! -d "$APP_DIR" ]; then
              echo "Could not find apps/agor-rust-android from: $ROOT"
              exit 1
            fi

            ${rustAndroidEnvHook}

            VERSION_CODE=$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 0)
            SHORT_SHA=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)
            export VERSION_CODE VERSION_NAME="$SHORT_SHA"
            export RUSTC_BOOTSTRAP=1

            echo "Building Rust Android APK (version $VERSION_CODE / $SHORT_SHA)..."
            cd "$APP_DIR"

            ./scripts/android.sh build --locked

            APK_SRC="$APP_DIR/target/dx/agor-hermes-app/debug/android/app/app/build/outputs/apk/debug/app-debug.apk"
            if [ ! -f "$APK_SRC" ]; then
              echo "Expected APK not produced at $APK_SRC" >&2
              exit 1
            fi

            DEST="$ROOT/agor-rust-android-$SHORT_SHA.apk"
            cp "$APK_SRC" "$DEST"
            echo ""
            echo "Built Rust Android APK: $DEST"
          '';
        };

        buildAgorAndroidApkScript = pkgs.writeShellApplication {
          name = "build-agor-android-apk";
          runtimeInputs = androidBuildInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            APP_DIR="$ROOT/apps/agor-android"

            if [ ! -d "$APP_DIR" ]; then
              echo "❌ Could not find apps/agor-android from: $ROOT"
              exit 1
            fi

            ${androidEnvHook}

            # Optionally vendor whisper.cpp for on-device transcription.
            # Skip with SKIP_WHISPER=1 for a faster build that falls back to
            # Android's built-in SpeechRecognizer.
            if [ -z "''${SKIP_WHISPER:-}" ]; then
              echo "📦 Vendoring whisper.cpp (set SKIP_WHISPER=1 to skip)..."
              (cd "$APP_DIR" && bash scripts/sync-whisper.sh)
            else
              echo "⏭️  Skipping whisper.cpp vendor (SKIP_WHISPER set)."
            fi

            echo ""
            echo "🔨 Building debug APK with Gradle..."
            cd "$APP_DIR"
            chmod +x ./gradlew
            ./gradlew :app:assembleHermesAgorDebug --no-daemon --stacktrace --no-configuration-cache

            APK_SRC="$APP_DIR/app/build/outputs/apk/hermesAgor/debug/app-hermesAgor-debug.apk"
            if [ ! -f "$APK_SRC" ]; then
              echo "❌ Expected APK not produced at $APK_SRC"
              exit 1
            fi

            SHORT_SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
            DEST="$ROOT/agor-android-debug-$SHORT_SHA.apk"
            cp "$APK_SRC" "$DEST"

            echo ""
            echo "✅ APK built: $DEST"
            echo "   Size: $(du -h "$DEST" | cut -f1)"
            echo "   Install: adb install -r $DEST"
          '';
        };

        agorAndroidSmokeScript = pkgs.writeShellApplication {
          name = "agor-android-smoke";
          runtimeInputs = androidDebugInputs;
          text = ''
            set -euo pipefail

            ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            SCRIPT="$ROOT/scripts/agor-android-smoke.sh"

            if [ ! -x "$SCRIPT" ]; then
              echo "Missing executable smoke script: $SCRIPT" >&2
              exit 2
            fi

            ${androidEnvHook}
            exec "$SCRIPT" "$@"
          '';
        };

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

            if [ -z "$NPM_TOKEN" ]; then
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
//registry.npmjs.org/:_authToken=$NPM_TOKEN
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
        packages = {
          build-agor-live-wrapper = buildScript;
          run-agor-live-wrapper = runScript;
          publish-agor-live-wrapper = publishScript;
          build-agor-android-apk = buildAgorAndroidApkScript;
          build-rust-android-apk = buildRustAndroidApkScript;
          agor-android-smoke = agorAndroidSmokeScript;
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
          build-agor-android-apk = {
            type = "app";
            program = "${buildAgorAndroidApkScript}/bin/build-agor-android-apk";
          };
          build-rust-android-apk = {
            type = "app";
            program = "${buildRustAndroidApkScript}/bin/build-rust-android-apk";
          };
          agor-android-smoke = {
            type = "app";
            program = "${agorAndroidSmokeScript}/bin/agor-android-smoke";
          };
          default = self.apps.${system}.run-agor-live;
        };

        devShells = {
          default = pkgs.mkShell {
            packages = sharedRuntimeInputs ++ androidDebugInputs;
            shellHook = ''
              ${androidEnvHook}
              echo ""
              echo "Agor dev shell: adb, aapt, Gradle, Node, pnpm, jq, sqlite available."
              echo "Android diagnostics: adb shell dumpsys/gfxinfo, scrcpy, strace, lsof, sqlite, python3."
              echo "Shell tooling: direnv available."
              echo "Android smoke: nix run .#agor-android-smoke"
              echo ""
            '';
          };

          android = pkgs.mkShell {
            packages = androidDebugInputs;
            shellHook = ''
              ${androidEnvHook}
              echo ""
              echo "   Diagnostics: adb shell dumpsys/gfxinfo, scrcpy, strace, lsof, sqlite, imagemagick, python3"
              echo "   Shell tooling: direnv"
              echo "📱 Agor Android dev shell (Kotlin/Gradle)"
              echo "   ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
              echo "   ANDROID_NDK_HOME = $ANDROID_NDK_HOME"
              echo "   JAVA_HOME        = $JAVA_HOME"
              echo ""
              echo "   To build the debug APK:"
              echo "     cd apps/agor-android"
              echo "     bash scripts/sync-whisper.sh   # optional, on-device transcription"
              echo "     ./gradlew :app:assembleHermesAgorDebug"
              echo "     ./gradlew :app:assembleHermesOnlyDebug"
              echo ""
              echo "   Or one-shot from anywhere in the repo:"
              echo "     nix run .#build-agor-android-apk"
              echo ""
              echo "   Device smoke/perf harness:"
              echo "     nix run .#agor-android-smoke"
              echo ""
            '';
          };

          rust-android = pkgs.mkShell {
            packages = rustAndroidBuildInputs;
            RUSTC_BOOTSTRAP = "1";
            shellHook = ''
              ${rustAndroidEnvHook}
              echo ""
              echo "Rust Android dev shell (build-std cross-compilation)"
              echo "  cargo: $(cargo --version)"
              echo "  rustc: $($RUSTC --version)"
              echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
              echo "  ANDROID_NDK_HOME = $ANDROID_NDK_HOME"
              echo "  RUSTC = $RUSTC (sysroot wrapper)"
              echo ""
              echo "  Build:  RUSTC_BOOTSTRAP=1 cargo build --target aarch64-linux-android -Z build-std=std,panic_abort"
              echo "  Check:  RUSTC_BOOTSTRAP=1 cargo check --target aarch64-linux-android -Z build-std=std,panic_abort"
              echo "  One-shot: nix run .#build-rust-android-apk"
              echo ""
            '';
          };
        };
      });
}
