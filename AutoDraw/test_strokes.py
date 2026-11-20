from PIL import Image, ImageDraw

from strokes import make_strokes, to_black_and_white


def picture():
    img = Image.new("RGB", (200, 100), "white")
    d = ImageDraw.Draw(img)
    d.rectangle([20, 20, 79, 59], fill="black")  # a filled square
    d.line([120, 50, 180, 50], fill="black", width=1)  # a thin line
    return img


def test_fill_covers_the_square_and_the_line():
    bw = to_black_and_white(picture(), 200, 128)
    assert bw.size == (200, 100)
    strokes = make_strokes(bw, 1)
    covered = {(x, y) for (y, x0, x1) in strokes for x in range(x0, x1 + 1)}
    black = {(x, y) for y in range(bw.height) for x in range(bw.width) if bw.getpixel((x, y)) == 0}
    assert covered == black
    assert (50, 40) in covered and (150, 50) in covered and (100, 80) not in covered


def test_spacing_keeps_thin_lines():
    bw = to_black_and_white(picture(), 200, 128)
    strokes = make_strokes(bw, 4)
    assert any(x0 <= 150 <= x1 for (y, x0, x1) in strokes if 46 <= y <= 50)


def test_outline_has_fewer_pixels_and_keeps_the_border():
    bw = to_black_and_white(picture(), 200, 128, outline=True)
    assert bw.getpixel((20, 40)) == 0  # edge of the square
    assert bw.getpixel((50, 40)) == 255  # inside is hollow


def test_size_and_transparency():
    img = Image.new("RGBA", (400, 200), (0, 0, 0, 0))  # fully transparent = white paper
    ImageDraw.Draw(img).ellipse([100, 50, 300, 150], fill=(0, 0, 0, 255))
    bw = to_black_and_white(img, 100, 128)
    assert bw.size == (100, 50)
    assert bw.getpixel((2, 2)) == 255 and bw.getpixel((50, 25)) == 0


from strokes import make_paths, rasterize


def test_traced_paths_cover_the_outline_with_few_pen_lifts():
    img = Image.new("RGB", (200, 120), "white")
    d = ImageDraw.Draw(img)
    d.ellipse([20, 20, 120, 100], outline="black", width=1)
    d.line([140, 20, 190, 100], fill="black", width=1)
    bw = to_black_and_white(img, 200, 128)
    paths = make_paths(bw)
    black = {(x, y) for y in range(bw.height) for x in range(bw.width) if bw.getpixel((x, y)) == 0}
    drawn = rasterize(paths, bw.size)
    covered = {(x, y) for y in range(bw.height) for x in range(bw.width) if drawn.getpixel((x, y)) == 0}
    # Every black pixel is drawn or right next to a drawn one (simplification may shift by 1 px).
    near = lambda p: any((p[0] + dx, p[1] + dy) in covered for dx in (-1, 0, 1) for dy in (-1, 0, 1))
    assert all(near(p) for p in black)
    # A circle and a line: a handful of strokes, not one per row (row scanning needs ~160).
    assert len(paths) <= 8, len(paths)


def test_lines_mode_draws_each_thick_line_once():
    img = Image.new("RGB", (200, 100), "white")
    ImageDraw.Draw(img).line([20, 50, 180, 50], fill="black", width=7)  # a thick line
    thinned = to_black_and_white(img, 200, 128, lines=True)
    col = [y for y in range(100) if thinned.getpixel((100, y)) == 0]
    assert len(col) == 1 and 47 <= col[0] <= 53  # one pixel, in the middle
    outline = to_black_and_white(img, 200, 128, outline=True)
    assert len([y for y in range(100) if outline.getpixel((100, y)) == 0]) == 2  # outline: two edges
    assert len(make_paths(thinned)) == 1


from strokes import lines_and_fill_paths


def test_lines_and_fill_keeps_pupils_solid():
    img = Image.new("RGB", (200, 100), "white")
    d = ImageDraw.Draw(img)
    d.ellipse([30, 30, 60, 60], outline="black", width=2)  # an eye outline
    d.ellipse([40, 40, 50, 50], fill="black")  # its pupil (solid)
    d.line([100, 50, 180, 50], fill="black", width=3)  # a line
    bw = to_black_and_white(img, 200, 128)
    drawn = rasterize(lines_and_fill_paths(bw, spacing=1), bw.size)
    # The pupil is filled, not reduced to a dot.
    pupil = [(x, y) for y in range(41, 50) for x in range(41, 50)]
    assert sum(1 for p in pupil if drawn.getpixel(p) == 0) >= len(pupil) * 0.8
    # Lines-only mode loses most of it.
    thin_only = rasterize(make_paths(to_black_and_white(img, 200, 128, lines=True)), bw.size)
    assert sum(1 for p in pupil if thin_only.getpixel(p) == 0) < len(pupil) * 0.3
    # The eye outline and the line are drawn.
    assert drawn.getpixel((30, 45)) == 0 or drawn.getpixel((31, 45)) == 0
    assert drawn.getpixel((140, 50)) == 0
