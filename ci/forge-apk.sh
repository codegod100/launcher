#!/usr/bin/env bash
# Builds the Qt launcher APK (qt/) and publishes it as a forge release: build-<versionCode>,
# holding ultra-launcher.apk and version.json, made a draft first and published once both
# files are up, so releases/latest never lacks them. Runs in .forge/jobs.toml's "apk" job.
set -euo pipefail
cd /src

QT_VERSION=6.8.3
NDK=26.1.10909125
# Minutes since the epoch: always above the GitHub builds' run numbers and always rising.
VERSION_CODE=$(( $(date +%s) / 60 ))

echo "== tools"
apt-get update -qq
# The host Qt tools (moc, qmlimportscanner, qmlcachegen) need glib, GL and friends.
apt-get install -y -qq --no-install-recommends python3-pip python3-venv ninja-build cmake unzip curl ca-certificates \
  libglib2.0-0t64 libgl1 libegl1 libopengl0 libfontconfig1 libfreetype6 libxkbcommon0 libdbus-1-3 >/dev/null
python3 -m venv /opt/aqt && /opt/aqt/bin/pip install -q aqtinstall

echo "== Qt $QT_VERSION (android arm64 + host)"
/opt/aqt/bin/aqt install-qt linux android "$QT_VERSION" android_arm64_v8a -O /opt/Qt --autodesktop >/dev/null
QT_ROOT=/opt/Qt/$QT_VERSION/android_arm64_v8a
QT_HOST=/opt/Qt/$QT_VERSION/gcc_64

echo "== Android SDK + NDK $NDK"
export ANDROID_SDK_ROOT=/opt/android-sdk
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
curl -fsSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline.zip -d "$ANDROID_SDK_ROOT/cmdline-tools" && mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "ndk;$NDK" "platforms;android-35" "build-tools;35.0.0" >/dev/null
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/$NDK

echo "== build (versionCode $VERSION_CODE)"
# Signed with the committed key, like every earlier build, so it installs over them.
export QT_ANDROID_KEYSTORE_PATH=/src/android/app/debug.keystore
export QT_ANDROID_KEYSTORE_ALIAS=androiddebugkey
export QT_ANDROID_KEYSTORE_STORE_PASS=android
export QT_ANDROID_KEYSTORE_KEY_PASS=android
"$QT_ROOT/bin/qt-cmake" -S qt -B build-android -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" -DANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
  -DQT_HOST_PATH="$QT_HOST" -DQT_ANDROID_SIGN_APK=ON -DVERSION_CODE="$VERSION_CODE"
cmake --build build-android --target apk
apk=$(find build-android -name "*-signed.apk" | head -1)
test -n "$apk" || { find build-android -name "*.apk"; exit 1; }
cp "$apk" ultra-launcher.apk
printf '{"versionCode": %s, "commit": "%s"}\n' "$VERSION_CODE" "${FORGE_SHA:-}" > version.json

echo "== release build-$VERSION_CODE"
api="${FORGE_URL:-https://fileapi.fastapicloud.dev}"
api="${api%/}/api/forge/repos/${FORGE_REPO:-nandi.uk/launcher}/releases"
echo "env: FORGE_REPO=${FORGE_REPO:-} FORGE_URL=${FORGE_URL:-} FORGE_API=${FORGE_API:-}"
auth=(-H "Authorization: Bearer $FORGE_TOKEN")
tag="build-$VERSION_CODE"
curl -fsS "${auth[@]}" -H "Content-Type: application/json" -X POST "$api" \
  -d "{\"tag\": \"$tag\", \"name\": \"ultra Home build $VERSION_CODE\", \"ref\": \"${FORGE_SHA:-}\", \"draft\": true, \"notes\": \"Qt launcher built from ${FORGE_SHA:-?}.\"}" >/dev/null
curl -fsS "${auth[@]}" -T ultra-launcher.apk "$api/$tag/assets/ultra-launcher.apk" >/dev/null
curl -fsS "${auth[@]}" -T version.json "$api/$tag/assets/version.json" >/dev/null
curl -fsS "${auth[@]}" -H "Content-Type: application/json" -X PATCH "$api/$tag" -d '{"draft": false}' >/dev/null
echo "published $tag"
