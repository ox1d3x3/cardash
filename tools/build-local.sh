#!/usr/bin/env bash
# Builds CarDash without Gradle/Android SDK, using only android.jar + Google's bundletool
# (which ships aapt2, D8 and apksig). Usage: tools/build-local.sh <android.jar> <bundletool.jar>
set -euo pipefail
ANDROID_JAR=$(realpath "$1"); BT=$(realpath "$2")
ROOT=$(cd "$(dirname "$0")/.." && pwd); SRC=$ROOT/app/src/main
OUT=$ROOT/build-local; rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex,bin}
VERSION_CODE=${VERSION_CODE:-3040099}; VERSION_NAME=${VERSION_NAME:-3.4}

unzip -q -o -j "$BT" linux/aapt2 -d "$OUT/bin"; chmod +x "$OUT/bin/aapt2"; AAPT2=$OUT/bin/aapt2

$AAPT2 compile --dir "$SRC/res" -o "$OUT/res/res.zip"
# Gradle injects the package from `namespace`; do the same here.
sed 's|<manifest |<manifest package="xyz.ox1de.cardash" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
$AAPT2 link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --java "$OUT/gen" --auto-add-overlay -o "$OUT/unsigned.apk" "$OUT/res/res.zip"

find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDROID_JAR${STUBS:+:$STUBS}" -d "$OUT/classes" @"$OUT/sources.txt"
java "$ROOT/tools/StripParams.java" "$OUT/classes"

java -cp "$BT" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

javac -cp "$BT" -d "$OUT/bin" "$ROOT/tools/Sign.java"
java -cp "$BT:$OUT/bin" Sign "$OUT/unsigned.apk" "$OUT/CarDash.apk" "$ROOT/keystore/cardash.p12" cardash cardash
echo "APK: $OUT/CarDash.apk"
