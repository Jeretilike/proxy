"""AutoDraw: draws a black-and-white version of an image for you by moving the mouse.

Drop (or open, or paste) an image, adjust it, press F2 to preview where it will go (a red
copy follows the mouse), then click to draw it there. Alt stops at any time; Esc cancels
the preview. Windows only (it moves the real mouse).
"""

import ctypes
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, ttk

from PIL import Image, ImageGrab, ImageTk

from strokes import lines_and_fill_paths, make_paths, make_strokes, order_and_join, rasterize, strokes_to_paths, to_black_and_white

try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
except ImportError:  # drag and drop is optional
    TkinterDnD = None

from pynput import keyboard, mouse

IS_WINDOWS = sys.platform == "win32"
PREVIEW_KEY = keyboard.Key.f2
STOP_KEYS = {keyboard.Key.alt, keyboard.Key.alt_l, keyboard.Key.alt_r, keyboard.Key.alt_gr}

if IS_WINDOWS:
    # Work in real screen pixels, so the drawing lands exactly where the preview showed it
    # even with display scaling (125%, 150%...).
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass

# --------------------------------------------------------------------------------------------
# The mouse


class Mouse:
    """Moves and clicks the real mouse.

    On Windows it uses SendInput, the same path a real mouse takes, so games (Roblox and the
    like) see the pointer move while the button is held, not just clicks.
    """

    MOVE, LEFTDOWN, LEFTUP, ABSOLUTE, VIRTUALDESK = 0x0001, 0x0002, 0x0004, 0x8000, 0x4000

    def __init__(self):
        self.fallback = mouse.Controller()
        if IS_WINDOWS:
            from ctypes import wintypes

            class MOUSEINPUT(ctypes.Structure):
                _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                            ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

            class KEYBDINPUT(ctypes.Structure):
                _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                            ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

            class _U(ctypes.Union):
                _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

            class INPUT(ctypes.Structure):
                _anonymous_ = ("u",)
                _fields_ = [("type", wintypes.DWORD), ("u", _U)]

            self.INPUT, self.MOUSEINPUT = INPUT, MOUSEINPUT
            self.user32 = ctypes.windll.user32
            self.user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)

    def _send(self, flags, dx=0, dy=0):
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(dx, dy, 0, flags, 0, 0)
        self.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

    def move(self, x, y):
        if IS_WINDOWS:
            # Absolute position over the whole desktop (all monitors), 0..65535.
            vx, vy = self.user32.GetSystemMetrics(76), self.user32.GetSystemMetrics(77)
            vw, vh = self.user32.GetSystemMetrics(78), self.user32.GetSystemMetrics(79)
            nx = int(round((x - vx) * 65535 / max(1, vw - 1)))
            ny = int(round((y - vy) * 65535 / max(1, vh - 1)))
            self._send(self.MOVE | self.ABSOLUTE | self.VIRTUALDESK, nx, ny)
        else:
            self.fallback.position = (int(x), int(y))

    def down(self):
        if IS_WINDOWS:
            self._send(self.LEFTDOWN)
        else:
            self.fallback.press(mouse.Button.left)

    def up(self):
        if IS_WINDOWS:
            self._send(self.LEFTUP)
        else:
            self.fallback.release(mouse.Button.left)

    def position(self):
        return self.fallback.position


# --------------------------------------------------------------------------------------------
# The app


class AutoDraw:
    def __init__(self):
        self.root = TkinterDnD.Tk() if TkinterDnD else tk.Tk()
        self.root.title("AutoDraw")
        self.root.geometry("840x720")
        self.root.minsize(720, 660)
        self.root.configure(bg="#1f1d1a")

        self.source = None  # original image
        self.bw = None  # black-and-white image (mode "1", black = draw)
        self.paths = []  # each a list of (x, y) points drawn without lifting the pen
        self.mouse = Mouse()
        self.stop_flag = threading.Event()
        self.drawing = False
        self.previewing = False
        self.overlay = None

        self.size = tk.IntVar(value=300)
        self.threshold = tk.IntVar(value=128)
        self.detail = tk.IntVar(value=2)
        self.speed = tk.IntVar(value=3)
        self.mode = tk.StringVar(value="Lines + fill")
        self.invert = tk.BooleanVar(value=False)
        self.game_mode = tk.BooleanVar(value=False)
        self.min_piece = tk.IntVar(value=4)
        self.lift_ms = tk.IntVar(value=40)

        self.build_ui()
        self.listen()
        self.root.bind("<Control-v>", lambda e: self.paste())
        self.root.protocol("WM_DELETE_WINDOW", self.quit)

    # ---------------------------------------------------------------------------------- UI

    def build_ui(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        bg, panel, fg, muted, accent = "#1f1d1a", "#2a2723", "#ece7df", "#a69e93", "#81b64c"
        style.configure(".", background=panel, foreground=fg, fieldbackground=bg)
        style.configure("TFrame", background=panel)
        style.configure("TLabel", background=panel, foreground=fg, font=("Segoe UI", 10))
        style.configure("Muted.TLabel", foreground=muted, font=("Segoe UI", 9))
        style.configure("Title.TLabel", font=("Segoe UI", 15, "bold"))
        style.configure("TButton", font=("Segoe UI", 10, "bold"), padding=6)
        style.configure("Accent.TButton", background=accent, foreground="#10210a")
        style.configure("TRadiobutton", background=panel, foreground=fg)
        style.configure("TCheckbutton", background=panel, foreground=fg)
        style.configure("Horizontal.TScale", background=panel)

        left = tk.Frame(self.root, bg=bg)
        left.pack(side="left", fill="both", expand=True, padx=(14, 7), pady=14)
        self.canvas = tk.Canvas(left, bg="#ffffff", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.canvas.bind("<Configure>", lambda e: self.show_preview())
        self.drop_hint = "Drop an image here\n\nor use Open image / Ctrl+V"
        if TkinterDnD:
            self.canvas.drop_target_register(DND_FILES)
            self.canvas.dnd_bind("<<Drop>>", self.on_drop)

        right = ttk.Frame(self.root, padding=14)
        right.pack(side="right", fill="y", padx=(7, 14), pady=14)
        ttk.Label(right, text="AutoDraw", style="Title.TLabel").pack(anchor="w")
        ttk.Label(right, text="Drop a picture, preview, click to draw.", style="Muted.TLabel").pack(anchor="w", pady=(0, 10))

        buttons = ttk.Frame(right)
        buttons.pack(fill="x", pady=(0, 10))
        ttk.Button(buttons, text="Open image", command=self.open_file).pack(side="left", expand=True, fill="x")
        ttk.Button(buttons, text="Paste", command=self.paste).pack(side="left", expand=True, fill="x", padx=(6, 0))

        def slider(label, var, lo, hi, unit=""):
            row = ttk.Frame(right)
            row.pack(fill="x", pady=3)
            top = ttk.Frame(row)
            top.pack(fill="x")
            ttk.Label(top, text=label).pack(side="left")
            value = ttk.Label(top, text=f"{var.get()}{unit}", style="Muted.TLabel")
            value.pack(side="right")

            def changed(_=None):
                var.set(int(float(scale.get())))
                value.configure(text=f"{var.get()}{unit}")
                self.rebuild()

            scale = ttk.Scale(row, from_=lo, to=hi, orient="horizontal", command=changed)
            scale.set(var.get())
            scale.pack(fill="x")

        slider("Size (longest side)", self.size, 50, 900, " px")
        slider("Darkness cut-off", self.threshold, 10, 245)
        slider("Fill row spacing (less = more detail)", self.detail, 1, 8, " px")
        slider("Speed (1 = careful, 10 = fastest)", self.speed, 1, 10)
        slider("Skip specks smaller than", self.min_piece, 0, 30, " px")
        slider("Pause between strokes (more if lines get joined)", self.lift_ms, 10, 200, " ms")

        ttk.Label(right, text="Draw").pack(anchor="w", pady=(6, 0))
        modes = ttk.Frame(right)
        modes.pack(fill="x", pady=(0, 2))
        for i, m in enumerate(("Lines + fill", "Lines", "Outline", "Fill")):
            ttk.Radiobutton(modes, text=m, value=m, variable=self.mode, command=self.rebuild).grid(row=i // 2, column=i % 2, sticky="w", padx=(0, 14))
        ttk.Checkbutton(right, text="Swap black and white", variable=self.invert, command=self.rebuild).pack(anchor="w", pady=2)
        ttk.Checkbutton(right, text="Game mode (Roblox and other games: slower, smoother)", variable=self.game_mode).pack(anchor="w", pady=2)

        keys = ttk.Frame(right)
        keys.pack(fill="x", pady=(14, 4))
        for key, what in (("F2", "preview where it goes"), ("Click", "draw it there (after preview)"), ("Esc", "cancel preview"), ("Alt", "stop drawing")):
            row = ttk.Frame(keys)
            row.pack(fill="x", pady=1)
            ttk.Label(row, text=key, width=6, font=("Segoe UI", 10, "bold")).pack(side="left")
            ttk.Label(row, text=what, style="Muted.TLabel").pack(side="left")

        self.status = ttk.Label(right, text="No image yet.", style="Muted.TLabel", wraplength=230, justify="left")
        self.status.pack(anchor="w", pady=(12, 0))
        self.show_preview()

    def set_status(self, text):
        self.root.after(0, lambda: self.status.configure(text=text))

    # ------------------------------------------------------------------------------ Images

    def load(self, image):
        self.source = image.convert("RGBA")
        self.rebuild()

    def open_file(self):
        path = filedialog.askopenfilename(filetypes=[("Images", "*.png *.jpg *.jpeg *.bmp *.gif *.webp"), ("All files", "*.*")])
        if path:
            self.load_path(path)

    def load_path(self, path):
        try:
            self.load(Image.open(path))
        except Exception as e:
            self.set_status(f"Couldn't open that file: {e}")

    def on_drop(self, event):
        paths = self.root.tk.splitlist(event.data)
        if paths:
            self.load_path(paths[0])

    def paste(self):
        try:
            data = ImageGrab.grabclipboard()
        except Exception:
            data = None
        if isinstance(data, Image.Image):
            self.load(data)
        elif isinstance(data, list) and data and os.path.isfile(data[0]):
            self.load_path(data[0])
        else:
            self.set_status("No image on the clipboard. Copy a picture first.")

    def rebuild(self):
        """Recalculates the drawing shortly after the settings stop changing, in the background."""
        if self.source is None:
            return
        if getattr(self, "_pending", None):
            self.root.after_cancel(self._pending)
        self._pending = self.root.after(250, self._rebuild_now)

    def _rebuild_now(self):
        self._pending = None
        self._generation = getattr(self, "_generation", 0) + 1
        settings = dict(
            source=self.source, size=self.size.get(), threshold=self.threshold.get(), invert=self.invert.get(),
            mode=self.mode.get(), spacing=self.detail.get(), min_piece=self.min_piece.get(),
        )
        self.set_status("Updating…")
        threading.Thread(target=self._work, args=(self._generation, settings), daemon=True).start()

    def _work(self, generation, st):
        mode = st["mode"]
        bw = to_black_and_white(st["source"], st["size"], st["threshold"], st["invert"],
                                outline=mode == "Outline", lines=mode == "Lines")
        if mode == "Lines + fill":
            # Thin lines traced once; solid black areas (hair, pupils) filled with a clean edge.
            paths = lines_and_fill_paths(bw, st["spacing"], st["min_piece"])
        elif mode in ("Lines", "Outline"):
            # Follow each line like a pen (far fewer pen lifts than scanning rows).
            paths = make_paths(bw, min_length=st["min_piece"])
        else:
            # Rows, drawn nearest-first and joined row to row where they touch.
            rows = [r for r in make_strokes(bw, st["spacing"]) if r[2] - r[1] + 1 >= st["min_piece"]]
            paths = order_and_join(strokes_to_paths(rows), 0)  # every piece its own stroke
        drawn = rasterize(paths, bw.size)
        self.root.after(0, lambda: self._apply(generation, bw, paths, drawn))

    def _apply(self, generation, bw, paths, drawn):
        if generation != self._generation:
            return  # settings changed again meanwhile
        self.bw, self.paths, self.drawn = bw, paths, drawn
        w, h = bw.size
        self.set_status(f"{w}×{h} px, {len(paths)} strokes. Press F2 to preview, then click where to draw.")
        self.show_preview()

    def show_preview(self):
        c = self.canvas
        c.delete("all")
        cw, ch = max(c.winfo_width(), 50), max(c.winfo_height(), 50)
        if self.bw is None:
            c.create_text(cw / 2, ch / 2, text=self.drop_hint, fill="#888", font=("Segoe UI", 13), justify="center")
            return
        # Show the strokes as they will be drawn.
        img = self.drawn
        scale = min(cw / img.width, ch / img.height, 3)
        shown = img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))), Image.NEAREST)
        self.tk_preview = ImageTk.PhotoImage(shown)
        c.create_image(cw / 2, ch / 2, image=self.tk_preview)

    # ---------------------------------------------------------------------- Preview overlay

    def start_preview(self):
        if self.drawing or self.bw is None or self.previewing:
            if self.bw is None:
                self.set_status("Load an image first.")
            return
        self.previewing = True
        w, h = self.bw.size
        # Red copy of the drawing on a see-through background.
        red = Image.new("RGB", (w, h), (230, 30, 30))
        rgba = Image.new("RGB", (w, h), (255, 0, 255))
        rgba.paste(red, (0, 0), self.drawn.point(lambda v: 255 if v == 0 else 0))
        top = tk.Toplevel(self.root)
        top.overrideredirect(True)
        top.attributes("-topmost", True)
        try:
            top.attributes("-transparentcolor", "#ff00ff")
            top.attributes("-alpha", 0.75)
        except tk.TclError:
            pass
        self.overlay_img = ImageTk.PhotoImage(rgba)
        label = tk.Label(top, image=self.overlay_img, bg="#ff00ff", bd=0, highlightthickness=1, highlightbackground="#e61e1e")
        label.pack()
        top.update_idletasks()
        self.make_click_through(top)
        self.overlay = top
        self.set_status("Preview: move the mouse to place the drawing (top-left corner at the pointer). Click to draw, Esc to cancel.")
        self.follow_mouse()

    def make_click_through(self, window):
        if not IS_WINDOWS:
            return
        try:
            hwnd = ctypes.windll.user32.GetParent(window.winfo_id()) or window.winfo_id()
            GWL_EXSTYLE, WS_EX_LAYERED, WS_EX_TRANSPARENT, WS_EX_TOOLWINDOW = -20, 0x80000, 0x20, 0x80
            style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            ctypes.windll.user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW)
        except Exception:
            pass

    def follow_mouse(self):
        if not self.previewing or self.overlay is None:
            return
        x, y = self.mouse.position()
        self.overlay.geometry(f"+{int(x)}+{int(y)}")
        self.root.after(15, self.follow_mouse)

    def end_preview(self):
        self.previewing = False
        if self.overlay is not None:
            self.overlay.destroy()
            self.overlay = None

    # ----------------------------------------------------------------------------- Drawing

    def start_drawing(self, x, y):
        self.end_preview()
        if self.drawing or not self.paths:
            return
        self.drawing = True
        self.stop_flag.clear()
        self.root.iconify()  # keep the window out of the way of the drawing
        threading.Thread(target=self.draw, args=(int(x), int(y)), daemon=True).start()

    def draw(self, ox, oy):
        # Programs read the mouse a limited number of times a second (games about 60). The pen
        # must stay up long enough to be noticed before moving to the next stroke, or the
        # program joins the strokes with long stray lines.
        speed = self.speed.get()
        game = self.game_mode.get()
        lift = self.lift_ms.get() / 1000
        settle = [0.012, 0.009, 0.007, 0.005, 0.004, 0.003, 0.002, 0.0015, 0.001, 0.0008][speed - 1]
        step_px = 12
        if game:
            lift = max(lift, 0.08)
            settle = max(settle, 0.017)
            step_px = 6
        total = len(self.paths)
        time.sleep(0.25)  # let the starting click finish
        done = 0
        cx, cy = self.mouse.position()  # where the pointer is now (pen up)
        glide_px = 20 if game else 60
        try:
            for i, path in enumerate(self.paths):
                if self.stop_flag.is_set():
                    break
                x, y = path[0]
                tx, ty = ox + x, oy + y
                # Glide there with the pen up instead of jumping: some programs (Roblox) keep the
                # old pointer position for a moment and would join the strokes with a stray line.
                dist = max(abs(tx - cx), abs(ty - cy))
                n = max(1, int(dist // glide_px))
                for k in range(1, n + 1):
                    self.mouse.move(cx + (tx - cx) * k / n, cy + (ty - cy) * k / n)
                    if k < n:
                        time.sleep(settle)
                # Arrive with the pen up, a little longer after a long jump.
                time.sleep(lift + min(0.2, dist / 2500))
                self.mouse.down()
                time.sleep(lift if game else settle * 2)
                if len(path) == 1:
                    self.mouse.move(ox + x + 1, oy + y)  # a dot: nudge so it registers
                    time.sleep(settle)
                for (x1, y1), (x2, y2) in zip(path, path[1:]):
                    if self.stop_flag.is_set():
                        break
                    # Long segments in steps, so programs that sample the mouse follow them.
                    n = max(1, int(max(abs(x2 - x1), abs(y2 - y1)) // step_px))
                    for s in range(1, n + 1):
                        self.mouse.move(ox + x1 + (x2 - x1) * s / n, oy + y1 + (y2 - y1) * s / n)
                        time.sleep(settle)
                time.sleep(settle * 2)  # let the end of the stroke register before lifting
                self.mouse.up()
                cx, cy = ox + path[-1][0], oy + path[-1][1]
                time.sleep(lift)  # pen up long enough to be noticed
                done = i + 1
                if i % 20 == 0:
                    self.set_status(f"Drawing… {done}/{total} strokes. Press Alt to stop.")
        finally:
            self.mouse.up()
            self.drawing = False
            stopped = self.stop_flag.is_set()
            self.set_status(f"Stopped after {done}/{total} strokes." if stopped else f"Done: {total} strokes drawn.")
            self.root.after(0, self.root.deiconify)

    # --------------------------------------------------------------------- Global hotkeys

    def listen(self):
        def on_key(key):
            if key in STOP_KEYS:
                if self.drawing:
                    self.stop_flag.set()
            elif key == PREVIEW_KEY:
                self.root.after(0, self.start_preview)
            elif key == keyboard.Key.esc and self.previewing:
                self.root.after(0, self.end_preview)
                self.set_status("Preview cancelled.")

        def on_click(x, y, button, pressed):
            if self.previewing and button == mouse.Button.left and not pressed:
                self.root.after(0, lambda: self.start_drawing(x, y))

        self.key_listener = keyboard.Listener(on_press=on_key)
        self.key_listener.daemon = True
        self.key_listener.start()
        self.mouse_listener = mouse.Listener(on_click=on_click)
        self.mouse_listener.daemon = True
        self.mouse_listener.start()

    def quit(self):
        self.stop_flag.set()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    AutoDraw().run()
