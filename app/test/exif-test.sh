#!/usr/bin/env bash
# Host test of ExifWriter on camera-like JPEGs: javac + python3 (PIL) + exiftool.
#   app/test/exif-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/out/exif-test"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$JAVAC" -encoding UTF-8 --release 8 -Xlint:-options -d "$OUT/classes" src/com/lenscatalog/ExifWriter.java test/ExifCli.java
python3 test/exif_fixtures.py "$OUT/fixtures"
python3 test/exif_check.py "$OUT/classes" "$OUT/fixtures"
