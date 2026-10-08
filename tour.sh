#!/usr/bin/env bash
# LensCatalog: the whole workflow, end to end, on aintfilm-sony's A7 II simulator
# (Android 2.3.7 at 640x480, keys only), in English by default (TOUR_LANG=es TOUR_COUNTRY=ES
# for Spanish), with screenshots and checks.
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
TOUR_LANG="${TOUR_LANG:-en}"; TOUR_COUNTRY="${TOUR_COUNTRY:-US}"
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
# The shutter, as on the A7 II: there Android's clock starts at 1970 on every
# power-on (the camera's own log), while the photographs carry the camera's real
# date. So the guest's clock is set to 1970 below, and a "shot" is pushed from
# the computer with today's date (adb push keeps the file's time).
shoot() {
  local src="$OUT/fixtures/CAMERA.${1##*.}"
  touch "$src"
  adb_ push "$src" "$DCIM/$1" >/dev/null 2>&1
  say "shot $1"
}
pref() { adb_ shell cat /data/data/$PKG/shared_prefs/lenscatalog.xml | tr -d '\r'; }
has_pref() { pref | grep -qF -- "$1"; }
wait_log() { for _ in $(seq 1 "${2:-120}"); do logged "$1" && return 0; sleep 1; done; return 1; }

# ------------------------------------------------------------------ setup
"$SIM" boot
"$SIM" install "$APK"
if [ "$(adb_ shell getprop persist.sys.language | tr -d '\r')" != "$TOUR_LANG" ]; then
  say "the guest in $TOUR_LANG (the framework restarts)"
  adb_ shell setprop persist.sys.language "$TOUR_LANG"; adb_ shell setprop persist.sys.country "$TOUR_COUNTRY"
  adb_ shell stop; sleep 2; adb_ shell start; sleep 90
fi
# past the lock screen (the framework restart above locks it again); only while it
# is up: on an unlocked screen that key opens a menu, and leaving the app lands there
for _ in $(seq 1 10); do focused Keyguard || break; adb_ shell input keyevent 82 >/dev/null 2>&1; sleep 2; done
adb_ shell pm clear $PKG >/dev/null
# The emulator puts its clock back to the computer's now and then: it is set to
# 1970 again right before each Apply and Write EXIF, the moments that matter.
clock1970() { adb_ shell date -s 19700101.000500 >/dev/null 2>&1; }
clock1970
rm -rf "$OUT/shots" "$OUT/photos" && mkdir -p "$OUT/shots" "$OUT/photos" "$OUT/fixtures"
python3 "$ROOT/app/test/exif_fixtures.py" "$OUT/fixtures"
# (and nothing of aintfilm's: the app's folder is /LENSCAT, and an /AINTFILM left by an older build goes)
adb_ shell "rm -r $DCIM; rm -r $CARD/LCSIM; rm -r $CARD/AINTFILM; rm $CARD/LENSCAT/SIM/LENS.TXT; rm $CARD/DCIM/LENSES/lenses.json; rm $CARD/DCIM/LENSES/LENSES.JSN; rm $CARD/LENSCAT/LENSCAT.LOG; rm $CARD/LENSCAT/MYLENSES.JSN; rm $CARD/LENSCAT/MYLENSES.BAD" >/dev/null 2>&1
adb_ shell "mkdir $CARD/DCIM; mkdir $DCIM; mkdir $CARD/LCSIM; mkdir $CARD/LENSCAT; mkdir $CARD/LENSCAT/SIM" >/dev/null 2>&1
cp "$OUT/fixtures/sony.jpg" "$OUT/fixtures/CAMERA.JPG"
# the raw file of a RAW+JPEG shot: LC_ARW=<a real .ARW>, else the fixture's outline of one
ARW="${LC_ARW:-$OUT/fixtures/sony.arw}"
say "raw file for the shots: $ARW"
cp "$ARW" "$OUT/fixtures/CAMERA.ARW"

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
key menu; clock1970; key enter              # back to the page (on Apply), apply
shot 08-aplicado
check "SteadyShot manual, 58 mm lens -> 60 mm (the nearest the A7 II offers)" logged "SIM setAntiHandBlurInfo(manual) setAntiHandBlurFocalLength(60) // lens 58 mm"
check "EXIF lensName + focal 58" logged 'lensName="Helios 44 58mm 1:2", focal=58/1'
check "correction ON with shading-w=3, chroma-r=-2" logged 'shading-w=3 shading-wm=0 shading-cr=0 shading-cb=0 shading-cm=0 chroma-r=-2'
sleep 3; shoot DSC00001.JPG; shoot DSC00001.ARW; sleep 1; shoot DSC00002.JPG

# ------------------------------------------------------------------ a zoom
say "3. Last used on home; Canon EF 100-200mm f/4.5A (a zoom)"
key menu
shot 09-inicio-ultimo
key down down down down down enter enter
shot 10-ficha-zoom
clock1970; key enter
shot 11-aplicado-zoom
check "zoom: SteadyShot at the wide end (100)" logged "SIM setAntiHandBlurInfo(manual) setAntiHandBlurFocalLength(100) // lens 100 mm"
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
clock1970; key enter
check "manual lens applied" logged 'lensName="Manual 35mm f/2.8", focal=35/1'
sleep 3; shoot DSC00004.JPG

# ------------------------------------------------------------------ Write EXIF
say "5. Write EXIF to photos"
sleep 2
key menu; clock1970; key enter
wait_log "tagger: done" 120 || true
sleep 1
shot 15-etiquetado
check "tagger: 5 tagged (4 JPEG + 1 ARW), 0 failed, though the app's clock says 1970" logged "tagger: done tagged=5 already=0 failed=0 skippedEl=0 noSession=0"
card_log() { adb_ shell cat $CARD/LENSCAT/LENSCAT.LOG | tr -d '\r'; }
# (the applies and the tagging of steps 2-5: the log up to the first "tagger: done")
# (stamped "boot+HH:MM:SS": the app knows Android's clock is unset, and the simulator has no camera clock)
applied_in_1970() {
  local l; l="$(card_log | awk '{print} /tagger: done/ {exit}' | grep -E 'setAntiHandBlurFocalLength|tagger: start')"
  [ -n "$l" ] && ! echo "$l" | grep -qvE '^(1970-|boot\+)'
}
check "the app's clock said 1970 at every Apply and at Write EXIF (as on the camera), logged as boot+" applied_in_1970
check "the ARW tagged in place and its sidecar written" logged "tagger: raw DSC00001.ARW exif=ok sidecar=DSC00001.XMP"
for n in 1 2 3 4; do adb_ pull $DCIM/DSC0000$n.JPG "$OUT/photos/DSC0000$n.JPG" >/dev/null 2>&1; done
adb_ pull $DCIM/DSC00001.ARW "$OUT/photos/DSC00001.ARW" >/dev/null 2>&1
adb_ pull $DCIM/DSC00001.XMP "$OUT/photos/DSC00001.XMP" >/dev/null 2>&1
if command -v exiftool >/dev/null; then
  python3 - "$OUT/photos" "$ARW" <<'PY' || fails=$((fails + 1))
import json, subprocess, sys
d, orig = sys.argv[1], sys.argv[2]
def tags(p):
    return json.loads(subprocess.run(["exiftool", "-j", "-G1", "-n", p], capture_output=True, text=True).stdout)[0]
a, b, x = tags(orig), tags(d + "/DSC00001.ARW"), tags(d + "/DSC00001.XMP")
o, n = open(orig, "rb").read(), open(d + "/DSC00001.ARW", "rb").read()
moved = [i for i in range(len(o)) if o[i] != n[i]]
ok = (b.get("ExifIFD:LensModel") == "Helios 44 58mm 1:2" and b.get("ExifIFD:LensMake") == "Helios"
      and b.get("ExifIFD:FocalLength") == 58 and b.get("ExifIFD:FNumber") == 2 and b.get("ExifIFD:LensInfo") == "58 58 2 2"
      and moved and moved[0] >= 4 and moved[-1] <= 7  # only the header's IFD0 offset
      and all(b.get(k) == a.get(k) for k in ("ExifIFD:ISO", "ExifIFD:ExposureTime", "ExifIFD:DateTimeOriginal"))
      and x.get("XMP-exifEX:LensModel") == "Helios 44 58mm 1:2" and x.get("XMP-exif:FocalLength") == 58
      and b.get("XMP-microsoft:LensModel") == "Helios 44 58mm 1:2" and b.get("XMP-microsoft:LensManufacturer") == "Helios")
print("  %s   DSC00001.ARW: LensMake=%r LensModel=%r FocalLength=%s FNumber=%s LensInfo=%r, %d camera bytes changed "
      "(the header's IFD0 offset); DSC00001.XMP: %r" % ("ok" if ok else "FAIL", b.get("ExifIFD:LensMake"), b.get("ExifIFD:LensModel"),
      b.get("ExifIFD:FocalLength"), b.get("ExifIFD:FNumber"), b.get("ExifIFD:LensInfo"), len(moved), x.get("XMP-exifEX:LensModel")))
sys.exit(0 if ok else 1)
PY
fi
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
    make = lens.split(" ")[0] if not lens.startswith("Manual") else None
    ok = (got[0] == lens and got[1] == focal and abs((got[2] or 0) - f) < 1e-6 and kept and t.get("ExifIFD:LensMake") == make
          and t.get("XMP-microsoft:LensModel") == lens)
    bad += not ok
    print("  %s   %s: LensMake=%r LensModel=%r FocalLength=%s FNumber=%s, camera EXIF kept=%s"
          % ("ok" if ok else "FAIL", name, t.get("ExifIFD:LensMake"), got[0], got[1], got[2], kept))
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
key menu up up up up up up up up enter down down down       # from View log, up through the tools to the last brands
shot 19-modelos-mismo-nombre
key enter
shot 20-ficha-montura-ef
key enter
check "EF version applied (last used = its id)" has_pref 'aspheric-2</string>'
check "no crash so far" no_crash

# ------------------------------------------------------------------ an electronic lens
say "8. An electronic lens (sim: /LENSCAT/SIM/LENS.TXT): nothing to do, and its photos are skipped"
adb_ shell "echo 'FE 28-70mm F3.5-5.6 OSS' > $CARD/LENSCAT/SIM/LENS.TXT"
start
shot 21-objetivo-electronico
check "electronic lens read" logged "SIM electronic lens from LENS.TXT: FE 28-70mm F3.5-5.6 OSS"
key menu
sleep 3; shoot DSC00005.JPG
adb_ shell rm $CARD/LENSCAT/SIM/LENS.TXT
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
# as LENSES.JSN: the camera's card holds 8.3 names only (a computer's lenses.json shows there as LENSES~1.JSO)
adb_ push "$OUT/lenses.json" $CARD/DCIM/LENSES/LENSES.JSN >/dev/null 2>&1
start
key down down
shot 23-catalogo-tarjeta
key up up up up up enter
shot 24-diagnostico-tarjeta
check "the card's catalogue is the one in use" logged "catalogue: card"
adb_ shell rm $CARD/DCIM/LENSES/LENSES.JSN

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
for _ in $(seq 1 30); do focused launcher && break; sleep 1; done   # the emulator has no acceleration: poll, do not guess
check "auto-exit: back to the camera after Apply" focused launcher
check "no crash" no_crash

# ------------------------------------------------------------------ a lens of your own
# Typed on the camera: the on-screen keyboard is walked with the four-way, the
# way a person does it. The walk is worked out here from the keyboard's layout
# (Keyboard.java: the same rows, the same cursor rules), one line of keys a
# character; what comes out is checked at the end, in the catalogue and the EXIF.
say "12. A lens of your own, typed on the camera's keyboard"
keys_for() { # text -> lines of keys (one per character), for `key`
  python3 - "$1" <<'PY'
import sys
LAYOUT = [["1","2","3","4","5","6","7","8","9","0","back"],
          ["q","w","e","r","t","y","u","i","o","p","-"],
          ["a","s","d","f","g","h","j","k","l",".","/"],
          ["shift","z","x","c","v","b","n","m",":","(",")"],
          ["clear:2","space:5","'","+","done:2"]]
rows = []
for r in LAYOUT:
    u, keys = 0, []
    for spec in r:
        name, span = (spec.split(":") + ["1"])[:2] if ":" in spec and spec != ":" else (spec, "1")
        span = int(span)
        keys.append((name, u, span)); u += span
    rows.append(keys)
row, col, anchor, shift = 1, 0, 0, 1   # Keyboard(): starts on q, shift once for an empty field
def key_at(r, unit):
    for c, (n, st, sp) in enumerate(rows[r]):
        if st <= unit < st + sp: return c
    return len(rows[r]) - 1
def go(name):
    global row, col, anchor
    out = []
    tr, tc = next((r, c) for r in range(len(rows)) for c, k in enumerate(rows[r]) if k[0] == name)
    down = (tr - row) % len(rows); up = len(rows) - down
    for _ in range(min(down, up)):
        row = (row + (1 if down <= up else -1)) % len(rows); col = key_at(row, anchor)
        out.append("down" if down <= up else "up")
    n = len(rows[row]); right = (tc - col) % n; left = n - right
    for _ in range(min(right, left)):
        col = (col + (1 if right <= left else -1)) % n; anchor = rows[row][col][1]
        out.append("right" if right <= left else "left")
    return out
for ch in sys.argv[1]:
    seq = []
    if ch.isalpha():
        want_upper = ch.isupper()
        while (shift != 0) != want_upper:        # shift cycles off, once, lock
            seq += go("shift") + ["enter"]; shift = (shift + 1) % 3
        seq += go(ch.lower()) + ["enter"]
        if shift == 1: shift = 0
    elif ch == " ":
        seq += go("space") + ["enter"]
    else:
        seq += go(ch) + ["enter"]
    print(" ".join(seq))
PY
}
type_text() { # the keys of a text, a character at a time (several keys a call: the emulator takes them in order)
  # (read on its own descriptor: adb shell, inside `key`, would swallow the lines left on stdin)
  while read -r -u 3 line; do KEYWAIT=0.7 key $line; done 3< <(keys_for "$1")
}
MYBRAND="Meyer-Optik"; MYMODEL="Oreston 50mm f/1.8"; MYMOUNT="M42"
MYID="my-meyer-optik-oreston-50mm-f-1-8-m42"
MYFILE=$CARD/LENSCAT/MYLENSES.JSN
APPFILE=/data/data/$PKG/files/mylenses.json
start
key up enter                                   # auto-exit off again (step 11 turned it on)
key up up up up enter                          # View log, Diagnostics, Manual data, Add a lens
shot 29-anadir-objetivo
key enter                                      # the brand: the keyboard
type_text "$MYBRAND"
shot 30-teclado
key menu                                       # Done: back on the form
key down enter; type_text "$MYMODEL"; key menu # the model
key down enter; type_text "$MYMOUNT"; key menu # the mount
key down down                                  # type stays Prime; focal stays 50 mm
key down; key right right right right right right right right   # f/1.8
shot 31-formulario
key down enter                                 # Save
sleep 2
shot 32-objetivo-guardado
check "the lens is saved in the app and on the card" logged "my lenses: saved $MYID \"$MYBRAND $MYMODEL\""
check "the card's copy is the catalogue's format and holds the lens" python3 - "$(adb_ shell cat $MYFILE | tr -d '\r')" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
b = [x for x in d["brands"] if x["brand"] == "Meyer-Optik"]
m = b[0]["models"][0]
assert m["model"] == "Oreston 50mm f/1.8" and m["mount"] == "M42" and m["type"] == "prime"
assert m["focal"] == 50 and abs(m["max_aperture"] - 1.8) < 1e-9 and m["id"] == "my-meyer-optik-oreston-50mm-f-1-8-m42"
PY
check "the app's own copy exists" adb_ shell "ls $APPFILE" | grep -q mylenses.json
key menu                                       # the result closes onto the brand's list
shot 33-marca-propia
key enter                                      # the lens's page: with its MINE chip, Edit and Delete
shot 34-ficha-propia
clock1970; key enter                           # Apply
check "the lens of your own applied: its name in the pre-capture EXIF" logged "setExifInfo(lensName=\"$MYBRAND $MYMODEL\", focal=50/1"
sleep 2; shoot DSC00006.JPG
key menu; clock1970; key enter                 # home -> Write EXIF
wait_log "tagger: done" 120 || true
adb_ pull $DCIM/DSC00006.JPG "$OUT/photos/DSC00006.JPG" >/dev/null 2>&1
if command -v exiftool >/dev/null; then
  check "DSC00006.JPG: LensMake and LensModel are the lens of your own" bash -c \
    "exiftool -s -S -LensMake -LensModel -FocalLength -FNumber -n '$OUT/photos/DSC00006.JPG' | tr '\n' '|' | grep -qF 'Meyer-Optik|Meyer-Optik Oreston 50mm f/1.8|50|1.8|'"
fi
# a new card: the card's copy is gone; the app's carries the lens, and the card's is written again
adb_ shell rm $MYFILE
start
check "a new card: the lens is still there, from the app's copy" logged "my lenses: 1"
check "and the card gets its copy back" adb_ shell "ls $MYFILE" | grep -q MYLENSES.JSN
# a reinstall, or a second body: the app's copy is gone; the card's brings the lens back
adb_ shell pm clear $PKG >/dev/null
start
check "a reinstall: the lens comes back from the card" logged "my lenses: 1 (1 imported from the card)"
check "and the app's copy is written again" adb_ shell "ls $APPFILE" | grep -q mylenses.json
# delete it: OK twice on its page
MYIDX=$(python3 - "$ROOT/app/assets/lenses.json" "$MYBRAND" <<'PY'
import json, sys
brands = [b["brand"] for b in json.load(open(sys.argv[1]))["brands"]]
i = len(brands)
for k, b in enumerate(brands):
    if b.lower() > sys.argv[2].lower(): i = k; break
print(i)
PY
)
downs=""; for _ in $(seq 1 $((MYIDX + 2))); do downs="$downs down"; done   # Favourites, then the brands
key $downs
shot 35-marca-en-catalogo
key enter enter                                # its page
key down down down down down                   # Apply, correction, adjust, favourites, Edit, Delete
key enter                                      # armed: "Delete? Press OK again"
shot 36-borrar
key enter                                      # deleted
check "deleted from the app and the card" logged "my lenses: removed $MYID"
check "the card's copy is empty now" python3 - "$(adb_ shell cat $MYFILE | tr -d '\r')" <<'PY'
import json, sys
assert json.loads(sys.argv[1])["brands"] == []
PY
key menu
check "no crash" no_crash

# Every file the app left on the card has an 8.3 upper-case name: the camera's card
# takes no other kind (0.3.2's DSC02073.JPG.exiftmp failed there). The simulator's
# card is vfat, so a long name would have been created, and shows up here.
say "13. The names the app left on the card"
card_names() { adb_ shell "ls -R $CARD/DCIM $CARD/LENSCAT" | tr -d '\r' | grep -vE '^(/|$)'; }
card_names | sed 's/^/  card: /' | head -40
not83() { card_names | grep -vE '^[A-Z0-9_~-]{1,8}(\.[A-Z0-9_~-]{1,3})?$'; }
has_photos() { card_names | grep -q "DSC00001.JPG"; }
check "the card listing has the photographs (the audit is not vacuous)" has_photos
check "every name on the card is 8.3, upper case" test -z "$(not83)"
not83 | sed 's/^/  NOT 8.3: /'
check "the app writes nothing in aintfilm's folder (/AINTFILM)" test "$(adb_ shell "ls $CARD/AINTFILM >/dev/null 2>&1 && echo yes || echo no" | tr -d '\r')" = "no"
log_has_start() { card_log | grep -q "LensCatalog start"; }
check "the log is the app's own: /LENSCAT/LENSCAT.LOG" log_has_start

say "screens in $OUT/shots, photographs in $OUT/photos"
if [ "$fails" -eq 0 ]; then say "ALL CHECKS PASSED"; else say "$fails CHECK(S) FAILED"; fi
exit $((fails > 0))
