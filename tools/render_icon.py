# -*- coding: utf-8 -*-
"""Render the app launcher (adaptive-icon) to a 512x512 fastlane icon.png.

Matches app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml +
drawable/ic_launcher_foreground.xml + values/colors.xml.
"""
from PIL import Image, ImageDraw

NAVY = (0x1A, 0x23, 0x7E, 0xFF)   # ic_launcher_background / meridian
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)  # ring
ORANGE = (0xFF, 0x57, 0x22, 0xFF)  # ic_launcher_arrow

SIZE = 2048  # supersample, downscale to 512
SCALE = SIZE / 108.0

def P(x, y):
    return (x * SCALE, y * SCALE)

img = Image.new("RGBA", (SIZE, SIZE), NAVY)
d = ImageDraw.Draw(img)

cx, cy = 54.0, 54.0
R_outer, R_inner = 24.0, 15.0

# White ring (evenOdd of two circles)
for r, col in ((R_outer, WHITE), (R_inner, NAVY)):
    x0, y0 = P(cx - r, cy - r)
    x1, y1 = P(cx + r, cy + r)
    d.ellipse([x0, y0, x1, y1], fill=col)

# Navy meridian cross (round caps)
w_meridian = int(3.0 * SCALE)
d.line([P(54, 39), P(54, 69)], fill=NAVY, width=w_meridian)
d.line([P(39, 54), P(69, 54)], fill=NAVY, width=w_meridian)

# Orange NE arrow
w_arrow = int(4.5 * SCALE)
d.line([P(49, 59), P(65, 43)], fill=ORANGE, width=w_arrow, joint="curve")
d.line([P(65, 43), P(57.5, 43)], fill=ORANGE, width=w_arrow, joint="curve")
d.line([P(65, 43), P(65, 50.5)], fill=ORANGE, width=w_arrow, joint="curve")

img = img.resize((512, 512), Image.LANCZOS)

for dest in (
    r"D:\workspace\AndroidApp\test\5G-Proxy-Pro\fastlane\metadata\android\en-US\images\icon.png",
    r"D:\workspace\AndroidApp\test\5G-Proxy-Pro\fastlane\metadata\android\zh-TW\images\icon.png",
):
    img.save(dest)
    print("wrote", dest)
