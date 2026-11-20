"""Turning a picture into mouse strokes (no GUI here, so it can be tested anywhere)."""

from PIL import Image, ImageFilter, ImageOps


def to_black_and_white(image, size, threshold, invert=False, outline=False):
    """Black-and-white copy of `image` scaled so its longest side is `size` pixels.

    Returns a mode "L" image where 0 (black) means "draw here" and 255 means "leave blank".
    `outline` keeps only the edges of the dark areas (far fewer strokes).
    """
    img = image.convert("RGBA")
    # Transparent areas count as white paper.
    paper = Image.new("RGBA", img.size, (255, 255, 255, 255))
    paper.alpha_composite(img)
    gray = paper.convert("L")
    scale = size / max(gray.size)
    gray = gray.resize((max(1, round(gray.width * scale)), max(1, round(gray.height * scale))), Image.LANCZOS)
    if invert:
        gray = ImageOps.invert(gray)
    bw = gray.point(lambda v: 0 if v < threshold else 255)
    if outline:
        # Edges of the black areas: a black pixel next to a white one.
        eroded = bw.filter(ImageFilter.MaxFilter(3))  # white grows into black
        edges = Image.new("L", bw.size, 255)
        b, e, out = bw.load(), eroded.load(), edges.load()
        for y in range(bw.height):
            for x in range(bw.width):
                if b[x, y] == 0 and e[x, y] == 255:
                    out[x, y] = 0
        bw = edges
    return bw


def make_strokes(bw, spacing=1):
    """Horizontal strokes (y, x_start, x_end) covering the black pixels, every `spacing` rows.

    Rows alternate direction-free; strokes are ordered top to bottom, left to right.
    With spacing > 1, a row also draws pixels that are black in the skipped rows below it, so
    thin horizontal lines aren't lost.
    """
    w, h = bw.size
    px = bw.load()
    strokes = []
    for y in range(0, h, spacing):
        rows = range(y, min(h, y + spacing))
        x = 0
        while x < w:
            if any(px[x, r] == 0 for r in rows):
                start = x
                while x < w and any(px[x, r] == 0 for r in rows):
                    x += 1
                strokes.append((y, start, x - 1))
            else:
                x += 1
    return strokes
