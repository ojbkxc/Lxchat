"""Generate LxChat launcher icon PNGs for all Android mipmap densities.

The design mirrors the vector drawable ``ic_launcher_foreground.xml``:
  * white background
  * a bold geometric blue "L" (filled rectangles)
  * three short orange accent strokes beneath the L

Output:
  * mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
  * mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png
  * app/src/main/ic_launcher-playstore.png  (512x512)

Supersampling (4x) is used for anti-aliasing, then downscaled with Lanczos.
"""

import os
from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Design constants (in the 240x240 viewport, matching the vector XML)
# ---------------------------------------------------------------------------
VIEWPORT = 240

# Blue "L" — two filled rectangles
L_VERTICAL = (78, 42, 110, 162)    # x1, y1, x2, y2
L_HORIZONTAL = (78, 130, 168, 162)

# Orange accent strokes beneath the L (matching the XML path data)
ACCENT_LINES = [
    ((92, 188), (110, 176)),
    ((110, 189), (128, 177)),
    ((128, 188), (146, 176)),
]
ACCENT_STROKE_WIDTH = 5  # in viewport units

# Colors
BLUE = (52, 116, 184, 255)    # #3474B8
ORANGE = (255, 153, 0, 255)   # #FF9900
WHITE = (255, 255, 255, 255)
TRANSPARENT = (0, 0, 0, 0)

# Supersampling factor for anti-aliasing
SS = 4

# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _render_at_viewport(round_mask: bool) -> Image.Image:
    """Render the design at full viewport size (with supersampling)."""
    size = VIEWPORT * SS
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    draw = ImageDraw.Draw(img)

    # Blue L — vertical bar
    x1, y1, x2, y2 = L_VERTICAL
    draw.rectangle(
        [x1 * SS, y1 * SS, x2 * SS, y2 * SS],
        fill=BLUE,
    )
    # Blue L — horizontal foot
    x1, y1, x2, y2 = L_HORIZONTAL
    draw.rectangle(
        [x1 * SS, y1 * SS, x2 * SS, y2 * SS],
        fill=BLUE,
    )

    # Orange accent lines
    line_width = ACCENT_STROKE_WIDTH * SS
    for (p1, p2) in ACCENT_LINES:
        draw.line(
            [p1[0] * SS, p1[1] * SS, p2[0] * SS, p2[1] * SS],
            fill=ORANGE,
            width=line_width,
        )
        # Pillow lines have flat caps; draw small filled circles at the
        # endpoints to approximate the round caps used in the vector XML.
        r = line_width / 2
        for px, py in (p1, p2):
            draw.ellipse(
                [px * SS - r, py * SS - r, px * SS + r, py * SS + r],
                fill=ORANGE,
            )

    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        result.paste(img, (0, 0), mask)
        img = result

    return img


def render_icon(size: int, round_mask: bool = False) -> Image.Image:
    """Render the icon at the requested pixel size with anti-aliasing."""
    big = _render_at_viewport(round_mask=round_mask)
    return big.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def main() -> None:
    res_dir = os.path.join(
        r"C:\GitHub\Lxchat", "app", "src", "main", "res"
    )
    main_dir = os.path.join(r"C:\GitHub\Lxchat", "app", "src", "main")

    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    generated = []
    for folder, size in sizes.items():
        dir_path = os.path.join(res_dir, folder)
        os.makedirs(dir_path, exist_ok=True)

        icon = render_icon(size, round_mask=False)
        icon_path = os.path.join(dir_path, "ic_launcher.png")
        icon.save(icon_path, "PNG")
        generated.append((icon_path, size))

        icon_round = render_icon(size, round_mask=True)
        round_path = os.path.join(dir_path, "ic_launcher_round.png")
        icon_round.save(round_path, "PNG")
        generated.append((round_path, size))

    # 512x512 Play Store version
    playstore = render_icon(512, round_mask=False)
    playstore_path = os.path.join(main_dir, "ic_launcher-playstore.png")
    playstore.save(playstore_path, "PNG")
    generated.append((playstore_path, 512))

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    print("Generated icons:")
    for path, sz in generated:
        rel = os.path.relpath(path, root)
        print(f"  {rel}  ({sz}x{sz})")


if __name__ == "__main__":
    main()