#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/ndk" ]; then
    export ANDROID_HOME="/home/nandi/Android/Sdk"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    ANDROID_NDK_ROOT=$(ls -1d "$ANDROID_HOME/ndk/"* | tail -n 1)
    export ANDROID_NDK_ROOT
fi
BUILD_TOOLS=$(ls -1d "$ANDROID_HOME/build-tools/"* | tail -n 1)
PLATFORM=$(ls -1d "$ANDROID_HOME/platforms/"* | tail -n 1)

KEYSTORE="${CARGO_APK_RELEASE_KEYSTORE:-$PWD/../android/app/debug.keystore}"
export CARGO_APK_RELEASE_KEYSTORE="$KEYSTORE"
export CARGO_APK_RELEASE_KEYSTORE_PASSWORD="${CARGO_APK_RELEASE_KEYSTORE_PASSWORD:-android}"

cargo apk build --release
apk="target/release/apk/ultra-launcher-wasm.apk"

work="target/java-build"
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex"
javac --release 11 -classpath "$PLATFORM/android.jar" \
    -d "$work/classes" java/dev/ultra/launcher/wasm/LauncherActivity.java
"$BUILD_TOOLS/d8" --min-api 30 --output "$work/dex" \
    $(find "$work/classes" -name '*.class')

python3 - "$apk" "$work/dex/classes.dex" "$work/unaligned.apk" <<'PY'
import sys, zipfile
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        if info.filename.startswith("META-INF/") or info.filename == "classes.dex":
            continue
        zout.writestr(info, zin.read(info.filename))
    zout.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
PY

"$BUILD_TOOLS/zipalign" -f -p 4 "$work/unaligned.apk" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$apk" "$work/aligned.apk"

echo "Built $PWD/$apk ($(stat -c%s "$apk") bytes)"
