#!/usr/bin/env python3
"""Generate the PWA / favicon set in public/icons from the MusicD logo.

    pip install Pillow
    python3 tools/make-icons.py path/to/logo.png

A one-off, run again only when the logo changes. It is a script rather than a
build step because the icons are committed assets — the server has no image
toolchain and should not grow one to redraw the same seven files on boot.

Three things here are not obvious and are the reason this exists rather than
someone hand-resizing in an image editor:

  * The source is TRIMMED to its artwork first, so every output frames the duck
    identically instead of inheriting whatever margin the source file carried.
  * `any` and `maskable` are DIFFERENT artwork. Android crops a maskable icon
    to a shape of its choosing and guarantees only the central 80%, so the
    maskable pair is drawn smaller to sit inside that safe zone. Shipping one
    file under both purposes loses the beak and the headphone cup on every
    launcher that masks to a circle.
  * The 16/32px favicons get a contrast boost. The logo is a fine engraving;
    scaled that far down its hatching averages to mid-grey and reads as a
    smudge, so the small sizes are pushed back towards black and white.
"""
import sys
import os
from PIL import Image, ImageChops, ImageOps

BG = (0, 0, 0)
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "public", "icons")


def main(src_path):
    src = Image.open(src_path).convert("RGB")
    probe = ImageChops.difference(src, Image.new("RGB", src.size, BG)).convert("L")
    box = probe.point(lambda v: 255 if v > 28 else 0).getbbox()
    if not box:
        sys.exit("the source is entirely background — nothing to crop to")
    art = src.crop(box)

    def icon(size, inset, gain=None):
        canvas = Image.new("RGB", (size, size), BG)
        side = int(size * inset)
        a = art.copy()
        a.thumbnail((side, side), Image.LANCZOS)
        if gain:
            g = a.convert("L").point(lambda v: min(255, int(v * gain)))
            a = ImageOps.autocontrast(g, cutoff=1).convert("RGB")
        canvas.paste(a, ((size - a.width) // 2, (size - a.height) // 2))
        return canvas

    os.makedirs(OUT, exist_ok=True)
    jobs = [
        # (filename, size, inset, gain)
        ("icon-192.png",           192, 0.88, None),
        ("icon-512.png",           512, 0.88, None),
        ("icon-192-maskable.png",  192, 0.72, None),
        ("icon-512-maskable.png",  512, 0.72, None),
        # iOS: no alpha (it would composite on white) and no corner radius of
        # our own, because iOS applies its own mask.
        ("apple-touch-icon.png",   180, 0.86, None),
        ("favicon-32.png",          32, 0.94, 1.5),
        ("favicon-16.png",          16, 0.98, 1.5),
    ]
    for name, size, inset, gain in jobs:
        icon(size, inset, gain).save(os.path.join(OUT, name), optimize=True)
        print("wrote", name)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: make-icons.py <logo.png>")
    main(sys.argv[1])
