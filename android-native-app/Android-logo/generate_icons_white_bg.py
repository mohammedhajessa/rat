"""
Generate launcher icons with TRANSPARENT background from play_store_512.png.
Run from Android-logo folder: python generate_icons_white_bg.py
Requires: pip install Pillow
"""
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Install Pillow: pip install Pillow")
    raise

SCRIPT_DIR = Path(__file__).resolve().parent
SRC = SCRIPT_DIR / "play_store_512.png"
# Dark green/teal in source image (replace with transparent)
BG_RGB = (11, 43, 38)  # #0B2B26
# Max per-channel difference to treat pixel as background
BG_TOLERANCE = 45
TRANSPARENT = (0, 0, 0, 0)

# Android mipmap sizes: (folder_suffix, size_px)
SIZES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]


def is_background(pixel):
    if len(pixel) == 4 and pixel[3] < 128:
        return True
    r, g, b = pixel[0], pixel[1], pixel[2]
    return (
        abs(r - BG_RGB[0]) <= BG_TOLERANCE
        and abs(g - BG_RGB[1]) <= BG_TOLERANCE
        and abs(b - BG_RGB[2]) <= BG_TOLERANCE
    )


def replace_bg_with_transparent(img):
    img = img.convert("RGBA")
    w, h = img.size
    out = Image.new("RGBA", (w, h))
    px_in = img.load()
    px_out = out.load()
    for y in range(h):
        for x in range(w):
            p = px_in[x, y]
            if is_background(p):
                px_out[x, y] = TRANSPARENT
            else:
                # keep RGB, ensure full opacity for foreground
                px_out[x, y] = (p[0], p[1], p[2], 255)
    return out


def main():
    if not SRC.exists():
        print(f"Missing {SRC}")
        return
    base = replace_bg_with_transparent(Image.open(SRC).convert("RGBA"))
    base = base.resize((512, 512), Image.Resampling.LANCZOS)

    for folder_name, size in SIZES:
        folder = SCRIPT_DIR / folder_name
        folder.mkdir(parents=True, exist_ok=True)
        resized = base.resize((size, size), Image.Resampling.LANCZOS)
        resized.save(folder / "ic_launcher.png", "PNG")
        resized.save(folder / "ic_launcher_round.png", "PNG")
        print(f"Wrote {folder_name}/ ic_launcher.png, ic_launcher_round.png ({size}px)")

    print("Done. Rebuild the app to see the eagle icon with no background (transparent).")


if __name__ == "__main__":
    main()
