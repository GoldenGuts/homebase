#!/usr/bin/env python3
"""
Store screenshots from real device captures: the capture in a rounded phone frame on the same dark
gradient as the widget renders in store/screenshots, with a title and a subtitle above it.

    python3 tools/frame_shots.py <spec.json> <out dir> [--size 1080x1920]

spec.json is a list of {"src": "capture.png", "title": "...", "sub": "...", "out": "01_name.png"}.
Needs Pillow. Fonts come from the app (app/src/main/assets/fonts).
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "app/src/main/assets/fonts")


def gradient(w, h):
    # teal top-left -> near-black -> plum bottom-right, like the renders
    top, mid, bot = (16, 52, 62), (11, 13, 18), (48, 18, 40)
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * .35 + y / h * .65)
            a, b, u = (top, mid, t / .5) if t < .5 else (mid, bot, (t - .5) / .5)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
    return img


def frame(src, title, sub, size):
    W, H = size
    s = W / 1080
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    tf = ImageFont.truetype(os.path.join(FONTS, "Inter-ExtraBold.ttf"), int(80 * s))
    sf = ImageFont.truetype(os.path.join(FONTS, "Inter-Medium.ttf"), int(38 * s))
    x0 = int(62 * s)
    d.text((x0, int(100 * s)), title, font=tf, fill=(245, 246, 250))
    d.text((x0, int(212 * s)), sub, font=sf, fill=(150, 158, 175))

    shot = Image.open(src).convert("RGB")
    top = int(320 * s)
    ph = H - top + int(260 * s)              # the phone runs off the bottom edge
    pw = int(ph * shot.width / shot.height)
    if pw > W - 2 * int(120 * s):
        pw = W - 2 * int(120 * s); ph = int(pw * shot.height / shot.width)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    bezel = int(14 * s); r = int(64 * s)
    px = (W - pw) // 2
    # soft shadow, bezel, then the screen with rounded corners
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle((px - bezel, top - bezel + int(24 * s), px + pw + bezel, top + ph + bezel), r + bezel, fill=150)
    img.paste((0, 0, 0), (0, 0), sh.filter(ImageFilter.GaussianBlur(int(40 * s))))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((px - bezel, top - bezel, px + pw + bezel, top + ph + bezel), r + bezel, fill=(28, 30, 38), outline=(60, 64, 78), width=max(2, int(3 * s)))
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw, ph), r, fill=255)
    img.paste(shot, (px, top), mask)
    return img


def main():
    spec, out = sys.argv[1], sys.argv[2]
    size = (1080, 1920)
    if "--size" in sys.argv:
        size = tuple(int(v) for v in sys.argv[sys.argv.index("--size") + 1].split("x"))
    base = os.path.dirname(os.path.abspath(spec))
    os.makedirs(out, exist_ok=True)
    for e in json.load(open(spec)):
        frame(os.path.join(base, e["src"]), e["title"], e["sub"], size).save(os.path.join(out, e["out"]), optimize=True)
        print(e["out"])


if __name__ == "__main__":
    main()
