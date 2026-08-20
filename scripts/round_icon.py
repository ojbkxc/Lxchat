import sys
from PIL import Image, ImageDraw

def round_icon(input_path, output_path, radius_ratio=0.22):
    img = Image.open(input_path).convert("RGBA")

    # Find bounding box of non-transparent pixels
    alpha = img.split()[3]
    bbox = alpha.getbbox()
    if bbox is None:
        bbox = (0, 0, img.width, img.height)

    # Crop to content
    cropped = img.crop(bbox)
    w, h = cropped.size
    size = max(w, h)

    # Pad to square, centered
    square = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    square.paste(cropped, ((size - w) // 2, (size - h) // 2))

    # Round corner radius proportional to size
    r = int(size * radius_ratio)

    # Create rounded corner mask
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), (size - 1, size - 1)], radius=r, fill=255)

    # Apply mask
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(square, mask=mask)
    result.save(output_path, "PNG")
    print(f"Saved {output_path} ({size}x{size}, radius={r}px)")

if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "icon.png"
    dst = sys.argv[2] if len(sys.argv) > 2 else "icon_rounded.png"
    round_icon(src, dst)
