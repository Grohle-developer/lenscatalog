#!/usr/bin/env python3
"""shot.py replacement without xdotool: parks the emulator window via Xlib,
then grabs the display with ffmpeg and crops the 640x480 screen.

    shot_xlib.py OUT.png        (DISPLAY names the virtual display)
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image
from Xlib import display as xdisplay

WIN_X, WIN_Y = 110, 70  # where the device window is parked
W, H = 640, 480


def find_window(root, name):
    try:
        wm_name = root.get_wm_name()
    except Exception:
        wm_name = None
    if wm_name and name in wm_name:
        return root
    try:
        children = root.query_tree().children
    except Exception:
        return None
    for ch in children:
        found = find_window(ch, name)
        if found is not None:
            return found
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    disp_name = os.environ.get("DISPLAY", ":99")
    d = xdisplay.Display(disp_name)
    root = d.screen().root
    win = find_window(root, "Android Emulator")
    if win is None:
        sys.exit("shot_xlib.py: no emulator window on %s" % disp_name)
    win.configure(x=WIN_X, y=WIN_Y)
    d.sync()
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "screen.ppm")
        r = subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "x11grab",
                            "-i", disp_name, "-frames:v", "1", raw],
                           capture_output=True)
        if r.returncode != 0:
            sys.exit("shot_xlib.py: x11grab failed: %s" % r.stderr.decode()[:300])
        Image.open(raw).crop((WIN_X, WIN_Y, WIN_X + W, WIN_Y + H)).save(out)
    print(out)


if __name__ == "__main__":
    main()
