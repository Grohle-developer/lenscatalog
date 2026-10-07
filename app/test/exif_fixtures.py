#!/usr/bin/env python3
"""JPEGs for the EXIF writer's test and the simulator tour, made like the camera's:

  sony.jpg        an A7 II-like JPEG: little-endian TIFF, IFD0 -> Exif IFD (exposure,
                  ISO, dates, a camera FocalLength and LensModel) and IFD1 with a
                  160x107 thumbnail
  bigendian.jpg   the same layout in a big-endian (MM) TIFF
  noexifptr.jpg   IFD0 and IFD1, but no Exif IFD
  noexif.jpg      no EXIF at all
  sony.arw        a raw file as the A7 II writes it, in outline: a TIFF whose IFD0 points
                  to the Exif IFD (LensModel "----", FocalLength 0, FNumber 0), then
                  "sensor data" (the bytes a raw converter reads, which must not move)

    exif_fixtures.py <out-dir>
"""
import os
import struct
import sys
from io import BytesIO

from PIL import Image, ImageDraw

ASCII, SHORT, LONG, RATIONAL = 2, 3, 4, 5


def ifd(entries, start, nxt, fmt):
    """One IFD at `start`, its long values right after it (word-aligned)."""
    size = 2 + 12 * len(entries) + 4
    body = struct.pack(fmt + "H", len(entries))
    data = b""
    for tag, typ, count, val in sorted(entries):
        if len(val) <= 4:
            body += struct.pack(fmt + "HHI", tag, typ, count) + val.ljust(4, b"\0")
        else:
            body += struct.pack(fmt + "HHII", tag, typ, count, start + size + len(data))
            data += val + (b"\0" if len(val) % 2 else b"")
    return body + struct.pack(fmt + "I", nxt) + data


def asc(tag, s):
    b = s.encode() + b"\0"
    return (tag, ASCII, len(b), b)


def tiff(fmt, thumb, with_exif=True):
    def rat(n, d):
        return struct.pack(fmt + "II", n, d)

    exif = [(0x829A, RATIONAL, 1, rat(1, 250)), (0x829D, RATIONAL, 1, rat(0, 10)),
            (0x8827, SHORT, 1, struct.pack(fmt + "H", 400)), (0x9000, 7, 4, b"0230"),
            (0x9003, ASCII, 20, b"2026:10:07 10:00:00\0"), (0x9004, ASCII, 20, b"2026:10:07 10:00:00\0"),
            (0x920A, RATIONAL, 1, rat(0, 10)), (0xA001, SHORT, 1, struct.pack(fmt + "H", 1)),
            (0xA002, LONG, 1, struct.pack(fmt + "I", 6000)), (0xA003, LONG, 1, struct.pack(fmt + "I", 4000)),
            asc(0xA434, "----")]

    def ifd0(exif_at):
        e = [asc(0x010F, "SONY"), asc(0x0110, "ILCE-7M2"), (0x0112, SHORT, 1, struct.pack(fmt + "H", 1)),
             asc(0x0131, "ILCE-7M2 v4.00"), asc(0x0132, "2026:10:07 10:00:00")]
        if with_exif:
            e.append((0x8769, LONG, 1, struct.pack(fmt + "I", exif_at)))
        return e

    def ifd1(at):
        return [(0x0103, SHORT, 1, struct.pack(fmt + "H", 6)), (0x0201, LONG, 1, struct.pack(fmt + "I", at)),
                (0x0202, LONG, 1, struct.pack(fmt + "I", len(thumb)))]

    # the sizes do not depend on the offsets: lay out once, then write with them
    exif_at = 8 + len(ifd(ifd0(0), 8, 0, fmt))
    exif_b = ifd(exif, exif_at, 0, fmt) if with_exif else b""
    ifd1_at = exif_at + len(exif_b)
    thumb_at = ifd1_at + len(ifd(ifd1(0), ifd1_at, 0, fmt))
    head = (b"MM\0*" if fmt == ">" else b"II*\0") + struct.pack(fmt + "I", 8)
    return head + ifd(ifd0(exif_at), 8, ifd1_at, fmt) + exif_b + ifd(ifd1(thumb_at), ifd1_at, 0, fmt) + thumb


def picture():
    img = Image.new("RGB", (900, 600), (70, 110, 160))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 380, 900, 600), fill=(60, 90, 50))
    d.ellipse((620, 70, 760, 210), fill=(250, 220, 120))
    for i in range(12):
        d.rectangle((40 + i * 70, 300 - (i % 4) * 40, 90 + i * 70, 420), fill=(150 + i * 6, 120, 100))
    return img


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    img = picture()
    th = BytesIO()
    img.resize((160, 107)).save(th, "JPEG", quality=70)
    th = th.getvalue()
    img.save(os.path.join(out, "sony.jpg"), "JPEG", quality=90, exif=b"Exif\0\0" + tiff("<", th))
    img.save(os.path.join(out, "bigendian.jpg"), "JPEG", quality=90, exif=b"Exif\0\0" + tiff(">", th))
    img.save(os.path.join(out, "noexifptr.jpg"), "JPEG", quality=90, exif=b"Exif\0\0" + tiff("<", th, False))
    img.save(os.path.join(out, "noexif.jpg"), "JPEG", quality=90)
    with open(os.path.join(out, "sony.arw"), "wb") as f:
        f.write(tiff("<", th) + bytes(range(256)) * 2048)


if __name__ == "__main__":
    main()
