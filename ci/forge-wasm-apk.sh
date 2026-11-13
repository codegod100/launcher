#!/usr/bin/env bash
# Builds the WASI launcher component (wasi/) and the wasmtime runtime APK (android-wasm/).
set -euo pipefail
cd /src

export PIXI_HOME=/opt/pixi
curl -fsSL https://pixi.sh/install.sh | bash
export PATH="$PIXI_HOME/bin:$PATH"

pixi run --manifest-path wasi/pixi.toml build
mkdir -p android-wasm/assets
cp wasi/build/launcher.wasm android-wasm/assets/launcher.wasm

curl -fsSL https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal --target aarch64-linux-android
export PATH="$HOME/.cargo/bin:$PATH"
cargo install cargo-apk --version 0.10.0 --locked

bash android-wasm/build.sh
cp android-wasm/target/release/apk/ultra-launcher-wasm.apk ultra-launcher-wasm.apk
