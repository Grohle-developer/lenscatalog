# Brand logos

One original 64×64 vector mark per brand in `app/assets/lenses.json`
(`index.json` maps brand name → file). Regenerate with
`python3 tools/gen_brand_logos.py` (needs `pip install fonttools`).

- **Original**: a two-letter monogram over a generic geometric motif. They are
  placeholders, not the manufacturers' real logos, and are not meant to
  resemble them. Do not present them as official logos.
- **Licence**: MIT, like the rest of the project. The monogram outlines come
  from Roboto Medium (Apache-2.0, `app/assets/fonts/Roboto-LICENSE.txt`).
- **Easy to port**: `viewBox 0 0 64 64`, no `<text>`, gradients or opacity;
  only circles, polygons and paths. Every colour is an exact RGBA4444 level
  (`#RRGGBB` with each channel a multiple of `0x11`).
- Brand names are trademarks of their respective owners; this project is not
  affiliated with or endorsed by any of them.
