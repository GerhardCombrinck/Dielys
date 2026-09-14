"""Renders the Play Store icon and feature graphic (docs/play/README.md).

Run from the repo root: python docs/play/graphics/make_graphics.py

The mark is the launcher icon's own path (res/drawable/ic_launcher_foreground.xml,
`M45.5,40 h8 a11,11 0 0 1 0,28 h-8 z` on a 108 grid, navy ground), scaled so the
72-unit area a launcher actually shows fills the image — the Play icon then looks
like the icon on the phone. Everything is drawn at 4x and scaled down for clean edges.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).parent
SS = 4  # supersampling

NAVY = (0x1B, 0x2A, 0x4A)
NAVY_DEEP = (0x0E, 0x17, 0x28)
AMBER = (0xE8, 0xA3, 0x3D)
WHITE = (0xFF, 0xFF, 0xFF)
SAND = (0xF6, 0xF4, 0xEF)

FONTS = Path("C:/Windows/Fonts")


def draw_mark(draw: ImageDraw.ImageDraw, left: float, top: float, unit: float, fill) -> None:
    """The launcher "D" with the 108-grid point (0, 0) at (left, top), `unit` px per grid unit.

    The SVG arc's 11 radius is too small to span 28 units, so SVG scales it up to 14:
    the shape is a bar from x 45.5 to 53.5 plus a half disc of radius 14 centred on
    (53.5, 54).
    """

    # The path spans x 45.5..67.5, so it sits 2.5 units right of the grid's
    # centre. Shifted back here, so the mark is centred in the tile.
    def p(x: float, y: float) -> tuple[float, float]:
        return left + (x - 2.5) * unit, top + y * unit

    draw.rectangle([p(45.5, 40), p(53.5, 68)], fill=fill)
    draw.pieslice([p(39.5, 40), p(67.5, 68)], start=-90, end=90, fill=fill)


def icon() -> None:
    size = 512 * SS
    img = Image.new("RGB", (size, size), NAVY)
    draw = ImageDraw.Draw(img)
    unit = size / 72  # the launcher-visible 72 units, 18..90 on the 108 grid
    draw_mark(draw, -18 * unit, -18 * unit, unit, WHITE)
    img.resize((512, 512), Image.LANCZOS).save(OUT / "icon-512.png", optimize=True)


def feature(tagline: str, name: str) -> None:
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), NAVY)
    draw = ImageDraw.Draw(img)

    # A deeper band along the bottom, for depth without a gradient library.
    for y in range(h):
        t = max(0.0, (y / h - 0.35) / 0.65)
        c = tuple(round(a + (b - a) * t * 0.85) for a, b in zip(NAVY, NAVY_DEEP))
        draw.line([(0, y), (w, y)], fill=c)

    # Icon tile on the left: the mark on navy, framed like a launcher icon.
    tile = 260 * SS
    tx, ty = 150 * SS, (h - tile) // 2
    draw.rounded_rectangle(
        [tx, ty, tx + tile, ty + tile], radius=58 * SS, fill=NAVY, outline=(0x3A, 0x4A, 0x6C), width=3 * SS
    )
    unit = tile / 72
    draw_mark(draw, tx - 18 * unit, ty - 18 * unit, unit, WHITE)

    # Words on the right.
    bold = ImageFont.truetype(str(FONTS / "segoeuib.ttf"), 112 * SS)
    semi = ImageFont.truetype(str(FONTS / "seguisb.ttf"), 46 * SS) if (FONTS / "seguisb.ttf").exists() else ImageFont.truetype(str(FONTS / "segoeuib.ttf"), 46 * SS)
    body = ImageFont.truetype(str(FONTS / "segoeui.ttf"), 30 * SS)

    x = 470 * SS
    draw.text((x, 128 * SS), "Die Lys", font=bold, fill=WHITE)
    draw.rounded_rectangle([x + 4 * SS, 268 * SS, x + 110 * SS, 276 * SS], radius=4 * SS, fill=AMBER)
    draw.text((x, 292 * SS), "Sit dit op die lys", font=semi, fill=AMBER)
    draw.text((x, 360 * SS), tagline, font=body, fill=(0xC9, 0xD1, 0xE0))

    img.resize((1024, 500), Image.LANCZOS).save(OUT / name, optimize=True)


if __name__ == "__main__":
    icon()
    feature("Gedeelde lyste vir jou huishouding", "feature-1024x500-af.png")
    feature("Shared lists for your household", "feature-1024x500-en.png")
    print("wrote", *sorted(p.name for p in OUT.glob("*.png")))
