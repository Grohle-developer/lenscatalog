#!/usr/bin/env bash
# Host tests of the pure-Java parts: the on-screen keyboard's model (Keyboard)
# and UserLenses' cleaning and ids. javac against the Android platform jar
# (for the types the classes mention; nothing of Android runs here).
#
#   JAVA_HOME  ANDROID_SDK  PLATFORM_JAR   as app/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
: "${PLATFORM_JAR:=$ANDROID_SDK/platforms/android-28/android.jar}"
[ -f "$PLATFORM_JAR" ] || { echo "no $PLATFORM_JAR: set PLATFORM_JAR" >&2; exit 2; }
OUT="$PWD/out/unit-test"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVAC" -encoding UTF-8 --release 8 -Xlint:-options -nowarn -cp "$PLATFORM_JAR" -d "$OUT/classes" \
  src/com/lenscatalog/Keyboard.java src/com/lenscatalog/UserLenses.java src/com/lenscatalog/Catalog.java \
  src/com/lenscatalog/AppLog.java test/KeyboardTest.java
"$JAVA" -cp "$OUT/classes:$PLATFORM_JAR" com.lenscatalog.KeyboardTest
