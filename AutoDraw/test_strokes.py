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
