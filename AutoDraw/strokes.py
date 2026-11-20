"""Turning a picture into mouse strokes (no GUI here, so it can be tested anywhere)."""

from PIL import Image, ImageFilter, ImageOps


def to_black_and_white(image, size, threshold, invert=False, outline=False, lines=False, smooth=False):
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
    if smooth:
        # Removes grain and specks before cutting to black and white.
        gray = gray.filter(ImageFilter.MedianFilter(3))
    bw = gray.point(lambda v: 0 if v < threshold else 255)
    if lines:
        # Each black line thinned to its centre, so it is drawn once (like tracing paper).
        return thin(bw)
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


# ---------------------------------------------------------------------------------------------
# Tracing lines (for outlines / line art): follow each line like a pen instead of scanning rows


def make_paths(bw, tolerance=0.5, join_gap=0, min_length=0):
    """Polylines that follow the black lines of `bw` (8-connected), each drawn without lifting.

    Returns a list of paths, each a list of (x, y) points; consecutive points are joined by
    straight segments (simplified so a segment strays at most `tolerance` px from the pixels).
    """
    w, h = bw.size
    px = bw.load()
    black = bytearray(w * h)
    for y in range(h):
        for x in range(w):
            if px[x, y] == 0:
                black[y * w + x] = 1
    visited = bytearray(w * h)
    # Prefer straight-ahead neighbours first so lines are followed smoothly.
    dirs = [(1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, 1), (-1, -1), (1, -1)]

    def neighbours(x, y):
        for dx, dy in dirs:
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if black[i] and not visited[i]:
                    yield nx, ny

    def degree(x, y):
        return sum(1 for _ in neighbours(x, y))

    paths = []
    # Start from line ends (one black neighbour) first, so each line is drawn end to end
    # instead of from somewhere in its middle; then whatever is left (closed loops).
    def black_neighbours(x, y):
        n = 0
        for dx, dy in dirs:
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and black[ny * w + nx]:
                n += 1
        return n

    ends = [(x, y) for y in range(h) for x in range(w) if black[y * w + x] and black_neighbours(x, y) <= 1]
    others = [(x, y) for y in range(h) for x in range(w) if black[y * w + x]]
    for sx, sy in ends + others:
        i = sy * w + sx
        if visited[i]:
            continue
        if True:
            # Start from a line end if this pixel's line has one nearby, so we draw end to end.
            x, y = sx, sy
            visited[i] = 1
            path = [(x, y)]
            last_dir = None
            while True:
                options = list(neighbours(x, y))
                if not options:
                    break
                if last_dir is not None:
                    # Keep going the same way when possible; otherwise the gentlest turn.
                    options.sort(key=lambda p: abs(p[0] - x - last_dir[0]) + abs(p[1] - y - last_dir[1]))
                else:
                    # First step: towards the neighbour with the fewest onward options (a line end).
                    options.sort(key=lambda p: degree(*p))
                nx, ny = options[0]
                last_dir = (nx - x, ny - y)
                x, y = nx, ny
                visited[y * w + x] = 1
                path.append((x, y))
            paths.append(path)
    if min_length > 0:
        # Specks and tiny bits (noise, texture) cost a pen lift each and add little.
        paths = [p for p in paths if len(p) >= min_length]
    paths = order_and_join(paths, join_gap)
    return [simplify(p, tolerance) for p in paths]


def order_and_join(paths, join_gap):
    """Draws paths nearest-first (less travel) and joins a path onto the previous one when it
    starts within `join_gap` px of where the previous one ended, so the pen needn't lift."""
    if not paths:
        return paths
    remaining = paths[:]
    # Bucket start and end points on a grid for quick nearest lookups.
    cell = 16
    grid = {}
    for idx, p in enumerate(remaining):
        for end in (p[0], p[-1]):
            grid.setdefault((end[0] // cell, end[1] // cell), set()).add(idx)
    used = [False] * len(remaining)

    def take(idx):
        used[idx] = True
        for end in (remaining[idx][0], remaining[idx][-1]):
            grid.get((end[0] // cell, end[1] // cell), set()).discard(idx)

    def nearest(x, y):
        best, best_d, flip = None, None, False
        r = 0
        while r < 64:
            for gx in range(x // cell - r, x // cell + r + 1):
                for gy in range(y // cell - r, y // cell + r + 1):
                    if max(abs(gx - x // cell), abs(gy - y // cell)) != r:
                        continue
                    for idx in grid.get((gx, gy), ()):
                        p = remaining[idx]
                        for end, rev in ((p[0], False), (p[-1], True)):
                            d = max(abs(end[0] - x), abs(end[1] - y))
                            if best_d is None or d < best_d:
                                best, best_d, flip = idx, d, rev
            if best is not None and best_d <= r * cell:
                break
            r += 1
        return best, best_d, flip

    out = []
    current = remaining[0]
    take(0)
    count = 1
    while count < len(remaining):
        x, y = current[-1]
        idx, d, flip = nearest(x, y)
        if idx is None:
            idx = used.index(False)
            d, flip = None, False
        nxt = remaining[idx][::-1] if flip else remaining[idx]
        take(idx)
        count += 1
        if join_gap > 0 and d is not None and d <= join_gap:
            current = current + nxt  # keep drawing: the gap is tiny
        else:
            out.append(current)
            current = nxt
    out.append(current)
    return out


def simplify(points, tolerance):
    """Ramer–Douglas–Peucker: drop points that lie within `tolerance` px of the line between neighbours."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (x1, y1), (x2, y2) = points[a], points[b]
        dx, dy = x2 - x1, y2 - y1
        length = (dx * dx + dy * dy) ** 0.5 or 1.0
        far, far_d = None, tolerance
        for k in range(a + 1, b):
            px_, py_ = points[k]
            d = abs(dy * (px_ - x1) - dx * (py_ - y1)) / length
            if d > far_d:
                far, far_d = k, d
        if far is not None:
            keep[far] = True
            stack.append((a, far))
            stack.append((far, b))
    return [p for p, k in zip(points, keep) if k]


def strokes_to_paths(strokes):
    """Row strokes (y, x0, x1) as two-point paths."""
    return [[(x0, y), (x1, y)] for (y, x0, x1) in strokes]


def rasterize(paths, size):
    """Which pixels the pen covers when drawing `paths` (for the preview)."""
    w, h = size
    img = Image.new("L", size, 255)
    px = img.load()
    for path in paths:
        if len(path) == 1:
            x, y = path[0]
            px[x, y] = 0
        for (x1, y1), (x2, y2) in zip(path, path[1:]):
            n = max(abs(x2 - x1), abs(y2 - y1), 1)
            for s in range(n + 1):
                x = round(x1 + (x2 - x1) * s / n)
                y = round(y1 + (y2 - y1) * s / n)
                if 0 <= x < w and 0 <= y < h:
                    px[x, y] = 0
    return img


def thin(bw):
    """Zhang-Suen thinning: shrinks every black shape to a 1-pixel-wide centre line.

    Only pixels on the current border are examined each pass (the neighbours of pixels just
    removed join the border), which keeps it quick on big pictures.
    """
    w, h = bw.size
    px = bw.load()
    black = bytearray((w + 2) * (h + 2))  # one pixel of white padding all round
    W = w + 2
    for y in range(h):
        for x in range(w):
            if px[x, y] == 0:
                black[(y + 1) * W + x + 1] = 1
    offsets = (-W, -W + 1, 1, W + 1, W, W - 1, -1, -W - 1)  # p2..p9 clockwise from north

    def on_border(i):
        return black[i] and (not black[i - W] or not black[i + 1] or not black[i + W] or not black[i - 1])

    border = {i for i in range(len(black)) if black[i] and on_border(i)}
    step = 0
    quiet = 0
    while quiet < 2:
        remove = []
        for i in border:
            if not black[i]:
                continue
            p = [black[i + o] for o in offsets]
            n = sum(p)
            if n < 2 or n > 6:
                continue
            if sum(1 for k in range(8) if p[k] == 0 and p[(k + 1) % 8] == 1) != 1:
                continue
            p2, p4, p6, p8 = p[0], p[2], p[4], p[6]
            if step == 0 and p2 * p4 * p6 == 0 and p4 * p6 * p8 == 0:
                remove.append(i)
            elif step == 1 and p2 * p4 * p8 == 0 and p2 * p6 * p8 == 0:
                remove.append(i)
        for i in remove:
            black[i] = 0
        if remove:
            quiet = 0
            nxt = set()
            for i in remove:
                for o in offsets:
                    if black[i + o]:
                        nxt.add(i + o)
            border = (border - set(remove)) | nxt
        else:
            quiet += 1
        step = 1 - step
    out = Image.new("L", (w, h), 255)
    o = out.load()
    for i in range(len(black)):
        if black[i]:
            y, x = divmod(i, W)
            o[x - 1, y - 1] = 0
    return out


# ---------------------------------------------------------------------------------------------
# Lines + fill: thin lines traced once, solid black areas (pupils, dark hair) filled in


def split_thick(bw, radius=2):
    """Splits black into solid areas (at least 2*radius+1 px across) and thin lines.

    Returns (lines, solid) as black-on-white images.
    """
    size = 2 * radius + 1
    core = bw.filter(ImageFilter.MaxFilter(size))  # only the middles of wide black areas stay black
    solid = core.filter(ImageFilter.MinFilter(size))  # grown back to their full shape
    # Lines: black that isn't part of a solid area.
    b, s = bw.load(), solid.load()
    lines = Image.new("L", bw.size, 255)
    l = lines.load()
    for y in range(bw.height):
        for x in range(bw.width):
            if b[x, y] == 0 and s[x, y] != 0:
                l[x, y] = 0
    return lines, solid


def outline_of(bw):
    """The edge pixels of the black areas."""
    eroded = bw.filter(ImageFilter.MaxFilter(3))
    edges = Image.new("L", bw.size, 255)
    b, e, out = bw.load(), eroded.load(), edges.load()
    for y in range(bw.height):
        for x in range(bw.width):
            if b[x, y] == 0 and e[x, y] == 255:
                out[x, y] = 0
    return edges


def lines_and_fill_paths(bw, spacing=2, min_length=0):
    """Thin lines traced once; solid areas filled with rows and edged with their outline."""
    lines, solid = split_thick(bw)
    paths = make_paths(thin(lines), min_length=min_length)
    paths += make_paths(outline_of(solid), min_length=min_length)
    rows = [r for r in make_strokes(solid, spacing) if r[2] - r[1] + 1 >= max(1, min_length)]
    paths += order_and_join(strokes_to_paths(rows), 0)  # every piece its own stroke
    return paths
