#!/usr/bin/env python3
"""
foldpen edit overlay: the input rectangle's border, drawn on a layer-shell
surface that exists only while edit mode is on.

stdin  (from the daemon), one JSON object per line:
         {"mon": [x, y, w, h], "rect": [x, y, w, h]}   rect relative to mon
stdout (to the daemon):
         {"rect": [x, y, w, h]}   the user dragged or resized it
         {"done": true}           Esc / right click: leave edit mode

Mouse: drag inside the rectangle moves it, the wheel resizes it around the
pointer. Only the rectangle takes input (its input region), so the rest of
the desktop stays usable while editing.
"""

import json
import sys

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("GtkLayerShell", "0.1")
from gi.repository import Gdk, GLib, Gtk, GtkLayerShell  # noqa: E402
import cairo  # noqa: E402

BORDER = 3
GRAB = 18   # px of input margin around the border


class Overlay(Gtk.Window):
    def __init__(self):
        super().__init__()
        self.mon = None
        self.rect = None
        self.drag = None
        GtkLayerShell.init_for_window(self)
        GtkLayerShell.set_namespace(self, "foldpen")
        GtkLayerShell.set_layer(self, GtkLayerShell.Layer.OVERLAY)
        GtkLayerShell.set_exclusive_zone(self, -1)
        GtkLayerShell.set_keyboard_mode(self, GtkLayerShell.KeyboardMode.ON_DEMAND)
        for edge in (GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.BOTTOM,
                     GtkLayerShell.Edge.LEFT, GtkLayerShell.Edge.RIGHT):
            GtkLayerShell.set_anchor(self, edge, True)
        self.set_app_paintable(True)
        visual = self.get_screen().get_rgba_visual()
        if visual:
            self.set_visual(visual)
        self.add_events(Gdk.EventMask.BUTTON_PRESS_MASK | Gdk.EventMask.BUTTON_RELEASE_MASK
                        | Gdk.EventMask.POINTER_MOTION_MASK | Gdk.EventMask.SCROLL_MASK
                        | Gdk.EventMask.SMOOTH_SCROLL_MASK)
        self.connect("draw", self.on_draw)
        self.connect("button-press-event", self.on_press)
        self.connect("button-release-event", self.on_release)
        self.connect("motion-notify-event", self.on_motion)
        self.connect("scroll-event", self.on_scroll)
        self.connect("key-press-event", self.on_key)
        self.connect("realize", lambda *_: self.update_input())
        GLib.io_add_watch(GLib.IOChannel.unix_new(sys.stdin.fileno()),
                          GLib.PRIORITY_DEFAULT, GLib.IO_IN | GLib.IO_HUP, self.on_stdin)

    # daemon link ---------------------------------------------------------------
    def on_stdin(self, ch, cond):
        line = sys.stdin.readline()
        if not line:
            Gtk.main_quit()
            return False
        m = json.loads(line)
        if self.mon != m["mon"]:
            self.mon = m["mon"]
            self.place()
        if not self.drag:   # our own drag is the authority while it lasts
            self.rect = m["rect"]
        self.update_input()
        self.queue_draw()
        return True

    def place(self):
        disp = Gdk.Display.get_default()
        for i in range(disp.get_n_monitors()):
            g = disp.get_monitor(i).get_geometry()
            if (g.x, g.y) == (int(self.mon[0]), int(self.mon[1])):
                GtkLayerShell.set_monitor(self, disp.get_monitor(i))
                break
        if not self.get_visible():
            self.show_all()

    @staticmethod
    def emit(obj):
        print(json.dumps(obj), flush=True)

    def update_input(self):
        win = self.get_window()
        if not win or not self.rect:
            return
        x, y, w, h = self.rect
        region = cairo.Region(cairo.RectangleInt(int(x - GRAB), int(y - GRAB),
                                                 int(w + 2 * GRAB), int(h + 2 * GRAB)))
        win.input_shape_combine_region(region, 0, 0)

    # drawing ---------------------------------------------------------------------
    def on_draw(self, _w, cr):
        cr.set_operator(cairo.OPERATOR_SOURCE)
        cr.set_source_rgba(0, 0, 0, 0)
        cr.paint()
        if not self.rect:
            return
        cr.set_operator(cairo.OPERATOR_OVER)
        x, y, w, h = self.rect
        cr.set_line_width(BORDER)
        cr.set_source_rgba(0, 0, 0, 0.5)
        cr.rectangle(x - 1, y - 1, w + 2, h + 2)
        cr.stroke()
        cr.set_source_rgba(1.0, 0.62, 0.15, 0.95)
        cr.rectangle(x + BORDER / 2, y + BORDER / 2, w - BORDER, h - BORDER)
        cr.stroke()

    # mouse -----------------------------------------------------------------------
    def inside(self, ex, ey):
        x, y, w, h = self.rect
        return x - GRAB <= ex <= x + w + GRAB and y - GRAB <= ey <= y + h + GRAB

    def on_press(self, _w, ev):
        if not self.rect:
            return
        if ev.button == 3:
            self.emit({"done": True})
            return
        if ev.button == 1 and self.inside(ev.x, ev.y):
            self.drag = (ev.x, ev.y, self.rect[0], self.rect[1])

    def on_release(self, _w, ev):
        if ev.button == 1 and self.drag:
            self.drag = None
            self.emit({"rect": self.rect})

    def on_motion(self, _w, ev):
        if not self.drag:
            return
        sx, sy, rx, ry = self.drag
        self.rect = [rx + ev.x - sx, ry + ev.y - sy, self.rect[2], self.rect[3]]
        self.emit({"rect": self.rect})
        self.update_input()
        self.queue_draw()

    def on_scroll(self, _w, ev):
        if not self.rect:
            return
        if ev.direction == Gdk.ScrollDirection.SMOOTH:
            dy = ev.get_scroll_deltas()[2]
        else:
            dy = {Gdk.ScrollDirection.UP: -1, Gdk.ScrollDirection.DOWN: 1}.get(ev.direction, 0)
        s = 1.08 ** (-dy)          # wheel up grows the rectangle
        x, y, w, h = self.rect
        px, py = ev.x, ev.y
        self.rect = [px - (px - x) * s, py - (py - y) * s, w * s, h * s]
        self.emit({"rect": self.rect})
        self.queue_draw()

    def on_key(self, _w, ev):
        if ev.keyval in (Gdk.KEY_Escape, Gdk.KEY_Return):
            self.emit({"done": True})


def main():
    Overlay()
    Gtk.main()


if __name__ == "__main__":
    main()
