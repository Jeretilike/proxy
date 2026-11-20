# AutoDraw

Draws a picture for you by moving the mouse (Windows).

1. Drop an image on the window (or **Open image** / **Ctrl+V**). Adjust size, darkness cut-off,
   speed and the mode; the preview shows the strokes it will draw.
   - **Lines** (best for drawings and line art): every black line is thinned to its centre and
     traced once, like tracing paper.
   - **Outline**: the edges of dark areas.
   - **Fill**: dark areas filled with rows.
2. Open the program or website you want to draw in.
3. Press **F2**: a red copy of the drawing follows the mouse (its top-left corner at the pointer).
4. **Click** to draw it there. The AutoDraw window minimises while it draws.
5. **Alt** stops at any time. **Esc** cancels the preview.

Built by `.github/workflows/autodraw.yml`; tests: `python -m pytest AutoDraw/test_strokes.py`.
