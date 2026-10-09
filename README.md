# foldpen

A Galaxy Z Fold 6 (or any Android 11+ phone) as a **stylus tablet** for Hyprland.

- The S Pen arrives as a real **pen tablet** (`foldpen-stylus`: pressure, tilt, side button, eraser, hover). It is not a mouse.
- Pen input is sent the moment Android delivers it (unbuffered dispatch, every historical sample, TCP_NODELAY, its own send thread). The screen mirror can never hold it up.
- The phone addresses an **input rectangle** on the PC screen, with the phone's aspect ratio. You can move and zoom it.
- A **mirror** of that rectangle can be shown on the phone at a low, adaptive frame rate. It runs fast while the picture changes or the pen moves, drops to about 1 fps when nothing changes, and skips frames instead of queueing them.
- **Finger modes:** in `pen` mode every finger is the pen (for a dummy capacitive stylus) and palm rejection is on. In `touch` mode fingers are real touchscreen contacts (`foldpen-touch`), and the S Pen stays a pen.
- You never need to look at the phone: everything is a gesture, and each one is confirmed by its own vibration pattern.

## Gestures (phone)

| three fingers | does | vibration |
|---|---|---|
| tap | mirror on / off | 1 short tick / 2 ticks |
| hold still ½ s | edit rectangle on / off | 1 long / long + tick |
| swipe left or right | finger mode pen / touch | 1 firm pulse / 2 pulses |

**Two fingers tapped together** (finger = pen mode) press the pen's side button: a tip tap with `BTN_STYLUS` held, which apps take as a right click. You'll feel a short tick. The S Pen uses its physical button.

**In edit mode** the rectangle behaves like an object under your fingers: drag one finger to move it and pinch with two to resize it. Its border is drawn on the PC only while edit mode is on. On the PC you can also drag it with the mouse, resize it with the wheel, and close it with Esc or a right click.

PC keys (from the NixOS feature): `SUPER+ALT+R` edit, `SUPER+ALT+M` mirror, `SUPER+ALT+F` finger mode.
CLI: `foldpen toggle edit|display|finger`, `foldpen set …`, `foldpen status`.

## Palm rejection (finger = pen)

A dummy pen usually lands **after** the palm. The first contact down is therefore no evidence of anything. Every contact's stroke is buffered from touchdown and is only sent once it qualifies. A stroke that never qualifies is dropped and never reaches the PC.

- **Last-writer-wins.** The newest qualifying contact takes the pen. The older one, the palm, is set aside for good.
- **It must write.** A stroke qualifies by moving more than 1.2 mm.
- **Too idle → dropped.** A contact that sits still for 400 ms before writing is a resting hand.
- **Too short → dropped.** A contact lifted within 50 ms is a blip. One lifted later without moving is a tap.
- **Corrupted → dropped.** So is a contact that jumps more than 12 mm between samples, or whose size doubles or halves (a palm merging or splitting). If that happens after the stroke went live, the stroke ends there.
- **Size.** A contact is a palm if it is larger than 12 mm, or larger than twice the running average of contacts that became the pen, capped at 22 mm. A contact that grows into a palm mid-stroke ends the stroke. While a size-detected palm is down, and for 350 ms after it lifts, new contacts next to it or below it are hand.
- **Edges.** A contact starting within 2.5 mm of the screen edge (a gripping thumb) is ignored.
- **The S Pen wins.** While the S Pen is near the screen, and for 400 ms after it leaves, fingers are ignored. Android's own palm cancellation (`FLAG_CANCELED`) is honoured.

The same hold-back is what separates the 3-finger gestures from input. Touches that turn out to start a gesture are **cancelled** on the PC (`MT_TOOL_PALM`, so libinput sends touch-cancel), so they never become a stray tap.

## Setup

NixOS: import `nixosModules.default` and set `services.foldpen = { enable = true; users = [ "you" ]; };`. Log out and back in once so the `uinput` group applies.

Then, with the phone on adb (USB, or wireless debugging):

```sh
foldpen install    # sign (key in ~/.local/state/foldpen), adb install, hand over the token
foldpen usb        # later: re-tunnel the port over USB (adb reverse) and launch
```

Over Wi-Fi the phone finds the PC through a UDP beacon (port 7393). If it has never been paired, run `foldpen pair` and type the 6-digit code on the phone. That is the only time the phone needs to be looked at. Every connection must present the token.

## Layout

- `android/`: the app, plain Java on framework APIs, built without Gradle (`nix/apk.nix`)
- `daemon/foldpen.py`: daemon and CLI (uinput, rectangle mapping, frames, control socket)
- `daemon/overlay.py`: the edit-mode border (GTK3 layer-shell)
- `nix/`: package, APK and NixOS module

Wire format: `[u8 type][u32 BE length][payload]`. The phone sends `HELLO`, `PEN`, `TOUCH`, `CMD` and `VIEW`; the PC sends `STATE` (JSON) and `FRAME` (JPEG).
