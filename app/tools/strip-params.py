#!/usr/bin/env python3
"""Remove MethodParameters attributes from class files, in place.

Since JDK 21, javac writes a MethodParameters attribute for every constructor
with a mandated parameter (the outer instance of an inner or anonymous class),
with no name. The d8 in build-tools 30.0.3 — the last that targets API 10
cleanly — dies on a nameless entry (NullPointerException in DexString). The
attribute only feeds reflection's Parameter.getName(), which Android 2.3 does
not have, so dropping it changes nothing the camera runs.

    tools/strip-params.py <dir-or-class>...
"""
import os
import struct
import sys


def strip(data):
    """The class file without MethodParameters, and how many were removed."""
    pos = 10
    count = struct.unpack_from(">H", data, 8)[0]
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack_from(">H", data, pos + 1)[0]
            utf8[i] = data[pos + 3:pos + 3 + n]
            pos += 3 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise ValueError("constant pool tag %d" % tag)
        i += 1
    pos += 6
    ifaces = struct.unpack_from(">H", data, pos)[0]
    pos += 2 + 2 * ifaces

    def skip_attrs(p):
        n = struct.unpack_from(">H", data, p)[0]
        p += 2
        for _ in range(n):
            p += 6 + struct.unpack_from(">I", data, p + 2)[0]
        return p

    fields = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(fields):
        pos = skip_attrs(pos + 6)
    out = bytearray(data[:pos])
    methods = struct.unpack_from(">H", data, pos)[0]
    out += data[pos:pos + 2]
    pos += 2
    removed = 0
    for _ in range(methods):
        out += data[pos:pos + 6]
        pos += 6
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        kept = []
        for _ in range(n):
            name = struct.unpack_from(">H", data, pos)[0]
            length = struct.unpack_from(">I", data, pos + 2)[0]
            if utf8.get(name) == b"MethodParameters":
                removed += 1
            else:
                kept.append(data[pos:pos + 6 + length])
            pos += 6 + length
        out += struct.pack(">H", len(kept))
        for a in kept:
            out += a
    out += data[pos:]
    return bytes(out), removed


def main():
    total = 0
    paths = []
    for arg in sys.argv[1:]:
        if os.path.isdir(arg):
            for d, _, files in os.walk(arg):
                paths += [os.path.join(d, f) for f in files if f.endswith(".class")]
        else:
            paths.append(arg)
    for p in paths:
        with open(p, "rb") as f:
            data = f.read()
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise SystemExit("%s: not a class file" % p)
        new, n = strip(data)
        if n:
            with open(p, "wb") as f:
                f.write(new)
            total += n
    print("strip-params: %d MethodParameters attributes removed from %d classes" % (total, len(paths)))


if __name__ == "__main__":
    main()
