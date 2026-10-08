#!/usr/bin/env python3
"""Generates one ORIGINAL vector logo per brand in app/assets/lenses.json.

    python3 tools/gen_brand_logos.py        # needs: pip install fonttools

Output: docs/brand-logos/<slug>.svg + index.json (brand -> file).

Design rules (so a UI layer can reuse them as-is):
  * viewBox 0 0 64 64, rounded-square badge, no text elements (monogram is
    outlined from the bundled Roboto Medium, Apache-2.0), no gradients,
    no opacity, no filters.
  * every colour is an exact RGBA4444 level (#RRGGBB with each channel a
    multiple of 0x11), like the app palette in NOTAS.md.
  * the marks are invented here: a monogram over a generic geometric motif.
    They do not reproduce, and are not meant to resemble, any real logo.
"""
import json, math, os, re, unicodedata
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "brand-logos")
FONT = TTFont(os.path.join(ROOT, "app", "assets", "fonts", "Roboto-Medium.ttf"))
GS, CMAP = FONT.getGlyphSet(), FONT.getBestCmap()

# Brand -> monogram (2 characters, unique; all present in the font subset)
MONO = {"7Artisans": "7A", "TTArtisan": "TT", "Konica Minolta": "KM", "Konica": "Ko",
        "Carl Zeiss Jena": "CJ", "Meyer-Optik": "MO", "Voigtländer": "Vo", "Mir": "Mi",
        "MTO": "MT", "Tair": "Ta", "Zenit": "Ze", "Zeiss": "Zs", "Sun": "Su", "Sigma": "Sg",
        "Samyang": "Sa", "Sony": "So", "Soligor": "Sl", "Schacht": "Sc", "Schneider": "Sn",
        "Steinheil": "St", "Panasonic": "Pa", "Pentacon": "Pc", "Pentax": "Px", "Petri": "Pe",
        "Praktica": "Pr", "Mamiya": "Ma", "Meike": "Me", "Minolta": "Mn", "Miranda": "Mr",
        "Mitakon": "Mk", "Tamron": "Tm", "Tokina": "Tk", "Topcon": "Tp", "Toshiba": "Ts",
        "Viltrox": "Vi", "Vivitar": "Vv", "Laowa": "La", "Leica": "Le", "Lensbaby": "Lb",
        "Lomography": "Lo", "Olympus": "Ol", "Opteka": "Op", "Ricoh": "Ri", "Rodenstock": "Rd",
        "Rollei": "Ro", "Canon": "Ca", "Chinon": "Ch", "Contax": "Cx", "Cosina": "Cs",
        "Nikon": "Ni", "Helios": "He", "Industar": "In", "Jupiter": "Ju", "Irix": "Ix",
        "Arsenal": "Ar", "Beroflex": "Bf", "Enna": "En", "Fotasy": "Fo", "Fujica": "Fu",
        "Quantaray": "Qu", "Yashica": "Ya", "Yongnuo": "Yn"}

def mono(brand):
    return MONO.get(brand, brand[:2].title())

def q(v):  # nearest RGBA4444 level
    return max(0, min(255, int(round(v / 17.0)) * 17))

def hexc(rgb):
    return "#%02x%02x%02x" % tuple(q(c) for c in rgb)

def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))

def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

# deep backgrounds; accent = bright complement used for the motif edge
PALETTES = ["#1f4e79", "#7a1f2b", "#1f6b4a", "#5a2d82", "#8a5a00", "#14636e",
            "#3b3b3b", "#a33a00", "#2a3f9a", "#6b6b00"]

def glyph_path(text, cx, cy, h):
    """Outline `text` centred at (cx,cy) with cap height ~h; returns path d."""
    upm = FONT["head"].unitsPerEm
    caps = getattr(FONT["OS/2"], "sCapHeight", 0.711 * upm) or 0.711 * upm
    s = h / caps
    adv, items = 0, []
    for ch in text:
        gn = CMAP[ord(ch)]
        items.append((gn, adv)); adv += GS[gn].width
    # bounds for centring
    bp = BoundsPen(GS)
    for gn, x in items:
        GS[gn].draw(TransformPen(bp, (1, 0, 0, 1, x, 0)))
    x0, y0, x1, y1 = bp.bounds
    ox = cx - (x0 + x1) / 2.0 * s
    oy = cy + (y0 + y1) / 2.0 * s
    sp = SVGPathPen(GS, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    for gn, x in items:
        GS[gn].draw(TransformPen(sp, (s, 0, 0, -s, ox + x * s, oy)))
    return sp.getCommands()

def motif(kind, c, fg):
    """Generic geometric motifs, drawn on a 64x64 badge in colour c."""
    st = 'fill="none" stroke="%s" stroke-width="3"' % c
    if kind == 0:
        return "".join('<circle cx="46" cy="18" r="%d" %s/>' % (r, st) for r in (8, 15, 22, 29))
    if kind == 1:  # iris
        pts = []
        for i in range(6):
            a = math.radians(60 * i + 15)
            pts.append("M32 32L%.1f %.1f" % (32 + 44 * math.cos(a), 32 + 44 * math.sin(a)))
        return '<path d="%s" %s/><circle cx="32" cy="32" r="24" %s/>' % ("".join(pts), st, st)
    if kind == 2:
        return "".join('<path d="M%d 72L%d -8" %s/>' % (x, x + 40, st) for x in range(-44, 80, 12))
    if kind == 3:
        return '<circle cx="50" cy="14" r="26" fill="%s"/>' % c
    if kind == 4:
        return "".join('<path d="M-4 %dL32 %dL68 %d" %s/>' % (y + 14, y - 8, y + 14, st) for y in (30, 42, 54, 66))
    if kind == 5:
        pts = " ".join("%.1f,%.1f" % (32 + 30 * math.cos(math.radians(60 * i)), 32 + 30 * math.sin(math.radians(60 * i))) for i in range(6))
        return '<polygon points="%s" %s/>' % (pts, st)
    if kind == 6:
        return '<polygon points="0,64 0,24 40,64" fill="%s"/><polygon points="64,0 64,34 30,0" fill="%s"/>' % (c, c)
    if kind == 7:
        return '<polygon points="0,64 64,0 64,64" fill="%s"/>' % c
    if kind == 8:
        return "".join('<circle cx="%d" cy="%d" r="3" fill="%s"/>' % (x, y, c) for x in range(8, 64, 14) for y in range(8, 64, 14))
    if kind == 9:
        return '<path d="M32 -4V68M-4 32H68" %s/><circle cx="32" cy="32" r="20" %s/>' % (st, st)
    if kind == 10:
        return "".join('<circle cx="%d" cy="%d" r="22" fill="%s"/>' % (x, y, c) for x, y in ((0, 0), (64, 64)))
    if kind == 11:
        return "".join('<path d="M-4 %dQ8 %d 20 %dT44 %dT68 %d" %s/>' % (y, y - 10, y, y, y, st) for y in (16, 30, 44, 58))
    raise ValueError(kind)

NKINDS = 12

def slug(b):
    s = unicodedata.normalize("NFKD", b).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")

def make(brand, idx):
    kind = idx % NKINDS
    pal = (idx * 7 + idx // NKINDS * 3) % len(PALETTES)
    bg = rgb(PALETTES[pal])
    c_bg, c_motif = hexc(bg), hexc(mix(bg, (255, 255, 255), 0.22))
    c_plate = hexc(mix(bg, (0, 0, 0), 0.35))
    text = mono(brand)
    h = 20
    d = glyph_path(text, 32, 33, h)
    return pal, kind, (
        '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="%s">\n'
        '  <title>%s</title>\n'
        '  <desc>Original placeholder mark (monogram over a geometric motif); not the brand\'s real logo.</desc>\n'
        '  <defs><clipPath id="c"><rect width="64" height="64" rx="12"/></clipPath></defs>\n'
        '  <g clip-path="url(#c)">\n'
        '    <rect width="64" height="64" fill="%s"/>\n'
        '    %s\n'
        '    <circle cx="32" cy="32" r="22" fill="%s"/>\n'
        '  </g>\n'
        '  <path d="%s" fill="#ffffff"/>\n'
        '</svg>\n' % (brand.replace("&", "and"), brand.replace("&", "and"), c_bg, motif(kind, c_motif, "#ffffff"), c_plate, d))

def main():
    os.makedirs(OUT, exist_ok=True)
    cat = json.load(open(os.path.join(ROOT, "app", "assets", "lenses.json"), encoding="utf-8"))
    brands = [b["brand"] for b in cat["brands"]]
    index, combos, monos = {}, set(), set()
    for i, b in enumerate(brands):
        pal, kind, svg = make(b, i)
        assert (pal, kind) not in combos, b
        combos.add((pal, kind))
        assert mono(b) not in monos, b
        monos.add(mono(b))
        fn = slug(b) + ".svg"
        open(os.path.join(OUT, fn), "w", encoding="utf-8").write(svg)
        index[b] = fn
    json.dump({"viewBox": "0 0 64 64", "license": "MIT (original artwork)", "logos": index},
              open(os.path.join(OUT, "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(len(index), "logos")

if __name__ == "__main__":
    main()
