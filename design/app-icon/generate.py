"""Generate platform icon sizes from the supplied artwork (requires Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
source = Image.open(HERE / "source.png").convert("RGBA")
# The supplied rounded tile has white outer corners. Make only the connected
# outer white area transparent so adaptive backgrounds do not show white wedges.
outer = Image.new("L", source.size)
outer.putdata([255 if min(p[:3]) > 225 and max(p[:3]) - min(p[:3]) < 25 else 0
               for p in source.getdata()])
for corner in ((0, 0), (source.width - 1, 0),
               (0, source.height - 1), (source.width - 1, source.height - 1)):
    if outer.getpixel(corner) == 255:
        ImageDraw.floodfill(outer, corner, 128)
alpha = source.getchannel("A")
alpha.putdata([0 if edge == 128 else a for edge, a in zip(outer.getdata(), alpha.getdata())])
source.putalpha(alpha)
res = ROOT / "app/src/main/res"

for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96,
                      "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = res / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    source.resize((size, size), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")
    canvas = Image.new("RGBA", source.size, "#FF991F")
    art_size = round(source.width * .84)
    art = source.resize((art_size, art_size), Image.Resampling.LANCZOS)
    offset = (source.width - art_size) // 2
    canvas.alpha_composite(art, (offset, offset))
    mask = Image.new("L", source.size)
    ImageDraw.Draw(mask).ellipse((0, 0, source.width - 1, source.height - 1), fill=255)
    canvas.putalpha(mask)
    canvas.resize((size, size), Image.Resampling.LANCZOS).save(folder / "ic_launcher_round.png")

drawable = res / "drawable-nodpi"
drawable.mkdir(parents=True, exist_ok=True)
source.resize((768, 768), Image.Resampling.LANCZOS).save(drawable / "ic_launcher_artwork.png", optimize=True)
source.resize((512, 512), Image.Resampling.LANCZOS).save(HERE / "sstv.png", optimize=True)
source.save(HERE / "sstv.ico", format="ICO", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])

preview = Image.new("RGB", (864, 432), "#EAEAEA")
for i, circular in enumerate((False, True)):
    tile = Image.new("RGBA", (432, 432), "#FF991F")
    tile.alpha_composite(source.resize((240, 240), Image.Resampling.LANCZOS), (96, 96))
    tile = tile.crop((72, 72, 360, 360))
    mask = Image.new("L", (288, 288))
    draw = ImageDraw.Draw(mask)
    if circular:
        draw.ellipse((0, 0, 287, 287), fill=255)
    else:
        draw.rounded_rectangle((0, 0, 287, 287), radius=68, fill=255)
    tile.putalpha(mask)
    preview.paste(tile, (i * 432 + 72, 72), tile)
preview.save(HERE / "android-preview.png")
