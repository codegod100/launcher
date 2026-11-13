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
manifest="target/release/apk/AndroidManifest.xml"

work="target/java-build"
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$work/res"

python3 - "$manifest" "$work/AndroidManifest.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
xml = open(src, "r", encoding="utf-8").read()
service = """        <service
            android:name="dev.ultra.launcher.wasm.RecentsService"
            android:label="@string/recents_service_label"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/recents_service" />
        </service>
"""
if "dev.ultra.launcher.wasm.RecentsService" not in xml:
    xml = xml.replace("</application>", service + "    </application>")
open(dst, "w", encoding="utf-8").write(xml)
PY

"$BUILD_TOOLS/aapt2" compile --dir res -o "$work/res.zip"
"$BUILD_TOOLS/aapt2" link \
    --manifest "$work/AndroidManifest.xml" \
    -I "$PLATFORM/android.jar" \
    "$work/res.zip" \
    -o "$work/res.apk" \
    --auto-add-overlay

javac --release 11 -classpath "$PLATFORM/android.jar" \
    -d "$work/classes" \
    java/dev/ultra/launcher/wasm/LauncherActivity.java \
    java/dev/ultra/launcher/wasm/RecentsService.java
"$BUILD_TOOLS/d8" --min-api 30 --output "$work/dex" \
    $(find "$work/classes" -name '*.class')

python3 - "$apk" "$work/res.apk" "$work/dex/classes.dex" "$work/unaligned.apk" <<'PY'
import sys, zipfile
src, res_apk, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with zipfile.ZipFile(src, "r") as zsrc, \
     zipfile.ZipFile(res_apk, "r") as zres, \
     zipfile.ZipFile(dst, "w") as zout:
    for info in zres.infolist():
        if info.filename == "resources.arsc":
            zout.writestr(info, zres.read(info.filename), compress_type=zipfile.ZIP_STORED)
        else:
            zout.writestr(info, zres.read(info.filename))
    for info in zsrc.infolist():
        if (info.filename.startswith("META-INF/")
                or info.filename == "classes.dex"
                or info.filename == "AndroidManifest.xml"
                or info.filename == "resources.arsc"
                or info.filename.startswith("res/")):
            continue
        zout.writestr(info, zsrc.read(info.filename))
    zout.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
PY

"$BUILD_TOOLS/zipalign" -f -p 4 "$work/unaligned.apk" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$apk" "$work/aligned.apk"

echo "Built $PWD/$apk ($(stat -c%s "$apk") bytes)"
