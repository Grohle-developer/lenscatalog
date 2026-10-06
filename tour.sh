#!/usr/bin/env bash
# LensCatalog screenshot tour on the A7 II simulator.
set -euo pipefail
SIM=/home/hatch/workspace/aintfilm-sony-claude/aintfilm-aintfilm-sony/sim
ADB=~/.cache/aintfilm-sony-sim/sdk/platform-tools/adb
APK=/home/hatch/workspace/a7ii-fw/lenscatalog/app/out/lenscatalog.apk
PKG=com.lenscatalog

cd "$SIM"
./sim.sh install "$APK" >/dev/null 2>&1 || ./sim.sh install "$APK" | tail -1
$ADB -e shell pm clear "$PKG" >/dev/null 2>&1 || true
$ADB -e shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 5

shot() { ./sim.sh shot "$1" >/dev/null; sleep 1; }
key() { ./sim.sh key "$@" >/dev/null; sleep 1; }

shot 01-home
key down; key enter            # Helios brand
shot 03-models
key enter                      # Helios 44-2 -> confirm
shot 04-confirm
key down; key enter            # toggle favourite
sleep 1; shot 05-confirm-fav
key enter                      # Aplicar -> toast
sleep 1; shot 06-toast
sleep 3                        # auto-exit
$ADB -e shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4
shot 07-home-lastused          # home now shows last used
key enter                      # Favourites
shot 08-favorites
key menu; sleep 1
key down; key enter            # last used -> confirm
shot 09-lastused-confirm
key menu; sleep 1              # back home
for i in $(seq 1 13); do key down; done   # to "Datos manuales"
key enter
shot 10-manual
key right; key right; key right
shot 11-manual-focal
key down; key right; key right
shot 12-manual-aperture
key down; key enter            # Aplicar -> confirm
shot 13-manual-confirm
key enter                      # apply -> toast
sleep 1; shot 14-manual-toast
sleep 3
$ADB -e shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4
for i in $(seq 1 12); do key down; done   # to Tokina
key enter
shot 15-tokina-models
key enter                      # Tokina 28-70 -> focal picker
shot 16-zoom-focal
key right; key right; key right; key right; key right
shot 17-zoom-focal-adj
key enter                      # -> confirm
shot 18-zoom-confirm
key enter                      # apply -> toast
sleep 1; shot 19-zoom-toast
echo TOUR-DONE
ls "$SIM/out/shots/"*.png | sort
