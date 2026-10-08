#!/usr/bin/env python3
"""Runs ExifWriter (through ExifCli) on each fixture and checks with exiftool that
the lens tags land in the Exif IFD, the camera's EXIF and thumbnail survive, the
image data is byte-identical, the file keeps its time, and exiftool -validate
finds nothing new.

Raw files (.arw) are written in place, with an XMP sidecar: there, the file
must be the camera's byte for byte but for the 4 bytes of IFD0's Exif pointer,
and the sidecar must say the lens. ARW_SAMPLE=<a real .ARW> adds that file to
the cases (and, with rawpy installed, checks it decodes to the same image).

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


def write(p, lens, focal, f, make="", fmin=None, fmax=None, shim=None):
    """shim: a SHIM_RENAME mode (rename_shim.c) for a card that refuses renames."""
    args = ["java", "-cp", CP, "com.lenscatalog.ExifCli", p, lens, str(focal), str(f), make]
    if fmin is not None:
        args += [str(fmin), str(fmax)]
    env = dict(os.environ)
    if shim:
        env["LD_PRELOAD"] = os.environ["RENAME_SHIM"]
        env["SHIM_RENAME"] = shim
    r = subprocess.run(args, capture_output=True, text=True, env=env)
    return r.stdout.strip()


def tempname(p):
    r = subprocess.run(["java", "-cp", CP, "com.lenscatalog.ExifCli", "--tempname", p], capture_output=True, text=True)
    return r.stdout.strip()


def check(cond, msg):
    global fails
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        fails += 1


# (fixture, writes): a write is (model, focal, f) or (model, focal, f, make, focalMin, focalMax)
CASES = [
    ("sony.jpg", [("Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58)]),
    ("sony.jpg", [("Canon EF 100-200mm f/4.5A", 0, 4.5, "Canon", 100, 200)]),  # a zoom: FocalLength left alone
    ("sony.jpg", [("Mir-1 37mm f/2.8", 37, 2.8), ("Mir-1B 37mm f/2.8", 37, 2.8)]),  # tagged twice
    ("sony.jpg", [("Voigtländer Color-Ultron 50mm f/1.8", 50, 1.8, "Voigtländer", 50, 50)]),  # UTF-8 name
    ("bigendian.jpg", [("Jupiter-9 85mm f/2", 85, 2.0, "Jupiter", 85, 85)]),
    ("noexifptr.jpg", [("Industar-61 L/Z 50mm f/2.8", 50, 2.8)]),
    ("noexif.jpg", [("Manual 50mm f/1.8", 50, 1.8)]),                 # a lens entered by hand: no maker
    ("sony.arw", [("Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58)]),
    ("sony.arw", [("Canon FD 35-70mm f/4", 0, 4.0, "Canon", 35, 70), ("Canon FD 35-70mm f/4 (2)", 0, 4.0, "Canon", 35, 70)]),  # zoom, twice
]
if os.environ.get("ARW_SAMPLE"):
    shutil.copy(os.environ["ARW_SAMPLE"], os.path.join(FX, "sample.arw"))
    CASES.append(("sample.arw", [("Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58)]))

work = os.path.join(FX, "work")
os.makedirs(work, exist_ok=True)
for n, (name, writes) in enumerate(CASES):
    p = os.path.join(work, "%d-%s" % (n, name))
    shutil.copy(os.path.join(FX, name), p)
    os.utime(p, (1759831200, 1759831200))
    raw = name.endswith(".arw")
    orig = open(p, "rb").read()
    before, warn0 = tags(p), warnings(p)
    data0 = None if raw else image_data(p)
    for w in writes:
        lens, focal, f = w[0], w[1], w[2]
        make = w[3] if len(w) > 3 else ""
        fmin, fmax = (w[4], w[5]) if len(w) > 5 else (None, None)
        out = write(p, lens, focal, f, make, fmin, fmax)
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
    if make:
        check(after.get("ExifIFD:LensMake") == make, "ExifIFD:LensMake = %r" % make)
        lo, hi = (fmin, fmax) if fmin is not None else (focal, focal)
        info = after.get("ExifIFD:LensInfo")
        check(info == "%d %d %s %s" % (lo, hi, ("%g" % f), ("%g" % f)) or info == "%d %d %g %g" % (lo, hi, f, f),
              "ExifIFD:LensInfo = %r (range %d-%d, f/%g)" % (info, lo, hi, f))
    else:
        check("ExifIFD:LensMake" not in after or after.get("ExifIFD:LensMake") == before.get("ExifIFD:LensMake"),
              "no LensMake written for a lens without a maker")
    # (exiftool converts the APEX value back to an f-number, even with -n; the APEX is kept to 1/100)
    check(abs(after.get("ExifIFD:MaxApertureValue", 0) - f) < 0.02,
          "ExifIFD:MaxApertureValue = f/%g (got f/%s)" % (f, after.get("ExifIFD:MaxApertureValue")))
    check(after.get("XMP-microsoft:LensModel") == lens and after.get("XMP-aux:Lens") == lens
          and after.get("XMP-exifEX:LensModel") == lens
          and (not make or after.get("XMP-microsoft:LensManufacturer") == make),
          "XMP in the file: MicrosoftPhoto/aux/exifEX say the lens (Windows Explorer reads only these)")
    check(after.get("XMP-x:XMPToolkit") == "LensCatalog", "one XMP packet, ours")
    mine = ("ExifIFD:LensModel", "ExifIFD:LensMake", "ExifIFD:LensInfo", "ExifIFD:FocalLength", "ExifIFD:FNumber",
            "ExifIFD:MaxApertureValue")
    lost = [k for k, v in before.items() if k.split(":")[0] in ("IFD0", "ExifIFD", "IFD1")
            and k not in mine and not k.endswith("ThumbnailOffset") and after.get(k) != v]
    check(not lost, "the camera's EXIF kept (%d tags)%s" % (
        sum(1 for k in before if k.split(":")[0] in ("IFD0", "ExifIFD", "IFD1")), " lost: %s" % lost if lost else ""))
    if "IFD1:ThumbnailLength" in before:
        r = subprocess.run(["exiftool", "-b", "-ThumbnailImage", p], capture_output=True)
        check(r.stdout[:2] == b"\xff\xd8" and len(r.stdout) == before["IFD1:ThumbnailLength"], "thumbnail intact")
    if raw:
        now = open(p, "rb").read()
        moved = [i for i in range(len(orig)) if orig[i] != now[i]]
        check(len(now) >= len(orig) and 0 < len(moved) and moved[-1] - moved[0] < 4 and moved[0] >= 4 and moved[-1] <= 7,
              "raw: the camera's bytes untouched but the 4 of the header's IFD0 offset (changed: %s)" % moved[:8])
        x = tags(os.path.splitext(p)[0] + ".XMP")
        check(x.get("XMP-exifEX:LensModel") == lens and x.get("XMP-aux:Lens") == lens
              and abs(x.get("XMP-exif:FNumber", 0) - f) < 1e-6
              and (x.get("XMP-exif:FocalLength") == focal if focal > 0 else "XMP-exif:FocalLength" not in x),
              "sidecar %s says the lens" % os.path.basename(os.path.splitext(p)[0] + ".XMP"))
        try:
            import numpy, rawpy
            def decoded(path):
                with rawpy.imread(path) as r:
                    return hashlib.sha1(r.raw_image_visible.tobytes()).hexdigest()
            if name == "sample.arw":
                src = os.path.join(FX, "sample.arw")
                check(decoded(p) == decoded(src), "raw: decodes to the same sensor data (rawpy)")
        except ImportError:
            pass
    else:
        check(image_data(p) == data0, "image data byte-identical")
        left = [n for n in os.listdir(os.path.dirname(p)) if n.upper().endswith(".TMP")]
        check(not left, "no temporary file left next to the photo %s" % left)
    check(int(os.path.getmtime(p)) == 1759831200, "file time kept")
    new = warnings(p) - warn0
    check(not new, "exiftool -validate: nothing new %s" % sorted(new))
# a card that will not rename over a file (rename_shim.c): the writer's fallbacks, and the photograph survives every one
if os.environ.get("RENAME_SHIM"):
    def fresh(name):
        p = os.path.join(work, name)
        shutil.copy(os.path.join(FX, "sony.jpg"), p)
        return p, open(p, "rb").read()
    def leftovers(d):
        return sorted(n for n in os.listdir(d) if n.upper().endswith((".TMP", ".OLD")))
    # over: rename over the original refused -> the original steps aside as .OLD, the new file takes its name
    p, before = fresh("S1-OVER.JPG")
    out = write(p, "Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58, shim="over")
    print("card refuses rename-over: %s" % out)
    check(out == "OK", "tagged through the .OLD step")
    check(tags(p).get("ExifIFD:LensModel") == "Helios 44-2 58mm f/2", "the photo carries the lens")
    check(leftovers(work) == [], "no .TMP or .OLD left %s" % leftovers(work))
    # over+tmp: the new file can never take the name -> the original comes back, untouched
    p, before = fresh("S2-TMP.JPG")
    out = write(p, "Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58, shim="over,tmp")
    print("card refuses every rename of the new file: %s" % out)
    check(out.startswith("ERR"), "the writer reports the failure")
    check(open(p, "rb").read() == before, "the photograph is byte-identical to before")
    check(leftovers(work) == [], "no .TMP or .OLD left %s" % leftovers(work))
    # over+tmp+old: not even the way back works -> the photo is left as .OLD, and the next run brings it back and tags it
    p, before = fresh("S3-OLD.JPG")
    out = write(p, "Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58, shim="over,tmp,old")
    print("card refuses every rename: %s" % out)
    check(out.startswith("ERR") and "S3-OLD.OLD" in out, "the writer says the photo is left as .OLD")
    check(not os.path.exists(p) and open(os.path.join(work, "S3-OLD.OLD"), "rb").read() == before, "the photograph is intact as .OLD")
    out = write(p, "Helios 44-2 58mm f/2", 58, 2.0, "Helios", 58, 58)
    check(out == "OK" and os.path.exists(p) and leftovers(work) == [], "the next run brings it back and tags it (%s)" % out)
    check(tags(p).get("ExifIFD:LensModel") == "Helios 44-2 58mm f/2", "the recovered photo carries the lens")
else:
    print("  (no C compiler: the refusing-card scenarios were not run)")

# the temporary file a JPEG goes through has an 8.3 name, upper case: what the camera's card takes
for name, want in (("DSC02073.JPG", "DSC02073.TMP"), ("/x/100MSDCF/dsc02073.jpeg", "DSC02073.TMP"),
                   ("a.very.long.name.jpg", "AVERYLON.TMP")):
    got = tempname(name)
    check(got == want, "temp name for %s is %s (got %s)" % (name, want, got))

# a sidecar someone else wrote (edits from a computer) is left alone
p = os.path.join(work, "foreign.arw")
shutil.copy(os.path.join(FX, "sony.arw"), p)
foreign = os.path.splitext(p)[0] + ".XMP"
open(foreign, "w").write("<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='Adobe XMP Core'>my edits</x:xmpmeta>")
out = write(p, "Helios 44-2 58mm f/2", 58, 2.0)
print("foreign.arw with someone's sidecar: %s" % out)
check(open(foreign).read().endswith("my edits</x:xmpmeta>"), "someone else's sidecar kept")
check(tags(p).get("ExifIFD:LensModel") == "Helios 44-2 58mm f/2", "the raw file still tagged")
print("FAILURES: %d" % fails)
sys.exit(1 if fails else 0)
