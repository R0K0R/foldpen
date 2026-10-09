#!/usr/bin/env python3
"""
foldpen -- an Android phone (Galaxy Z Fold 6) as a pen tablet for Hyprland.

The phone app (../android) streams pen and touch samples over TCP; this daemon
replays them through two uinput devices:

  foldpen-stylus   a pen tablet (BTN_TOOL_PEN / RUBBER, pressure, tilt). The
                   S Pen always lands here, and so does a finger while the
                   phone is in "finger = pen" mode.
  foldpen-touch    a multitouch touchscreen. Fingers land here in "finger =
                   touch" mode, so Hyprland's own touch handling (and the Lua
                   gestures in features/hyprland/touch.lua) sees real touches.

Both devices are bound to one monitor (hl.device{ output = ... }), and every
sample is mapped into the INPUT RECTANGLE: a region of that monitor with the
phone screen's aspect ratio, so the small phone can address part of a big
screen. The rectangle is moved/zoomed from the phone (edit mode gestures) or
with the mouse on a layer-shell overlay that only exists while editing.

Optionally the rectangle's contents are sent back to the phone as JPEG frames
(display forwarding). Frames are paced dynamically -- fast while the picture
changes or the pen is moving, decaying to ~1 fps when nothing changes -- and
are dropped rather than queued when the link is busy, so they can never delay
input: input travels phone->PC, frames PC->phone, on separate TCP directions.

Wire format, both directions: [u8 type][u32 length, big endian][payload].
"""

import argparse
import asyncio
import hashlib
import json
import os
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

PORT = 7392
BEACON_PORT = 7393

# phone -> PC
T_HELLO, T_PEN, T_TOUCH, T_CMD, T_VIEW = 1, 2, 3, 4, 5
# PC -> phone
T_STATE, T_FRAME = 10, 11

PEN_IN_RANGE, PEN_TOUCH, PEN_BTN1, PEN_ERASER, PEN_BTN2 = 1, 2, 4, 8, 16
TOUCH_DOWN, TOUCH_PALM = 1, 2

ABS_MAX = 32767
PRESSURE_MAX = 4095
# libinput decides tip contact from ABS_PRESSURE, not BTN_TOUCH, with a
# threshold of a few percent of the range. A light S Pen stroke reports far
# less than that, so a touching sample is lifted onto [TIP_FLOOR, 1].
TIP_FLOOR = 0.06

STYLUS_NAME = "foldpen-stylus"
TOUCH_NAME = "foldpen-touch"

STATE_DIR = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state") / "foldpen"
RUNTIME_DIR = Path(os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}")
CTL_SOCK = RUNTIME_DIR / "foldpen.sock"
OVERLAY = Path(__file__).with_name("overlay.py")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- hyprland

def hyprctl(*args):
    try:
        return subprocess.run(["hyprctl", *args], capture_output=True, text=True, timeout=3).stdout
    except Exception as e:  # compositor restarting, etc.
        log("hyprctl failed:", e)
        return ""


def monitors():
    """Logical layout boxes of the enabled monitors: {name: (x, y, w, h)}."""
    try:
        data = json.loads(hyprctl("-j", "monitors") or "[]")
    except json.JSONDecodeError:
        return {}
    out = {}
    for m in data:
        if m.get("disabled"):
            continue
        w, h = m["width"] / m["scale"], m["height"] / m["scale"]
        if m.get("transform", 0) % 2:
            w, h = h, w
        out[m["name"]] = (float(m["x"]), float(m["y"]), w, h)
    return out


def bind_devices(output):
    # transform = 0 overrides the global input.tablet/touchdevice transform
    # features/hyprland sets on a rotated panel: these coordinates are already
    # in the monitor's (rotated) logical space.
    lua = " ".join(
        f'hl.device({{ name = "{n}", output = "{output}", transform = 0 }})'
        for n in (STYLUS_NAME, TOUCH_NAME))
    hyprctl("eval", lua)


# --------------------------------------------------------------------------- uinput

class Devices:
    def __init__(self):
        from evdev import UInput, AbsInfo, ecodes as e
        self.e = e
        ax = lambda mx, res=0, mn=0: AbsInfo(0, mn, mx, 0, 0, res)
        self.pen = UInput({
            e.EV_KEY: [e.BTN_TOOL_PEN, e.BTN_TOOL_RUBBER, e.BTN_TOUCH, e.BTN_STYLUS, e.BTN_STYLUS2],
            e.EV_ABS: [(e.ABS_X, ax(ABS_MAX, 100)), (e.ABS_Y, ax(ABS_MAX, 100)),
                       (e.ABS_PRESSURE, ax(PRESSURE_MAX)),
                       (e.ABS_TILT_X, ax(90, 57, -90)), (e.ABS_TILT_Y, ax(90, 57, -90))],
        }, name=STYLUS_NAME, vendor=0x1d50, product=0xf01d, version=1,
            input_props=[e.INPUT_PROP_DIRECT])
        self.touch = UInput({
            e.EV_KEY: [e.BTN_TOUCH],
            e.EV_ABS: [(e.ABS_X, ax(ABS_MAX, 100)), (e.ABS_Y, ax(ABS_MAX, 100)),
                       (e.ABS_MT_SLOT, ax(9)), (e.ABS_MT_TRACKING_ID, ax(65535)),
                       (e.ABS_MT_POSITION_X, ax(ABS_MAX, 100)), (e.ABS_MT_POSITION_Y, ax(ABS_MAX, 100)),
                       (e.ABS_MT_TOOL_TYPE, ax(2))],
        }, name=TOUCH_NAME, vendor=0x1d50, product=0xf01e, version=1,
            input_props=[e.INPUT_PROP_DIRECT])
        self.pen_state = dict(range=False, tool=None, touch=False, b1=False, b2=False)
        self.slots = {}       # slot -> tracking id
        self.next_tid = 1

    # pen ------------------------------------------------------------------
    def pen_event(self, flags, x, y, p, tx, ty):
        e, s, u = self.e, self.pen_state, self.pen
        in_range = bool(flags & PEN_IN_RANGE)
        touch = in_range and bool(flags & PEN_TOUCH)
        tool = e.BTN_TOOL_RUBBER if flags & PEN_ERASER else e.BTN_TOOL_PEN
        if not in_range:
            if s["range"]:
                self._pen_release()
            return
        if s["range"] and s["tool"] != tool:
            self._pen_release()
        if not s["range"]:
            # Proximity-in in a frame of its own, with zero pressure: libinput
            # takes the pressure seen at proximity-in as the pen's resting
            # offset, so arriving already pressed would eat the stroke.
            u.write(e.EV_ABS, e.ABS_X, x)
            u.write(e.EV_ABS, e.ABS_Y, y)
            u.write(e.EV_ABS, e.ABS_PRESSURE, 0)
            u.write(e.EV_KEY, tool, 1)
            u.syn()
            s.update(range=True, tool=tool, touch=False)
        u.write(e.EV_ABS, e.ABS_X, x)
        u.write(e.EV_ABS, e.ABS_Y, y)
        u.write(e.EV_ABS, e.ABS_TILT_X, tx)
        u.write(e.EV_ABS, e.ABS_TILT_Y, ty)
        u.write(e.EV_ABS, e.ABS_PRESSURE,
                int((TIP_FLOOR + (1 - TIP_FLOOR) * min(max(p, 0.0), 1.0)) * PRESSURE_MAX) if touch else 0)
        if touch != s["touch"]:
            u.write(e.EV_KEY, e.BTN_TOUCH, int(touch))
            s["touch"] = touch
        for key, bit, name in ((e.BTN_STYLUS, PEN_BTN1, "b1"), (e.BTN_STYLUS2, PEN_BTN2, "b2")):
            v = bool(flags & bit)
            if v != s[name]:
                u.write(e.EV_KEY, key, int(v))
                s[name] = v
        u.syn()

    def _pen_release(self):
        e, s, u = self.e, self.pen_state, self.pen
        u.write(e.EV_ABS, e.ABS_PRESSURE, 0)
        if s["touch"]:
            u.write(e.EV_KEY, e.BTN_TOUCH, 0)
        if s["b1"]:
            u.write(e.EV_KEY, e.BTN_STYLUS, 0)
        if s["b2"]:
            u.write(e.EV_KEY, e.BTN_STYLUS2, 0)
        u.syn()
        u.write(e.EV_KEY, s["tool"], 0)
        u.syn()
        s.update(range=False, tool=None, touch=False, b1=False, b2=False)

    # touch ----------------------------------------------------------------
    def touch_frame(self, contacts):
        """contacts: {slot: (flags, x, y)} -- every contact still down; slots
        missing from it are lifted, TOUCH_PALM ones are cancelled."""
        e, u = self.e, self.touch
        changed = False
        for slot in list(self.slots):
            c = contacts.get(slot)
            if c is None or c[0] & TOUCH_PALM:
                u.write(e.EV_ABS, e.ABS_MT_SLOT, slot)
                if c is not None:
                    # MT_TOOL_PALM makes libinput send touch CANCEL, not up, so
                    # a touch that turned out to start a phone gesture never
                    # becomes a tap on the desktop.
                    u.write(e.EV_ABS, e.ABS_MT_TOOL_TYPE, e.MT_TOOL_PALM)
                    u.syn()
                    u.write(e.EV_ABS, e.ABS_MT_SLOT, slot)
                u.write(e.EV_ABS, e.ABS_MT_TRACKING_ID, -1)
                del self.slots[slot]
                changed = True
        first = None
        for slot, (flags, x, y) in sorted(contacts.items()):
            if flags & TOUCH_PALM or not 0 <= slot <= 9:
                continue
            u.write(e.EV_ABS, e.ABS_MT_SLOT, slot)
            if slot not in self.slots:
                self.slots[slot] = self.next_tid
                self.next_tid = self.next_tid % 65000 + 1
                u.write(e.EV_ABS, e.ABS_MT_TRACKING_ID, self.slots[slot])
                u.write(e.EV_ABS, e.ABS_MT_TOOL_TYPE, e.MT_TOOL_FINGER)
            u.write(e.EV_ABS, e.ABS_MT_POSITION_X, x)
            u.write(e.EV_ABS, e.ABS_MT_POSITION_Y, y)
            first = first or (x, y)
            changed = True
        if first:
            u.write(e.EV_ABS, e.ABS_X, first[0])
            u.write(e.EV_ABS, e.ABS_Y, first[1])
        if changed:
            u.write(e.EV_KEY, e.BTN_TOUCH, int(bool(self.slots)))
            u.syn()

    def release_all(self):
        if self.pen_state["range"]:
            self._pen_release()
        self.touch_frame({})


# --------------------------------------------------------------------------- state

class State:
    """Persistent: the rectangle (global logical coords), finger mode, token."""

    def __init__(self):
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        self.path = STATE_DIR / "state.json"
        try:
            d = json.loads(self.path.read_text())
        except Exception:
            d = {}
        self.rect = d.get("rect")              # [x, y, w, h]
        self.finger = d.get("finger", "pen")   # "pen" | "touch"
        self.token = d.get("token") or secrets.token_hex(16)
        self.display = False
        self.edit = False
        self.save()

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"rect": self.rect, "finger": self.finger, "token": self.token}))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)


# --------------------------------------------------------------------------- daemon

class Daemon:
    def __init__(self, args):
        self.args = args
        self.dev = Devices()
        self.st = State()
        self.view = (2160, 1856)    # phone view px (Fold 6 inner screen) until told
        self.mons = {}
        self.mon = None             # name the devices are bound to
        self.client = None          # (reader, writer)
        self.overlay = None
        self.pair_code = None       # (code, expiry)
        self.last_input = 0.0
        self.frame_wake = asyncio.Event()
        self.refresh_monitors()

    # geometry ----------------------------------------------------------------
    def refresh_monitors(self):
        self.mons = monitors() or self.mons
        self.fit_rect()

    def aspect(self):
        return self.view[0] / self.view[1]

    def fit_rect(self, rect=None):
        """Clamp rect onto the monitor holding its centre, at the phone's aspect."""
        if not self.mons:
            return
        r = rect or self.st.rect
        if r is None:
            mx, my, mw, mh = next(iter(self.mons.values()))
            h = mh * 0.6
            r = [mx + (mw - h * self.aspect()) / 2, my + mh * 0.2, h * self.aspect(), h]
        cx, cy = r[0] + r[2] / 2, r[1] + r[3] / 2
        name = min(self.mons, key=lambda n: self._dist(self.mons[n], cx, cy))
        mx, my, mw, mh = self.mons[name]
        # keep the area across aspect changes (fold/unfold, rotation)
        area = max(r[2] * r[3], 40 * 40)
        w = (area * self.aspect()) ** 0.5
        h = w / self.aspect()
        k = min(1.0, mw / w, mh / h)
        w, h = w * k, h * k
        x = min(max(cx - w / 2, mx), mx + mw - w)
        y = min(max(cy - h / 2, my), my + mh - h)
        self.st.rect = [x, y, w, h]
        if name != self.mon:
            self.mon = name
            bind_devices(name)

    @staticmethod
    def _dist(m, x, y):
        mx, my, mw, mh = m
        dx = max(mx - x, 0, x - (mx + mw))
        dy = max(my - y, 0, y - (my + mh))
        return dx * dx + dy * dy

    def to_abs(self, u, v):
        """Phone-view fraction -> device units on the bound monitor."""
        x, y, w, h = self.st.rect
        mx, my, mw, mh = self.mons[self.mon]
        ax = (x - mx + min(max(u, 0.0), 1.0) * w) / mw
        ay = (y - my + min(max(v, 0.0), 1.0) * h) / mh
        return int(ax * ABS_MAX + 0.5), int(ay * ABS_MAX + 0.5)

    def rect_op(self, dx=0.0, dy=0.0, scale=1.0, cx=0.5, cy=0.5):
        """Edit-mode manipulation of the rectangle as an object: it follows the
        fingers (dx/dy are fractions of the phone width) and grows when they
        spread. A full-width swipe moves it 3/4 of the monitor width."""
        x, y, w, h = self.st.rect
        mw = self.mons[self.mon][2]
        g = mw * self.args.edit_gain
        x += dx * g
        y += dy * g
        px, py = x + cx * w, y + cy * h
        s = min(max(scale, 0.5), 2.0)
        w, h = w * s, h * s
        self.fit_rect([px - cx * w, py - cy * h, w, h])
        self.changed(save=True)

    # state broadcast ------------------------------------------------------------
    def state_msg(self, **extra):
        return {"display": self.st.display, "finger": self.st.finger, "edit": self.st.edit, **extra}

    def changed(self, save=False):
        if save:
            self.st.save()
        self.send(T_STATE, json.dumps(self.state_msg()).encode())
        self.overlay_push()
        self.frame_wake.set()

    def send(self, t, payload):
        if not self.client:
            return
        w = self.client[1]
        if w.is_closing():
            return
        w.write(struct.pack(">BI", t, len(payload)) + payload)

    def toggle(self, what):
        if what == "display":
            self.st.display = not self.st.display
        elif what == "finger":
            self.st.finger = "touch" if self.st.finger == "pen" else "pen"
        elif what == "edit":
            self.set_edit(not self.st.edit)
            return
        self.changed(save=True)

    def set_edit(self, on):
        self.st.edit = on
        if on:
            self.refresh_monitors()
            self.start_overlay()
        else:
            self.stop_overlay()
        self.changed(save=True)

    # overlay ------------------------------------------------------------------
    def start_overlay(self):
        if self.overlay:
            return
        asyncio.get_running_loop().create_task(self._run_overlay())

    async def _run_overlay(self):
        try:
            p = await asyncio.create_subprocess_exec(
                sys.executable, str(OVERLAY), stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE)
        except Exception as e:
            log("overlay failed to start:", e)
            return
        self.overlay = p
        self.overlay_push()
        done = False
        while True:
            line = await p.stdout.readline()
            if not line:
                break
            try:
                m = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "rect" in m and self.mon:
                mx, my = self.mons[self.mon][:2]
                rx, ry, rw, rh = m["rect"]
                self.fit_rect([rx + mx, ry + my, rw, rh])
                self.changed(save=True)
            if m.get("done"):
                done = True
                break
        rc = await p.wait()
        if self.overlay is p:
            self.overlay = None
            # Only the user's "done" (Esc / right click) leaves edit mode. An
            # overlay that crashed just means no border on the PC; editing
            # from the phone still works, and a toggle there ends it.
            if done and self.st.edit:
                self.st.edit = False
                self.changed(save=True)
            elif not done:
                log(f"edit overlay exited ({rc}); edit mode stays on")

    def overlay_push(self):
        p = self.overlay
        if not p or p.stdin.is_closing() or not self.mon:
            return
        mx, my, mw, mh = self.mons[self.mon]
        x, y, w, h = self.st.rect
        p.stdin.write((json.dumps({"mon": [mx, my, mw, mh], "rect": [x - mx, y - my, w, h]}) + "\n").encode())

    def stop_overlay(self):
        p, self.overlay = self.overlay, None
        if p and p.returncode is None:
            p.terminate()

    # phone connection ------------------------------------------------------------
    async def on_client(self, reader, writer):
        peer = writer.get_extra_info("peername")
        sock = writer.get_extra_info("socket")
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        try:
            t, payload = await asyncio.wait_for(read_msg(reader), 5)
            if t != T_HELLO:
                raise ConnectionError("expected hello")
            hello = json.loads(payload)
            reply = self.authorize(hello)
            if reply is None:
                writer.write(pack(T_STATE, {"error": "pair"}))
                await writer.drain()
                log(f"refused unpaired phone {peer}")
                return
            if self.client:
                log("replacing previous phone")
                self.client[1].close()
            self.client = (reader, writer)
            log(f"phone connected: {peer} {hello.get('model', '')}")
            self.set_view(hello)
            writer.write(pack(T_STATE, self.state_msg(**reply)))
            frames = asyncio.get_running_loop().create_task(self.frame_loop(writer))
            try:
                while True:
                    t, payload = await read_msg(reader)
                    self.handle(t, payload)
            finally:
                frames.cancel()
        except (asyncio.IncompleteReadError, ConnectionError, OSError, asyncio.TimeoutError) as e:
            log(f"phone {peer} gone: {e!r}")
        finally:
            if self.client and self.client[1] is writer:
                self.client = None
                self.dev.release_all()
            writer.close()

    def authorize(self, hello):
        if secrets.compare_digest(str(hello.get("token", "")), self.st.token):
            return {}
        code = str(hello.get("code", ""))
        if self.pair_code and time.monotonic() < self.pair_code[1] and secrets.compare_digest(code, self.pair_code[0]):
            self.pair_code = None
            return {"token": self.st.token}
        return None

    def set_view(self, m):
        w, h = int(m.get("w", 0)), int(m.get("h", 0))
        if w > 0 and h > 0 and (w, h) != self.view:
            self.view = (w, h)
            self.fit_rect()
            self.changed(save=True)

    def handle(self, t, p):
        if t == T_PEN:
            flags, u, v, pr, tx, ty = struct.unpack(">Bfffff", p)
            if self.mon:
                x, y = self.to_abs(u, v)
                self.dev.pen_event(flags, x, y, pr, int(tx), int(ty))
            self.last_input = time.monotonic()
        elif t == T_TOUCH:
            n = p[0]
            contacts = {}
            for i in range(n):
                slot, flags, u, v = struct.unpack_from(">BBff", p, 1 + i * 10)
                if self.mon:
                    contacts[slot] = (flags,) + self.to_abs(u, v)
            self.dev.touch_frame(contacts)
            self.last_input = time.monotonic()
        elif t == T_CMD:
            m = json.loads(p)
            cmd = m.get("cmd")
            if cmd == "toggle":
                self.toggle(m.get("what"))
            elif cmd == "rect" and self.st.rect and self.mon:
                self.rect_op(m.get("dx", 0.0), m.get("dy", 0.0), m.get("scale", 1.0),
                             m.get("cx", 0.5), m.get("cy", 0.5))
        elif t == T_VIEW:
            self.set_view(json.loads(p))

    # display forwarding ----------------------------------------------------------
    async def frame_loop(self, writer):
        """Latest-only frames at an adaptive rate. Never queues: if the previous
        frame is still in the socket buffer, this one is skipped."""
        a = self.args
        last_hash, interval = None, a.min_interval
        while True:
            if not self.st.display or not self.mon:
                last_hash = None
                self.frame_wake.clear()
                await self.frame_wake.wait()
                continue
            t0 = time.monotonic()
            if writer.transport.get_write_buffer_size() == 0:
                img = await self.capture()
                if img:
                    h = hashlib.blake2b(img, digest_size=16).digest()
                    if h != last_hash:
                        last_hash = h
                        self.send(T_FRAME, img)
                        interval = a.min_interval
                    else:
                        interval = min(interval * 1.6, a.max_interval)
            if time.monotonic() - self.last_input < 0.5:
                interval = a.min_interval
            self.frame_wake.clear()
            try:
                await asyncio.wait_for(self.frame_wake.wait(), max(0.0, interval - (time.monotonic() - t0)))
            except asyncio.TimeoutError:
                pass

    async def capture(self):
        x, y, w, h = self.st.rect
        target = min(max(self.view), self.args.frame_px) / max(w, h)
        try:
            p = await asyncio.create_subprocess_exec(
                "grim", "-g", f"{int(x)},{int(y)} {int(w)}x{int(h)}", "-s", f"{target:.3f}",
                "-t", "jpeg", "-q", str(self.args.jpeg_quality), "-",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            out, _ = await p.communicate()
            return out if p.returncode == 0 else None
        except Exception as e:
            log("grim failed:", e)
            return None

    # LAN discovery ----------------------------------------------------------------
    async def beacon(self):
        """UDP broadcast so the phone finds this PC without configuration. The
        token still gates every connection."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.setblocking(False)
        msg = f"FOLDPEN1 {self.args.port}".encode()
        targets, refreshed = [], 0.0
        while True:
            if not self.client:
                if time.monotonic() - refreshed > 15:
                    targets, refreshed = broadcast_addrs(), time.monotonic()
                for b in targets:
                    try:
                        s.sendto(msg, (b, BEACON_PORT))
                    except OSError:
                        pass
            await asyncio.sleep(1.5)

    # control socket ----------------------------------------------------------------
    async def on_ctl(self, reader, writer):
        try:
            words = (await reader.readline()).decode().split()
            reply = self.ctl(words)
            writer.write((json.dumps(reply) + "\n").encode())
            await writer.drain()
        finally:
            writer.close()

    def ctl(self, w):
        if w[:1] == ["toggle"] and len(w) == 2 and w[1] in ("display", "finger", "edit"):
            self.toggle(w[1])
        elif w[:1] == ["set"] and len(w) == 3:
            if w[1] == "edit":
                self.set_edit(w[2] == "on")
            elif w[1] == "display":
                self.st.display = w[2] == "on"
                self.changed()
            elif w[1] == "finger" and w[2] in ("pen", "touch"):
                self.st.finger = w[2]
                self.changed(save=True)
        elif w[:1] == ["pair"]:
            code = f"{secrets.randbelow(10**6):06d}"
            self.pair_code = (code, time.monotonic() + 120)
            return {"code": code}
        elif w[:1] == ["token"]:
            return {"token": self.st.token, "port": self.args.port}
        elif w[:1] == ["monitors"]:
            self.refresh_monitors()
            self.changed(save=True)
        elif w[:1] != ["status"]:
            return {"error": "usage: toggle|set|pair|status|monitors"}
        return {**self.state_msg(), "connected": self.client is not None,
                "monitor": self.mon, "rect": self.st.rect}

    async def watch_monitors(self):
        # Cheap poll; monitor hotplug/rotation is rare and this keeps the
        # daemon free of a socket2 event parser.
        while True:
            await asyncio.sleep(3)
            m = monitors()
            if m and m != self.mons:
                self.mon = None   # force a re-bind (outputs may have moved)
                self.refresh_monitors()
                self.changed(save=True)

    async def run(self):
        servers = [await asyncio.start_server(self.on_client, self.args.bind, self.args.port)]
        if CTL_SOCK.exists():
            CTL_SOCK.unlink()
        servers.append(await asyncio.start_unix_server(self.on_ctl, str(CTL_SOCK)))
        os.chmod(CTL_SOCK, 0o600)
        log(f"listening on {self.args.bind}:{self.args.port}; monitor {self.mon}")
        tasks = [asyncio.create_task(self.watch_monitors())]
        if not self.args.no_beacon:
            tasks.append(asyncio.create_task(self.beacon()))
        stop = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, stop.set)
        await stop.wait()
        self.stop_overlay()
        self.dev.release_all()


def broadcast_addrs():
    try:
        data = json.loads(subprocess.run(["ip", "-j", "-4", "addr"], capture_output=True, text=True).stdout)
    except Exception:
        return ["255.255.255.255"]
    out = []
    for iface in data:
        if "UP" not in iface.get("flags", []) or iface.get("ifname") == "lo":
            continue
        for a in iface.get("addr_info", []):
            if a.get("broadcast"):
                out.append(a["broadcast"])
    return out or ["255.255.255.255"]


def pack(t, obj):
    payload = json.dumps(obj).encode()
    return struct.pack(">BI", t, len(payload)) + payload


async def read_msg(reader):
    head = await reader.readexactly(5)
    t, n = struct.unpack(">BI", head)
    if n > 1 << 20:
        raise ConnectionError("oversized message")
    return t, await reader.readexactly(n)


# --------------------------------------------------------------------------- CLI

def ctl(*words):
    s = socket.socket(socket.AF_UNIX)
    try:
        s.connect(str(CTL_SOCK))
    except OSError:
        sys.exit("foldpen: daemon not running (systemctl --user status foldpen)")
    s.sendall((" ".join(words) + "\n").encode())
    data = b""
    while chunk := s.recv(4096):
        data += chunk
    return json.loads(data)


APP = "dev.foldpen"


def adb(*a, check=True):
    return subprocess.run(["adb", *a], check=check, capture_output=True, text=True)


def push_to_phone(port):
    """USB/adb setup: tunnel the port and hand the phone its token, so pairing
    needs no typing."""
    tok = ctl("token")
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    adb("shell", "am", "start", "-n", f"{APP}/.MainActivity", "--es", "token", tok["token"],
        "--es", "host", "127.0.0.1", "--ei", "port", str(port))


def install(apk_dir, port):
    key = STATE_DIR / "signing.p12"
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if not key.exists():
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
                        "-storepass", "foldpen", "-keypass", "foldpen", "-alias", "foldpen",
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500",
                        "-dname", "CN=foldpen"], check=True, capture_output=True)
        os.chmod(key, 0o600)
    out = STATE_DIR / "foldpen.apk"
    subprocess.run(["java", "-jar", f"{apk_dir}/apksigner.jar", "sign", "--ks", str(key),
                    "--ks-pass", "pass:foldpen", "--out", str(out), f"{apk_dir}/foldpen-unsigned.apk"],
                   check=True)
    r = adb("install", "-r", str(out), check=False)
    print(r.stdout.strip() or r.stderr.strip())
    if r.returncode:
        sys.exit(1)
    push_to_phone(port)


def main():
    ap = argparse.ArgumentParser(prog="foldpen")
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("daemon", help="run the daemon (the systemd user service does this)")
    d.add_argument("--port", type=int, default=PORT)
    d.add_argument("--bind", default="0.0.0.0")
    d.add_argument("--no-beacon", action="store_true")
    d.add_argument("--min-interval", type=float, default=0.1, help="fastest frame interval, s")
    d.add_argument("--max-interval", type=float, default=1.0, help="slowest frame interval when idle, s")
    d.add_argument("--frame-px", type=int, default=1280, help="longest frame side, px")
    d.add_argument("--jpeg-quality", type=int, default=65)
    d.add_argument("--edit-gain", type=float, default=0.75)
    t = sub.add_parser("toggle", help="toggle display | finger | edit")
    t.add_argument("what", choices=["display", "finger", "edit"])
    s = sub.add_parser("set", help="set display on|off, edit on|off, finger pen|touch")
    s.add_argument("what", choices=["display", "finger", "edit"])
    s.add_argument("value")
    sub.add_parser("status")
    sub.add_parser("pair", help="show a 6-digit code to type on the phone (valid 2 min)")
    u = sub.add_parser("usb", help="adb reverse the port and give the phone its token")
    u.add_argument("--port", type=int, default=PORT)
    i = sub.add_parser("install", help="sign and adb-install the phone app, then `usb`")
    i.add_argument("--port", type=int, default=PORT)
    a = ap.parse_args()

    if a.cmd == "daemon":
        asyncio.run(Daemon(a).run())
    elif a.cmd in ("toggle", "status"):
        print(json.dumps(ctl(a.cmd, *([a.what] if a.cmd == "toggle" else []))))
    elif a.cmd == "set":
        print(json.dumps(ctl("set", a.what, a.value)))
    elif a.cmd == "pair":
        code = ctl("pair")["code"]
        print(f"foldpen pairing code: {code}  (valid 2 minutes)")
        if shutil.which("notify-send"):
            subprocess.run(["notify-send", "-a", "foldpen", "foldpen pairing code", code], check=False)
    elif a.cmd == "usb":
        push_to_phone(a.port)
    elif a.cmd == "install":
        apk = os.environ.get("FOLDPEN_APK")
        if not apk:
            sys.exit("FOLDPEN_APK is not set (the Nix wrapper sets it)")
        install(apk, a.port)


if __name__ == "__main__":
    main()
