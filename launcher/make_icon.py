"""Draw the Survival Rebalance icon: a four-pointed star over a balance beam.

Writes launcher/icon.ico (for the exe) and src/survivalrebalance/gui/icon.png (for the window).
Run: python launcher/make_icon.py
"""
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
S = 1024


def star(d, cx, cy, r_out, r_in, fill):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, fill=fill)


def draw():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((40, 40, S - 40, S - 40), radius=190, fill=(22, 34, 56, 255), outline=(92, 128, 170, 255), width=24)
    # the beam and its pivot
    d.polygon([(S / 2, 560), (S / 2 - 80, 800), (S / 2 + 80, 800)], fill=(92, 128, 170, 255))
    d.rounded_rectangle((180, 520, S - 180, 572), radius=26, fill=(214, 224, 236, 255))
    for x in (250, S - 250):
        d.line((x, 572, x, 690), fill=(214, 224, 236, 255), width=16)
        d.pieslice((x - 95, 600, x + 95, 790), 0, 180, fill=(214, 224, 236, 255))
    star(d, S / 2, 330, 210, 52, (246, 178, 52, 255))
    return img


def main():
    img = draw()
    ico = os.path.join(HERE, "icon.ico")
    img.save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    png = os.path.join(HERE, "..", "src", "survivalrebalance", "gui", "icon.png")
    img.resize((256, 256), Image.LANCZOS).save(png)
    print("wrote", ico, "and", os.path.normpath(png))


if __name__ == "__main__":
    main()
