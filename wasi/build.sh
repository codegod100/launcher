#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

QT2WASM_ROOT="${QT2WASM_ROOT:-$(python3 -c 'import qt2wasm, pathlib; print(pathlib.Path(qt2wasm.__file__).parent / "_data")')}"
QT2WASM_COMPAT_DIR="${QT2WASM_COMPAT_DIR:-$CONDA_PREFIX/opt/qt6-wasi/compat}"
MOC="${QT2WASM_MOC:-$QT_WASI_HOST_PATH/libexec/moc}"

BUILD_DIR="build"
GEN_DIR="$PWD/$BUILD_DIR/gen"
mkdir -p "$GEN_DIR/bindings" "$GEN_DIR/runtime"

# 1. Generate C guest bindings for `world gui` (latha:gui/gui + ultra:launcher/android).
wit-bindgen c --world gui --out-dir "$GEN_DIR/bindings" wit

# 2. Run moc on qt2wasm runtime headers and our window class.
"$MOC" "$QT2WASM_ROOT/runtime/wasieventdispatcher.h" -o "$GEN_DIR/runtime/moc_wasieventdispatcher.cpp"
"$MOC" "$QT2WASM_ROOT/gui/qt2wasmgui.h" -o "$GEN_DIR/runtime/moc_qt2wasmgui.cpp"
"$MOC" "src/launcher_window.h" -o "$GEN_DIR/moc_launcher_window.cpp"

# 3. Configure and link the core WASI reactor module.
cmake -S . -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$QT2WASM_QT_TOOLCHAIN" \
    -DQT2WASM_ROOT="$QT2WASM_ROOT" \
    -DQT2WASM_QT_PREFIX="$QT2WASM_QT_PREFIX" \
    -DQT2WASM_GEN_DIR="$GEN_DIR" \
    -DQT2WASM_COMPAT_DIR="$QT2WASM_COMPAT_DIR" \
    -DQT2WASM_GUI_WORLD=gui \
    -DQT2WASM_QPA_PLATFORM=qt2wasmgui

cmake --build "$BUILD_DIR"

# 4. Wrap as a WASI Preview 2 component.
wasm-tools component new "$BUILD_DIR/launcher.core.wasm" \
    --adapt "wasi_snapshot_preview1=$QT2WASM_ADAPTER" \
    -o "$BUILD_DIR/launcher.wasm"

echo "Built $PWD/$BUILD_DIR/launcher.wasm ($(stat -c%s "$BUILD_DIR/launcher.wasm") bytes)"
