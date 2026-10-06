#!/usr/bin/env bash
# Builds out/lenscatalog.apk: pure Java, no JNI (the whole app is Sony
# framework calls by reflection + JSON + UI). javac, then d8 for API 10,
# signed v1 only — the camera's Android 2.3.7 knows no other scheme.
#
#   ./build.sh   -> out/lenscatalog.apk
#
# Toolchain (override via environment):
#   JAVA_HOME  ANDROID_SDK  BUILD_TOOLS  PLATFORM_JAR
# Signing: ANDROID_KEYSTORE_B64 + ANDROID_KEYSTORE_PASSWORD + ANDROID_KEY_ALIAS +
# ANDROID_KEY_PASSWORD sign with the real key; otherwise a throwaway key is made
# in debug.keystore (the camera installs any self-signed v1 APK, but not over an
# install signed with another key).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"

# ---- toolchain ----
: "${ANDROID_SDK:=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}}"
: "${JAVA_HOME:=$HOME/workspace/a7ii-fw/hidden_files/ghidra/jdk-21.0.12.1+1}"
: "${BUILD_TOOLS:=30.0.3}"
: "${PLATFORM_JAR:=$ANDROID_SDK/platforms/android-28/android.jar}"
BT="$ANDROID_SDK/build-tools/$BUILD_TOOLS"
API_VERSIONS="$(dirname "$PLATFORM_JAR")/data/api-versions.xml"
JAVA="$JAVA_HOME/bin"
# apksigner and keytool exec `java` from PATH
export PATH="$JAVA:$PATH"
AJ="$PLATFORM_JAR"
for f in "$AJ" "$API_VERSIONS" "$BT/aapt" "$BT/zipalign" "$BT/apksigner" "$BT/dexdump" "$BT/lib/d8.jar" \
         "$JAVA/javac"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' AndroidManifest.xml)"
echo "building LensCatalog $VERSION_NAME (versionCode $VERSION_CODE)"

rm -rf out/gen out/classes out/dex out/*.apk
mkdir -p out/gen out/classes out/dex

echo "[1/7] aapt R.java"
"$BT/aapt" package -f -m -J out/gen -M AndroidManifest.xml -S res -A assets -I "$AJ"
echo "[2/7] javac"
"$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -cp "$AJ" -d out/classes \
  out/gen/com/lenscatalog/R.java src/com/lenscatalog/*.java
echo "[3/7] d8 (min API 10)"
python3 tools/strip-params.py out/classes
find out/classes -name '*.class' > out/classes.txt
"$JAVA/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 10 \
  --lib "$AJ" --output out/dex "@out/classes.txt"
echo "[4/7] API check"
python3 tools/api-check.py "$BT/dexdump" "$API_VERSIONS" out/dex/classes.dex 10
echo "[5/7] aapt package + dex"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F out/unaligned.apk
( cd out/dex && "$BT/aapt" add ../unaligned.apk classes.dex )
echo "[6/7] zipalign"
"$BT/zipalign" -f 4 out/unaligned.apk out/aligned.apk

echo "[7/7] sign (v1 only, Gingerbread-compatible)"
# NOTE (2026-10-06): two hard lessons from the camera's Android 2.3.7, both found
# by installing on an API 10 emulator:
#  1. keytool (JDK 21) defaults to a SHA-384 self-signed cert -> rejected.
#     Pin -sigalg SHA256withRSA (Tweak uses sha256WithRSA and installs fine).
#  2. jarsigner (JDK 21) writes digest attributes as "SHA-1-Digest" (WITH hyphen),
#     which Gingerbread's JarVerifier rejects ("invalid digest"). apksigner writes
#     "SHA1-Digest" (no hyphen), like the working Tweak app. So: apksigner, not jarsigner.
sign_apk() { # $1=keystore $2=storepass $3=keypass $4=alias
  "$BT/apksigner" sign --ks "$1" --ks-pass "pass:$2" --key-pass "pass:$3" --ks-key-alias "$4" \
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out out/lenscatalog.apk out/aligned.apk
}
if [ -n "${ANDROID_KEYSTORE_B64:-}" ]; then
  KS="$(mktemp)"
  trap 'rm -f "$KS"' EXIT
  printf '%s' "$ANDROID_KEYSTORE_B64" | base64 -d > "$KS"
  # real keystores carry their own alias/passwords; apksigner reads them from env below
  KS_PASS="${ANDROID_KEYSTORE_PASSWORD:?}" KEY_PASS="${ANDROID_KEY_PASSWORD:?}" \
  "$BT/apksigner" sign --ks "$KS" --ks-key-alias "${ANDROID_KEY_ALIAS:?}" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out out/lenscatalog.apk out/aligned.apk
else
  echo "      no ANDROID_KEYSTORE_B64: signing with debug.keystore (cannot update an install signed otherwise)"
  [ -e debug.keystore ] || "$JAVA/keytool" -genkeypair -keystore debug.keystore -alias lenscatalog \
    -keyalg RSA -keysize 2048 -validity 10000 -sigalg SHA256withRSA -storepass android -keypass android -dname "CN=lenscatalog debug" 2>/dev/null
  sign_apk debug.keystore android android lenscatalog
fi
echo "      checking signature algorithm (camera only accepts SHA-1/SHA-256)"
RSA_FILE="$(unzip -Z1 out/lenscatalog.apk 'META-INF/*.RSA' | head -1)"
SIGALG="$(unzip -p out/lenscatalog.apk "$RSA_FILE" | openssl pkcs7 -inform DER -print_certs -text -noout 2>/dev/null | grep -m1 'Signature Algorithm' | sed 's/.*: //')"
echo "      signature algorithm: $SIGALG"
case "$SIGALG" in
  *sha1WithRSAEncryption*|*sha256WithRSAEncryption*) ;;
  *) echo "BUILD FAILED: $SIGALG is rejected by the camera" >&2; exit 1 ;;
esac
DIGESTALG="$(unzip -p out/lenscatalog.apk META-INF/MANIFEST.MF | grep -m1 -o 'SHA-\?[0-9]*-Digest' || true)"
echo "      manifest digest: $DIGESTALG"
[ "$DIGESTALG" = "SHA1-Digest" ] || { echo "BUILD FAILED: '$DIGESTALG' digests are rejected by the camera (need SHA1-Digest, no hyphen)" >&2; exit 1; }
"$BT/apksigner" verify --min-sdk-version 10 out/lenscatalog.apk
( cd out && sha256sum lenscatalog.apk > lenscatalog.apk.sha256 )
echo "BUILD OK: $ROOT/out/lenscatalog.apk ($VERSION_NAME, versionCode $VERSION_CODE, $(du -k out/lenscatalog.apk | cut -f1) KB)"
