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
