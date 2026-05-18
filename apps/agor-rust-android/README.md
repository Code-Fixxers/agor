# Agor Rust Android

Rust/Dioxus rewrite of the Agor Android client.

## Browser Sideview

Run the Agor + Hermes app as a local web target:

```bash
cd apps/agor-rust-android
./scripts/web.sh serve
```

Then open <http://127.0.0.1:6173>.

The script pins the Dioxus CLI and wasm-bindgen versions expected by this
workspace, installs them into `target/dev-tools`, and runs Dioxus with
`--platform web`. It keeps `Dioxus.toml` on `mobile` so Android remains the
default target.

Useful variants:

```bash
# Build the web bundle without serving it.
./scripts/web.sh build --locked

# Serve the Hermes-only binary.
AGOR_RUST_ANDROID_PACKAGE=hermes-only-app ./scripts/web.sh serve

# Use another port.
AGOR_RUST_ANDROID_PORT=6174 ./scripts/web.sh serve
```

## Android Device

Build, install, and launch the Rust/Dioxus APK from the Rust Android Nix shell:

```bash
cd apps/agor-rust-android
./scripts/android.sh run --locked
```

The helper uses the pinned Dioxus CLI, builds with `build-std`, patches the
Dioxus-generated Gradle project to the SDK available in the Nix shell, and
enables cleartext HTTP for local/dev Agor daemons.

## Whisper Models

The APK does not package `base.en` or any other Whisper model. Remote Whisper is
the default. If on-device fallback is needed, publish the model as a separate
artifact or download it to a device-local path, then set the artifact URL/path in
the app's Voice Transcription settings.

```bash
cd apps/agor-rust-android
./scripts/fetch-whisper-model.sh base.en
```
