"""Frames raw phone screenshots as 1080x1920 (9:16) Play Store screenshots.

    python docs/play/graphics/frame_screenshots.py <raw-dir> <lang>

<raw-dir> holds `adb exec-out screencap -p` captures from a 1080x2340 phone, named as
in SHOTS below. The status and navigation bars are cropped off (they carry the time,
battery and notification icons of whoever took them), the rest is scaled into a
rounded card under a caption, on the app's own dark ground.

Never use a capture that shows a real account's email or a real household's lists.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).parent
FONTS = Path("C:/Windows/Fonts")
SS = 2

W, H = 1080, 1920
GROUND = (0x0E, 0x14, 0x22)
CARD_EDGE = (0x33, 0x40, 0x5E)
AMBER = (0xE8, 0xA3, 0x3D)
WHITE = (0xFF, 0xFF, 0xFF)

# On a 1080x2340 S24 capture: status bar above, gesture bar below.
CROP_TOP, CROP_BOTTOM = 112, 2214

SHOTS = [
    ("01-lists.png", {"af": "Al jou lyste op een plek", "en": "All your lists in one place"}),
    ("02-inkopies.png", {"af": "Belangrike items bly bo", "en": "Starred items stay on top"}),
    ("04-kamp.png", {"af": "Sien wat reeds klaar is", "en": "See what's already done"}),
    ("03-tuin.png", {"af": "Vir die huis, die tuin en die kamp", "en": "For home, garden and camping"}),
]


def frame(raw: Path, caption: str, dest: Path) -> None:
    shot = Image.open(raw).convert("RGB").crop((0, CROP_TOP, 1080, CROP_BOTTOM))

    img = Image.new("RGB", (W * SS, H * SS), GROUND)
    draw = ImageDraw.Draw(img)

    # Long captions step down in size rather than run off the edge.
    size = 62
    while True:
        font = ImageFont.truetype(str(FONTS / "segoeuib.ttf"), size * SS)
        text_w = draw.textlength(caption, font=font)
        if text_w <= (W - 120) * SS or size <= 36:
            break
        size -= 2
    draw.text(((W * SS - text_w) / 2, 120 * SS), caption, font=font, fill=WHITE)
    draw.rounded_rectangle(
        [(W / 2 - 50) * SS, 222 * SS, (W / 2 + 50) * SS, 230 * SS], radius=4 * SS, fill=AMBER
    )

    card_top, card_bottom = 300, H - 70
    card_h = card_bottom - card_top
    card_w = round(card_h * shot.width / shot.height)
    left = (W - card_w) // 2

    scaled = shot.resize((card_w * SS, card_h * SS), Image.LANCZOS)
    mask = Image.new("L", scaled.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, *scaled.size], radius=44 * SS, fill=255)
    img.paste(scaled, (left * SS, card_top * SS), mask)
    draw.rounded_rectangle(
        [left * SS, card_top * SS, (left + card_w) * SS, card_bottom * SS],
        radius=44 * SS,
        outline=CARD_EDGE,
        width=3 * SS,
    )

    img.resize((W, H), Image.LANCZOS).save(dest, optimize=True)


def main() -> None:
    raw_dir, lang = Path(sys.argv[1]), sys.argv[2]
    for i, (name, captions) in enumerate(SHOTS, start=1):
        dest = OUT / f"screenshot-{lang}-{i}.png"
        frame(raw_dir / name, captions[lang], dest)
        print("wrote", dest.name)


if __name__ == "__main__":
    main()
