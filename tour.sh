#!/usr/bin/env bash
# LensCatalog: the whole workflow, end to end, on aintfilm-sony's A7 II simulator
# (Android 2.3.7 at 640x480, keys only), in Spanish, with screenshots and checks.
#
#   ./tour.sh [apk]          default: app/out/lenscatalog.apk
#
# AINTFILM_SONY  checkout of aintfilm-sony (its sim/sim.sh): default ~/aintfilm-sony
# OUT            where screens, photos and the report go: default out/tour
#
# What it does, as a photographer would: picks a prime from the catalogue, stars it,
# turns lens correction on and tunes it, applies; "shoots" (camera-like JPEGs land in
# DCIM with the time they were taken); a zoom; a lens from manual data; then Write
# EXIF, and pulls the photographs to check with exiftool that each one got its own
# lens and kept the camera's EXIF. Then favourites, diagnostics, the log, two models
# of one name on two mounts, an electronic lens (sim/LENS.TXT), a catalogue on the
# card, the control wheel, a page jump and auto-exit. Every check prints ok / FAIL.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SIMDIR="${AINTFILM_SONY:-$HOME/aintfilm-sony}/sim"
SIM="$SIMDIR/sim.sh"
APK="${1:-$ROOT/app/out/lenscatalog.apk}"
OUT="${OUT:-$ROOT/out/tour}"
export SIM_OUT="$OUT"
ADB="${AINTFILM_SIM_HOME:-$HOME/.cache/aintfilm-sony-sim}/sdk/platform-tools/adb"
PKG=com.lenscatalog
CARD=/mnt/sdcard
DCIM=$CARD/DCIM/100MSDCF
[ -x "$SIM" ] || { echo "no $SIM: set AINTFILM_SONY to an aintfilm-sony checkout" >&2; exit 2; }
[ -f "$APK" ] || { echo "no $APK: build it with app/build.sh" >&2; exit 2; }

say() { echo "[tour] $*"; }
adb_() { "$ADB" -e "$@"; }
fails=0
check() { # description, then a command that succeeds when it holds
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "  ok   $d"; else echo "  FAIL $d"; fails=$((fails + 1)); fi
}
logged() { adb_ logcat -d -s LensCatalog:I | tr -d '\r' | grep -qF -- "$1"; }
no_crash() { ! adb_ logcat -d | grep -qE "FATAL EXCEPTION|AndroidRuntime.*E/"; }
key() { "$SIM" key "$@" >/dev/null; sleep "${KEYWAIT:-1.5}"; }
shot() { sleep 0.8; "$SIM" shot "$1" >/dev/null && say "screen $1"; }
pid() { adb_ shell ps | tr -d '\r' | awk -v p=$PKG '$NF == p {print $2}'; }
# Android 2.3 has no `am force-stop`: a fresh start kills the process first
start() {
  local p; p="$(pid)"; [ -z "$p" ] || adb_ shell kill "$p"; sleep 1
  adb_ logcat -c
  adb_ shell am start -n $PKG/.MainActivity >/dev/null
  for _ in $(seq 1 60); do logged "simulator mode" && break; sleep 1; done
  sleep 3
  # the first frame can keep the status bar's old pixels: one key pair redraws it
  "$SIM" key down >/dev/null; "$SIM" key up >/dev/null; sleep 1.5
}
focused() { adb_ shell dumpsys window windows | tr -d '\r' | grep -E "mCurrentFocus" | grep -q "$1"; }
# the shutter: a camera-like JPEG written now, so it carries the time it was "taken"
shoot() { adb_ shell "cat $CARD/LCSIM/CAMERA.JPG > $DCIM/$1"; say "shot $1"; }
pref() { adb_ shell cat /data/data/$PKG/shared_prefs/lenscatalog.xml | tr -d '\r'; }
has_pref() { pref | grep -qF -- "$1"; }
wait_log() { for _ in $(seq 1 "${2:-120}"); do logged "$1" && return 0; sleep 1; done; return 1; }

# ------------------------------------------------------------------ setup
"$SIM" boot
"$SIM" install "$APK"
if [ "$(adb_ shell getprop persist.sys.language | tr -d '\r')" != "es" ]; then
  say "the guest in Spanish (the framework restarts)"
  adb_ shell setprop persist.sys.language es; adb_ shell setprop persist.sys.country ES
  adb_ shell stop; sleep 2; adb_ shell start; sleep 90
fi
adb_ shell pm clear $PKG >/dev/null
rm -rf "$OUT/shots" "$OUT/photos" && mkdir -p "$OUT/shots" "$OUT/photos" "$OUT/fixtures"
python3 "$ROOT/app/test/exif_fixtures.py" "$OUT/fixtures"
adb_ shell "rm -r $DCIM; rm -r $CARD/LCSIM; rm $CARD/AINTFILM/SIM/LENS.TXT; rm $CARD/DCIM/LENSES/lenses.json" >/dev/null 2>&1
adb_ shell "mkdir $CARD/DCIM; mkdir $DCIM; mkdir $CARD/LCSIM; mkdir $CARD/AINTFILM; mkdir $CARD/AINTFILM/SIM" >/dev/null 2>&1
adb_ push "$OUT/fixtures/sony.jpg" $CARD/LCSIM/CAMERA.JPG >/dev/null 2>&1

# ------------------------------------------------------------------ a prime, starred, with correction
say "1. Home, auto-exit off (so the results stay up for their screenshots)"
start
shot 01-inicio
key up enter
shot 02-herramientas
check "auto-exit off, stored" has_pref '<boolean name="auto_exit" value="false" />'
key down                                   # wraps to Write EXIF

say "2. Catalogue -> Helios -> 44 58mm 1:2"
key down down down down down down down down down
shot 03-catalogo
key enter down
shot 04-modelos
key enter
shot 05-ficha
key down down down enter up up enter        # star it, then correction ON (the selection stays put)
shot 06-favorito-correccion
key down enter right right right down down down down down left left
shot 07-editor
key menu enter                              # back to the page (on Apply), apply
shot 08-aplicado
check "IBIS 58 mm" logged "SIM setAntiHandBlurFocalLength(58)"
check "EXIF lensName + focal 58" logged 'lensName="Helios 44 58mm 1:2", focal=58/1'
check "correction ON with shading-w=3, chroma-r=-2" logged 'shading-w=3 shading-wm=0 shading-cr=0 shading-cb=0 shading-cm=0 chroma-r=-2'
sleep 3; shoot DSC00001.JPG; sleep 1; shoot DSC00002.JPG

# ------------------------------------------------------------------ a zoom
say "3. Last used on home; Canon EF 100-200mm f/4.5A (a zoom)"
key menu
shot 09-inicio-ultimo
key down down down down down enter enter
shot 10-ficha-zoom
key enter
shot 11-aplicado-zoom
check "zoom: IBIS at the wide end (100)" logged "SIM setAntiHandBlurFocalLength(100)"
check "zoom: no numeric EXIF focal" logged 'focal=SKIPPED (zoom range)'
sleep 3; shoot DSC00003.JPG

# ------------------------------------------------------------------ manual data
say "4. Manual data: 35 mm f/2.8"
key menu up up up up enter
shot 12-manual
KEYWAIT=0.4 key left left left left left left left left left left left left left left left
key down right right right right right right right
shot 13-manual-valores
key down enter
shot 14-ficha-manual
key enter
check "manual lens applied" logged 'lensName="Manual 35mm f/2.8", focal=35/1'
sleep 3; shoot DSC00004.JPG

# ------------------------------------------------------------------ Write EXIF
say "5. Write EXIF to photos"
sleep 2
key menu enter
wait_log "tagger: done" 120 || true
sleep 1
shot 15-etiquetado
check "tagger: 4 tagged, 0 failed" logged "tagger: done tagged=4 already=0 failed=0"
for n in 1 2 3 4; do adb_ pull $DCIM/DSC0000$n.JPG "$OUT/photos/DSC0000$n.JPG" >/dev/null 2>&1; done
if command -v exiftool >/dev/null; then
  python3 - "$OUT/photos" "$OUT/fixtures/sony.jpg" <<'PY' || fails=$((fails + 1))
import json, subprocess, sys
d, orig = sys.argv[1], sys.argv[2]
def tags(p):
    return json.loads(subprocess.run(["exiftool", "-j", "-G1", "-n", p], capture_output=True, text=True).stdout)[0]
o = tags(orig)
want = {"DSC00001.JPG": ("Helios 44 58mm 1:2", 58, 2), "DSC00002.JPG": ("Helios 44 58mm 1:2", 58, 2),
        "DSC00003.JPG": ("Canon EF 100-200mm f/4.5A", o.get("ExifIFD:FocalLength"), 4.5),
        "DSC00004.JPG": ("Manual 35mm f/2.8", 35, 2.8)}
bad = 0
for name, (lens, focal, f) in sorted(want.items()):
    t = tags(d + "/" + name)
    got = (t.get("ExifIFD:LensModel"), t.get("ExifIFD:FocalLength"), t.get("ExifIFD:FNumber"))
    kept = all(t.get(k) == o.get(k) for k in ("ExifIFD:ISO", "ExifIFD:ExposureTime", "ExifIFD:DateTimeOriginal",
                                              "IFD0:Make", "IFD0:Model", "IFD1:ThumbnailLength"))
    ok = got[0] == lens and got[1] == focal and abs((got[2] or 0) - f) < 1e-6 and kept
    bad += not ok
    print("  %s   %s: LensModel=%r FocalLength=%s FNumber=%s, camera EXIF kept=%s"
          % ("ok" if ok else "FAIL", name, got[0], got[1], got[2], kept))
sys.exit(1 if bad else 0)
PY
else
  say "(no exiftool: the photos' EXIF is not checked)"
fi
check "no crash so far" no_crash

# ------------------------------------------------------------------ favourites, diagnostics, log
say "6. Favourites, diagnostics, log"
key menu down enter
shot 16-favoritos
key enter menu menu                         # open it from there, back, back
key up up up up enter
shot 17-diagnostico
key menu down enter
shot 18-registro

# ------------------------------------------------------------------ two models of one name
say "7. Voigtlander Color Skopar 20mm: K, EF and F share one name; the EF one must open"
key menu up up up up up up up enter down down down
shot 19-modelos-mismo-nombre
key enter
shot 20-ficha-montura-ef
key enter
check "EF version applied (last used = its id)" has_pref 'aspheric-2</string>'
check "no crash so far" no_crash

# ------------------------------------------------------------------ an electronic lens
say "8. An electronic lens (sim: /AINTFILM/SIM/LENS.TXT): nothing to do, and its photos are skipped"
adb_ shell "echo 'FE 28-70mm F3.5-5.6 OSS' > $CARD/AINTFILM/SIM/LENS.TXT"
start
shot 21-objetivo-electronico
check "electronic lens read" logged "SIM electronic lens from LENS.TXT: FE 28-70mm F3.5-5.6 OSS"
key menu
sleep 3; shoot DSC00005.JPG
adb_ shell rm $CARD/AINTFILM/SIM/LENS.TXT
start
sleep 2
key enter
wait_log "tagger: done" 120 || true
sleep 1
shot 22-etiquetado-electronico
check "the electronic lens's photo skipped" logged "skippedEl=1"
adb_ pull $DCIM/DSC00005.JPG "$OUT/photos/DSC00005.JPG" >/dev/null 2>&1
if command -v exiftool >/dev/null; then
  check "DSC00005 keeps the camera's own LensModel" bash -c "exiftool -s3 -LensModel '$OUT/photos/DSC00005.JPG' | grep -qx -- '----'"
fi

# ------------------------------------------------------------------ a catalogue on the card
say "9. A catalogue of your own on the card (/DCIM/LENSES/lenses.json)"
cat > "$OUT/lenses.json" <<'JSON'
{"version":2,"brands":[{"brand":"Mi taller","models":[
 {"id":"taller-helios-44-2","model":"Helios 44-2 58mm f/2 (copia de la abuela)","mount":"M42","type":"prime","max_aperture":2.0,"focal":58},
 {"id":"taller-tair-11a","model":"Tair-11A 135mm f/2.8","mount":"M42","type":"prime","max_aperture":2.8,"focal":135}]}]}
JSON
adb_ shell mkdir $CARD/DCIM/LENSES >/dev/null 2>&1
adb_ push "$OUT/lenses.json" $CARD/DCIM/LENSES/lenses.json >/dev/null 2>&1
start
key down down
shot 23-catalogo-tarjeta
key up up up up up enter
shot 24-diagnostico-tarjeta
check "the card's catalogue is the one in use" logged "catalogue: card"
adb_ shell rm $CARD/DCIM/LENSES/lenses.json

# ------------------------------------------------------------------ the wheel, a page, auto-exit
say "10. Control wheel and page jump in a long list; manual focal by holding the key"
start
key down down down down down enter         # Canon (104 models)
key wheel+ wheel+ wheel+
shot 25-rueda
key right
shot 26-pagina
key menu up up up up up up up up up enter  # back on Canon; up to manual data
key hold:right
shot 27-manual-mantener
key down down enter enter
focal="$(adb_ logcat -d -s LensCatalog:I | tr -d '\r' | sed -n 's/.*lensName="Manual \([0-9]*\)mm.*/\1/p' | tail -1)"
say "focal after holding right for a second: ${focal:-?} mm (from 50)"
check "holding the key repeats, and speeds up" test "${focal:-0}" -gt 58
key menu
say "11. Auto-exit back on: Apply leaves the app by itself"
key up enter down down down enter enter     # auto-exit ON, then Last used -> Apply
sleep 4
check "auto-exit: back to the camera after Apply" focused launcher
check "no crash" no_crash

say "screens in $OUT/shots, photographs in $OUT/photos"
if [ "$fails" -eq 0 ]; then say "ALL CHECKS PASSED"; else say "$fails CHECK(S) FAILED"; fi
exit $((fails > 0))
