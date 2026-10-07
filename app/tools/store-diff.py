#!/usr/bin/env python3
"""The slots that differ between two dumps of the camera's settings store
(Diagnostics -> Dump camera settings: /AINTFILM/STORE001.TXT, STORE002.TXT...).
Dump, change ONE setting in the camera's menu, dump again: the slots printed
are the ones that setting lives in.

    store-diff.py STORE001.TXT STORE002.TXT
"""
import sys


def load(p):
    head, slots = "", {}
    for line in open(p, encoding="utf-8", errors="replace"):
        if line.startswith("#"):
            head = line.strip()
            continue
        parts = line.split()
        if len(parts) == 3:
            slots[parts[0]] = parts[2]
    return head, slots


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    (ha, a), (hb, b) = load(sys.argv[1]), load(sys.argv[2])
    print("A:", ha, "(%d slots)" % len(a))
    print("B:", hb, "(%d slots)" % len(b))
    n = 0
    for k in sorted(set(a) | set(b)):
        if a.get(k) != b.get(k):
            n += 1
            va, vb = a.get(k, "-"), b.get(k, "-")
            ints = ""
            if len(va) <= 8 and len(vb) <= 8 and va != "-" and vb != "-":
                ints = "   (%d -> %d little-endian)" % (int.from_bytes(bytes.fromhex(va), "little"),
                                                       int.from_bytes(bytes.fromhex(vb), "little"))
            print("%s  %s -> %s%s" % (k, va, vb, ints))
    print("%d slot(s) differ" % n)


if __name__ == "__main__":
    main()
