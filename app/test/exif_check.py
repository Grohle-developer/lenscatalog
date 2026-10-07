#!/usr/bin/env python3
"""Runs ExifWriter (through ExifCli) on each fixture and checks with exiftool that
the lens tags land in the Exif IFD, the camera's EXIF and thumbnail survive, the
image data is byte-identical, the file keeps its time, and exiftool -validate
finds nothing new.

    exif_check.py <classes-dir> <fixtures-dir>
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys

CP, FX = sys.argv[1], sys.argv[2]
fails = 0


def tags(p):
    r = subprocess.run(["exiftool", "-j", "-G1", "-a", "-n", p], capture_output=True, text=True)
    return json.loads(r.stdout)[0]


def warnings(p):
    """exiftool's structural warnings. Left out: [minor] ones, and "Missing required"
    ones, which only say an IFD the writer had to create (the fixtures without
    EXIF) lacks the camera's own tags; a camera JPEG always has them."""
    r = subprocess.run(["exiftool", "-validate", "-warning", "-a", "-s", p], capture_output=True, text=True)
    return {l for l in r.stdout.splitlines()
            if l.startswith("Warning") and "minor" not in l and "Missing required" not in l}


def image_data(p):
    """The main image from its SOS on, found by walking the segments (the EXIF
    thumbnail inside APP1 is a JPEG too, with an SOS of its own)."""
    b = open(p, "rb").read()
    i = 2
    while b[i] == 0xFF and b[i + 1] != 0xDA:
        i += 2 + (b[i + 2] << 8 | b[i + 3])
    return hashlib.sha1(b[i:]).hexdigest()


def write(p, lens, focal, f):
    r = subprocess.run(["java", "-cp", CP, "com.lenscatalog.ExifCli", p, lens, str(focal), str(f)],
                       capture_output=True, text=True)
    return r.stdout.strip()


def check(cond, msg):
    global fails
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        fails += 1


CASES = [
    ("sony.jpg", [("Helios 44-2 58mm f/2", 58, 2.0)]),
    ("sony.jpg", [("Canon EF 100-200mm f/4.5A", 0, 4.5)]),            # a zoom: FocalLength left alone
    ("sony.jpg", [("Mir-1 37mm f/2.8", 37, 2.8), ("Mir-1B 37mm f/2.8", 37, 2.8)]),  # tagged twice
    ("sony.jpg", [("Voigtländer Color-Ultron 50mm f/1.8", 50, 1.8)]),  # UTF-8 name
    ("bigendian.jpg", [("Jupiter-9 85mm f/2", 85, 2.0)]),
    ("noexifptr.jpg", [("Industar-61 L/Z 50mm f/2.8", 50, 2.8)]),
    ("noexif.jpg", [("Manual 50mm f/1.8", 50, 1.8)]),
]

work = os.path.join(FX, "work")
os.makedirs(work, exist_ok=True)
for n, (name, writes) in enumerate(CASES):
    p = os.path.join(work, "%d-%s" % (n, name))
    shutil.copy(os.path.join(FX, name), p)
    os.utime(p, (1759831200, 1759831200))
    before, data0, warn0 = tags(p), image_data(p), warnings(p)
    for lens, focal, f in writes:
        out = write(p, lens, focal, f)
    print("%s <- %r: %s" % (os.path.basename(p), lens, out))
    after = tags(p)
    check(out == "OK", "writer returned OK")
    check(after.get("ExifIFD:LensModel") == lens, "ExifIFD:LensModel = %r (got %r)" % (lens, after.get("ExifIFD:LensModel")))
    check(not any(k.startswith("IFD0:") and k[5:] in ("LensModel", "FocalLength", "FNumber") for k in after),
          "no lens tag in IFD0")
    if focal > 0:
        check(after.get("ExifIFD:FocalLength") == focal, "ExifIFD:FocalLength = %s" % focal)
    else:
        check(after.get("ExifIFD:FocalLength") == before.get("ExifIFD:FocalLength"),
              "zoom: the camera's FocalLength kept (%s)" % before.get("ExifIFD:FocalLength"))
    check(abs(after.get("ExifIFD:FNumber", 0) - f) < 1e-6, "ExifIFD:FNumber = %s" % f)
    mine = ("ExifIFD:LensModel", "ExifIFD:FocalLength", "ExifIFD:FNumber")
    lost = [k for k, v in before.items() if k.split(":")[0] in ("IFD0", "ExifIFD", "IFD1")
            and k not in mine and not k.endswith("ThumbnailOffset") and after.get(k) != v]
    check(not lost, "the camera's EXIF kept (%d tags)%s" % (
        sum(1 for k in before if k.split(":")[0] in ("IFD0", "ExifIFD", "IFD1")), " lost: %s" % lost if lost else ""))
    if "IFD1:ThumbnailLength" in before:
        r = subprocess.run(["exiftool", "-b", "-ThumbnailImage", p], capture_output=True)
        check(r.stdout[:2] == b"\xff\xd8" and len(r.stdout) == before["IFD1:ThumbnailLength"], "thumbnail intact")
    check(image_data(p) == data0, "image data byte-identical")
    check(int(os.path.getmtime(p)) == 1759831200, "file time kept")
    new = warnings(p) - warn0
    check(not new, "exiftool -validate: nothing new %s" % sorted(new))
print("FAILURES: %d" % fails)
sys.exit(1 if fails else 0)
