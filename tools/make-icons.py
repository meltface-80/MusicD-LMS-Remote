#!/usr/bin/env python3
"""Generate every icon Mallard wears from its one mark, tools/brand/mallard-mark.png.

    pip install Pillow
    python3 tools/make-icons.py [tools/brand/mallard-mark.png]

THE MARK (owner decision): as Rouen's is the MusicD duck blended with Roon's bars, Mallard's is the duck blended with
Lyrion's waveform — but the REVERSE of Rouen's, and in Mandarin's brass, because a first cut on Rouen's own dark artwork
read as Rouen's. Mandarin's gold icon: the right half of its black-ink duck (lens, headphones, crest), cut at an ink
line, with the waveform on the left — half of Lyrion's waveform, its tall centre bar at the line, in the same ink. Half
a waveform because half a duck, and because the full ten-bar waveform turned to hatching at launcher sizes. The mark is
a 512px square on its own ground, framed already; nothing here trims it, and the ground is read from its corner.

Run again only when the mark changes; the outputs are committed. What it writes, and why each differs:
  * public/icons: the PWA. `any` is the mark full-bleed; `maskable` is DIFFERENT artwork, the mark at 80% on its ground,
    because Android crops a maskable icon to a shape of its choosing and guarantees only the centre. apple-touch-icon
    has no alpha (iOS composites it on white) and no corners of our own (iOS masks it). duck-tile.png is the share
    card's corner: a rounded square with an ink rim. Every full-size icon draws the mark at 88%, so a
    launcher's disc or rounded square never cuts the headphone cup at the edge.
  * android mipmaps: ic_launcher (rounded square), ic_launcher_round (disc), and the adaptive foreground: the mark at
    72/108 on its ground, since a launcher shows only the middle 72dp of the 108dp layer (colors.xml's icon_background
    is the same ground, so the layers meet without a seam).
  * android drawables: menu_brand (the side menu's badge) and duck_tile (the share card, as the page's).
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUND = (201, 164, 92)   # replaced by the mark's own corner in main()
INK = (23, 25, 28)
ART = 0.88   # the mark's share of a full icon: clear of a launcher's mask at the edges
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def scaled(mark, size, art=1.0):
    """The mark at [size], drawn at [art] of it on its own ground."""
    canvas = Image.new("RGB", (size, size), GROUND)
    side = round(size * art)
    canvas.paste(mark.resize((side, side), Image.LANCZOS), ((size - side) // 2, (size - side) // 2))
    return canvas


def masked(img, shape, radius=0.0):
    """[img] cut to a disc or a rounded square, drawn at 4x and brought down so the edge is smooth."""
    s = img.width
    m = Image.new("L", (s * 4, s * 4), 0)
    d = ImageDraw.Draw(m)
    if shape == "disc":
        d.ellipse([0, 0, s * 4 - 1, s * 4 - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, s * 4 - 1, s * 4 - 1], radius=round(s * 4 * radius), fill=255)
    out = img.convert("RGBA")
    out.putalpha(m.resize((s, s), Image.LANCZOS))
    return out


def tile(mark, size):
    """The share card's corner: the mark in a rounded square with an ink rim (Rouen's tile has a brass one on dark)."""
    big = size * 4
    out = masked(scaled(mark, big, ART), "round", 0.2)
    d = ImageDraw.Draw(out)
    rim = round(big * 0.014)   # about 3.5px at 256
    inset = round(big * 0.008) + rim // 2
    d.rounded_rectangle([inset, inset, big - 1 - inset, big - 1 - inset], radius=round(big * 0.19), outline=INK, width=rim)
    return out.resize((size, size), Image.LANCZOS)


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT))


def main(src):
    global GROUND
    mark = Image.open(src).convert("RGB")
    if mark.width != mark.height:
        sys.exit("the mark must be square")
    GROUND = mark.getpixel((2, 2))
    icons = os.path.join(ROOT, "public", "icons")
    save(scaled(mark, 192, ART), icons, "icon-192.png")
    save(scaled(mark, 512, ART), icons, "icon-512.png")
    save(scaled(mark, 192, 0.8), icons, "icon-192-maskable.png")
    save(scaled(mark, 512, 0.8), icons, "icon-512-maskable.png")
    save(scaled(mark, 180, ART), icons, "apple-touch-icon.png")
    save(scaled(mark, 32), icons, "favicon-32.png")
    save(scaled(mark, 16), icons, "favicon-16.png")
    save(tile(mark, 256), icons, "duck-tile.png")
    for name, k in DENSITIES.items():
        px = round(48 * k)
        save(masked(scaled(mark, px, ART), "round", 0.22), RES, "mipmap-" + name, "ic_launcher.png")
        save(masked(scaled(mark, px, 0.84), "disc"), RES, "mipmap-" + name, "ic_launcher_round.png")
        save(scaled(mark, round(108 * k), 72 / 108), RES, "mipmap-" + name, "ic_launcher_foreground.png")
    save(masked(scaled(mark, 192, ART), "round", 0.22), RES, "drawable-nodpi", "menu_brand.png")
    save(tile(mark, 256), RES, "drawable-nodpi", "duck_tile.png")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools", "brand", "mallard-mark.png"))
