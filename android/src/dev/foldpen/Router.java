package dev.foldpen;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Turns MotionEvents into pen / touch messages, and recognises the control
 * gestures. Everything is decided per pointer by tool type:
 *
 *  S Pen (TOOL_TYPE_STYLUS / ERASER): sent at once, every historical sample,
 *  never delayed. While it is near the screen, fingers are ignored.
 *
 *  Fingers: held back first (buffered, not dropped), long enough to see
 *  whether they are a palm or the start of a 3-finger gesture. Then:
 *    finger mode "pen"   -> one contact is the pen (a dummy stylus): the
 *                           newest one whose stroke qualifies (it writes, it
 *                           is not a blip, not idle, not corrupted) -- last-
 *                           writer-wins, because the pen tip lands after the
 *                           palm. Strokes that don't qualify are dropped.
 *    finger mode "touch" -> every finger is a touchscreen contact.
 *
 *  Gestures, always three fingers, so they work without looking:
 *    tap               toggle display forwarding
 *    hold (still)      toggle edit mode (move / resize the input rectangle)
 *    swipe sideways    toggle finger mode (pen <-> touch)
 *  In edit mode one finger drags the rectangle and two pinch-resize it.
 */
final class Router {
    interface Host {
        void toggle(String what);
        void ink(float x, float y, boolean down);
        void hover(float x, float y, boolean inRange);
        void tick();
    }

    // timing (ms) and distances (mm)
    static final long TOUCH_HOLD_MS = 55, GESTURE_SPREAD_MS = 220;
    // finger = pen, after Chromium's touch palm filter (ui/events/ozone/evdev/
    // touch_filter, NeuralStylusPalmDetectionFilter: IsHeuristicPalmStroke and
    // its hold-then-decide flow). Every new contact is held (buffered) for
    // DECIDE_SAMPLES samples or DECIDE_MS, then decided ONCE from the maxima
    // over that window: palm if its major axis or its area (major x minor)
    // reaches the limits below. A stroke lifted before that is decided at
    // lift, or dropped if shorter than BLIP_MS. Not-palm strokes take the pen
    // last-writer-wins. Chromium's other rule -- any touch within 100 mm of a
    // big blob is palm -- is left out: here the pen IS a touch next to the palm.
    // Also dropped: corrupted strokes -- a sample jumping more than JUMP_MM at
    // over JUMP_SPEED (mm/ms = m/s, what a palm contact snapping does), or a
    // size change of SIZE_JUMP x AND SIZE_JUMP_MM. Corruption after a stroke
    // went live ends it there.
    static final int DECIDE_SAMPLES = 6;
    static final long DECIDE_MS = 60, BLIP_MS = 50;
    static final float WRITE_MM = 1.2f, JUMP_MM = 8, JUMP_SPEED = 3, SIZE_JUMP = 2.0f, SIZE_JUMP_MM = 5;
    static final long GESTURE_TAP_MS = 400, GESTURE_HOLD_MS = 450, GESTURE_GIVEUP_MS = 1500;
    static final long STYLUS_GRACE_MS = 400, PALM_MEMORY_MS = 350;
    static final float SLOP_MM = 6, SWIPE_MM = 18, EDGE_MM = 2.5f;
    // Contact size (getTouchMajor / px-per-mm). On the Fold these are not
    // real mm, but they separate (measured, 14.7 px/mm): dummy pen 0.5-2.5 px,
    // fingertip 3.9-5.9 px, palm edge landing at 8+ px and growing to 12-60.
    // 0.45 "mm" = 6.6 px sits between fingertips and palm edges.
    // Area limit likewise: pen <= 5 px^2, fingertip 8-20, palm edge 35+.
    static final float PALM_MM = 0.45f, PALM_AREA_MM2 = 0.116f, PALM_EMA_X = 3, PALM_MAX_MM = 22, PALM_NEAR_MM = 15;
    static final float FINGER_PEN_PRESSURE = 0.5f;

    static final int PEN_IN_RANGE = 1, PEN_TOUCH = 2, PEN_BTN1 = 4, PEN_ERASER = 8, PEN_BTN2 = 16;
    static final int TOUCH_DOWN = 1, TOUCH_PALM = 2;

    enum S { PENDING, ACTIVE, REJECTED, GESTURE, EDIT, TWO }

    static final class Contact {
        final int id;
        final long downT;
        final float x0, y0;
        float x, y, major, gx0, gy0, lastMajor;
        float minor, size, pressure, path, area;   // maxima / total over the stroke
        int n;                                     // samples seen
        float mEarly, aEarly;                      // maxima within DECIDE_MS: what decide() sees
        float major0;                              // at landing
        long lastT;
        S state = S.PENDING;
        boolean palm, cancel;
        int slot = -1;
        final ArrayList<float[]> buf = new ArrayList<>();
        Contact(int id, long t, float x, float y, float major) {
            this.id = id; downT = t; x0 = this.x = x; y0 = this.y = y; this.major = lastMajor = major; lastT = t;
        }
    }

    private final Link link;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float w = 1, h = 1, mm = 10;

    String finger = "pen";
    boolean edit;

    // stylus
    private boolean penInRange, penTouching;
    private long penLastSeen = -10_000;
    private final Runnable proxOut = this::proximityOut;

    // fingers
    private final SparseArray<Contact> contacts = new SparseArray<>();
    private Contact penFinger;
    private final ArrayList<float[]> palms = new ArrayList<>();   // x, y, radius, endTime(0 = down)
    private float majorEma;
    private final boolean[] slots = new boolean[10];
    private final ArrayList<Contact> cancelled = new ArrayList<>();
    private boolean touchDirty;

    // gesture
    // finger = pen: two fingers tapped together = the pen's side button
    // Fingertips land at 3.4-5.9 px, the dummy pen at 0.5-2.5 (measured): a
    // pen landing with a palm edge used to make a "two-finger tap" -- the
    // side button, Rnote's eraser -- out of a pen touch.
    static final long TWO_SPREAD_MS = 150, TWO_TAP_MS = 350;
    static final float TWO_MIN_PX = 3.0f;
    private Contact twoA, twoB;

    private boolean gesture, gestureFired;
    private long gestureT0;
    private float gestureMax;
    private final Runnable gestureHold = this::gestureHeld;

    private void gestureHeld() {
        if (gesture && !gestureFired && gestureMax < SLOP_MM * mm) fire("edit");
    }

    // edit
    private int editKey;
    private float editCx, editCy, editSpread;
    private float accDx, accDy, accScale = 1, accCx = .5f, accCy = .5f;
    private long lastEditSend;

    private final Runnable tick = this::activateReady;

    Router(Link link, Host host) { this.link = link; this.host = host; }

    void setSize(float w, float h, float dpi) {
        this.w = Math.max(w, 1); this.h = Math.max(h, 1); this.mm = dpi / 25.4f;
    }

    /** State from the daemon. Mode changes drop whatever is in flight. */
    void setModes(String finger, boolean edit) {
        boolean changed = !finger.equals(this.finger) || edit != this.edit;
        this.finger = finger;
        this.edit = edit;
        if (!changed) return;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state == S.ACTIVE || c.state == S.PENDING) cancelContact(c);
            if (c.state != S.GESTURE) c.state = S.REJECTED;
        }
        if (edit && penTouching) liftPen();
        flushTouch();
    }

    // ================================================================ events

    void onTouch(MotionEvent e) {
        int am = e.getActionMasked(), ai = e.getActionIndex();
        boolean canceled = am == MotionEvent.ACTION_CANCEL
                || (Build.VERSION.SDK_INT >= 33 && (e.getFlags() & MotionEvent.FLAG_CANCELED) != 0);
        long now = e.getEventTime();

        // moves first (historical samples included), then the pointer that changed
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean isAction = i == ai && am != MotionEvent.ACTION_MOVE;
            if (isStylus(e, i)) {
                if (edit) continue;
                if (!isAction || am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN) {
                    stylusSamples(e, i, true);
                }
            } else if (!isAction) {
                Contact c = contacts.get(e.getPointerId(i));
                if (c != null) fingerMove(e, i, c);
            }
        }
        if (am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN) {
            if (!isStylus(e, ai)) fingerDown(e, ai, now);
        } else if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_POINTER_UP) {
            if (isStylus(e, ai)) { if (!edit) stylusUp(e, ai); }
            else fingerUp(e.getPointerId(ai), canceled);
        } else if (am == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (isStylus(e, i)) { if (!edit) liftPen(); }
                else fingerUp(e.getPointerId(i), true);
            }
        }
        if (edit) editUpdate(e, am, ai);
        flushTouch();
    }

    void onHover(MotionEvent e) {
        if (edit || !isStylus(e, 0)) return;
        int am = e.getActionMasked();
        if (am == MotionEvent.ACTION_HOVER_EXIT) {
            // EXIT also precedes every touch-down; only leave proximity if no
            // DOWN follows shortly.
            handler.removeCallbacks(proxOut);
            handler.postDelayed(proxOut, 150);
            return;
        }
        stylusSamples(e, 0, false);
    }

    // ================================================================ stylus

    private static boolean isStylus(MotionEvent e, int i) {
        int t = e.getToolType(i);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER;
    }

    private void stylusSamples(MotionEvent e, int i, boolean touching) {
        handler.removeCallbacks(proxOut);
        penLastSeen = SystemClock.uptimeMillis();
        if (penFinger != null) { endPenFinger(true); }
        if (touching) rejectFingersForStylus();
        penInRange = true;
        penTouching = touching;
        int flags = PEN_IN_RANGE | (touching ? PEN_TOUCH : 0) | stylusButtons(e, i);
        for (int k = 0; k < e.getHistorySize(); k++) {
            float x = e.getHistoricalX(i, k), y = e.getHistoricalY(i, k);
            sendPen(flags, x / w, y / h, e.getHistoricalPressure(i, k),
                    e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, k),
                    e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, k), e.getHistoricalEventTime(k));
            if (touching) host.ink(x, y, true);
        }
        float x = e.getX(i), y = e.getY(i);
        sendPen(flags, x / w, y / h, e.getPressure(i),
                e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i), e.getEventTime());
        if (touching) host.ink(x, y, true);
        else host.hover(x, y, true);
    }

    private void stylusUp(MotionEvent e, int i) {
        penTouching = false;
        int flags = PEN_IN_RANGE | stylusButtons(e, i);
        sendPen(flags, e.getX(i) / w, e.getY(i) / h, 0, 0, 0, e.getEventTime());
        host.ink(e.getX(i), e.getY(i), false);
        penLastSeen = SystemClock.uptimeMillis();
        // stays in range while it hovers; leaves if no hover event follows
        handler.removeCallbacks(proxOut);
        handler.postDelayed(proxOut, 400);
    }

    private void proximityOut() {
        if (!penInRange) return;
        penInRange = false;
        sendPen(0, 0, 0, 0, 0, 0);
        host.hover(0, 0, false);
    }

    private void liftPen() {
        if (!penInRange) return;
        penTouching = false;
        handler.removeCallbacks(proxOut);
        proxOut.run();
    }

    private static int stylusButtons(MotionEvent e, int i) {
        int b = e.getButtonState(), f = 0;
        if ((b & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0) f |= PEN_BTN1;
        if ((b & (MotionEvent.BUTTON_STYLUS_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0) f |= PEN_BTN2;
        if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) f |= PEN_ERASER;
        return f;
    }

    private boolean stylusRecently() {
        return penInRange || SystemClock.uptimeMillis() - penLastSeen < STYLUS_GRACE_MS;
    }

    private void rejectFingersForStylus() {
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state == S.ACTIVE || c.state == S.PENDING) { cancelContact(c); c.state = S.REJECTED; }
        }
    }

    private void sendPen(int flags, float x, float y, float p, float tilt, float orient) {
        sendPen(flags, x, y, p, tilt, orient, SystemClock.uptimeMillis());
    }

    /** `when` is the sample's own time (MotionEvent clock = uptimeMillis): the
     *  daemon replays samples on this timeline, so Wi-Fi bursts come out
     *  evenly spaced instead of merged into one point. */
    private void sendPen(int flags, float x, float y, float p, float tilt, float orient, long when) {
        double t = Math.toDegrees(tilt);
        ByteBuffer b = ByteBuffer.allocate(25);
        b.put((byte) flags).putFloat(x).putFloat(y).putFloat(p)
                .putFloat((float) (t * Math.sin(orient))).putFloat((float) (-t * Math.cos(orient)))
                .putInt((int) when);
        link.send(Link.T_PEN, b.array());
    }

    // ================================================================ fingers

    /** `adb logcat -s foldpen-palm` -- every contact decision, for tuning. */
    private void dbg(Contact c, String what) {
        String line = String.format(java.util.Locale.ROOT,
                "#%d %-14s t=%4dms at=(%.0f,%.0f)mm moved=%.1fmm path=%.1fmm major=%.2fpx minor=%.2fpx size=%.4f pres=%.3f pen=%s",
                c.id, what, SystemClock.uptimeMillis() - c.downT, c.x / mm, c.y / mm,
                Math.hypot(c.x - c.x0, c.y - c.y0) / mm, c.path / mm, c.major, c.minor, c.size, c.pressure,
                penFinger == null ? "-" : "#" + penFinger.id);
        Log.d("foldpen-palm", line);
        // also to the daemon's journal (journalctl --user -u foldpen), so
        // tuning does not need adb
        try { link.sendJson(Link.T_CMD, new JSONObject().put("cmd", "log").put("l", line)); }
        catch (Exception ignored) { }
    }

    private void fingerDown(MotionEvent e, int i, long t) {
        float x = e.getX(i), y = e.getY(i);
        Contact c = new Contact(e.getPointerId(i), t, x, y, e.getTouchMajor(i));
        c.minor = e.getTouchMinor(i); c.size = e.getSize(i); c.pressure = e.getPressure(i);
        c.area = c.major * Math.max(c.minor, 0); c.n = 1;
        c.mEarly = c.major0 = c.major; c.aEarly = c.area;
        contacts.put(c.id, c);
        if (gesture) {
            // only the three fingers that started it belong to a gesture;
            // anything landing during one is ignored until it ends
            c.state = S.REJECTED;
            dbg(c, "rej:during-gesture");
            return;
        }
        dbg(c, "down");
        if (stylusRecently()) { c.state = S.REJECTED; dbg(c, "rej:stylus"); return; }
        if (edit) c.state = S.EDIT;
        else if (finger.equals("pen")) {
            if (atEdge(c)) { c.state = S.REJECTED; dbg(c, "rej:edge"); }
            // only a clearly palm-sized contact is rejected on landing: a
            // fingertip (3.4-5.9 px) is past the calibrated palm limit but may
            // be part of a gesture; decide() judges it after the hold window
            else if (c.major >= 2 * palmThreshold()) { markPalm(c); dbg(c, "rej:palm-size"); }
        }
        c.buf.add(new float[]{x, y, 0});
        if (maybeStartGesture(t)) return;
        if (c.state == S.PENDING && finger.equals("pen") && penFinger == null) maybeTwoTap(c, t);
        if (c.state == S.PENDING) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, holdMs());
        }
    }

    private void fingerMove(MotionEvent e, int i, Contact c) {
        for (int k = 0; k <= e.getHistorySize(); k++) {
            boolean cur = k == e.getHistorySize();
            float x = cur ? e.getX(i) : e.getHistoricalX(i, k);
            float y = cur ? e.getY(i) : e.getHistoricalY(i, k);
            float maj = cur ? e.getTouchMajor(i) : e.getHistoricalTouchMajor(i, k);
            long st = cur ? e.getEventTime() : e.getHistoricalEventTime(k);
            float jump = (float) Math.hypot(x - c.x, y - c.y) / mm;
            boolean corrupt = finger.equals("pen") && (
                    (jump > JUMP_MM && jump / Math.max(st - c.lastT, 1) > JUMP_SPEED)
                    || (c.lastMajor > 0 && maj > 0 && Math.abs(maj - c.lastMajor) > SIZE_JUMP_MM * mm
                        && (maj > c.lastMajor * SIZE_JUMP || maj * SIZE_JUMP < c.lastMajor)));
            c.lastMajor = maj;
            c.lastT = st;
            c.path += jump * mm;
            c.minor = Math.max(c.minor, cur ? e.getTouchMinor(i) : e.getHistoricalTouchMinor(i, k));
            c.size = Math.max(c.size, cur ? e.getSize(i) : e.getHistoricalSize(i, k));
            c.pressure = Math.max(c.pressure, cur ? e.getPressure(i) : e.getHistoricalPressure(i, k));
            c.area = Math.max(c.area, maj * (cur ? e.getTouchMinor(i) : e.getHistoricalTouchMinor(i, k)));
            c.n++;
            if (st - c.downT <= DECIDE_MS) { c.mEarly = Math.max(c.mEarly, maj); c.aEarly = c.area; }
            c.x = x; c.y = y;
            c.major = Math.max(c.major, maj);
            if (corrupt && (c.state == S.PENDING || c == penFinger)) {
                dbg(c, c == penFinger ? "end:corrupt" : "rej:corrupt");
                if (c == penFinger) endPenFinger(false);
                c.state = S.REJECTED;
                c.buf.clear();
                continue;
            }
            switch (c.state) {
                case PENDING:
                    c.buf.add(new float[]{x, y, st - c.downT});
                    if (finger.equals("pen") && c.n >= DECIDE_SAMPLES) decide(c);
                    break;
                case ACTIVE:
                    if (c == penFinger) {
                        if (grewIntoPalm(c)) {  // a palm rolled onto the "pen"
                            endPenFinger(false);
                            markPalm(c);
                            dbg(c, "end:palm-grew");
                        } else {
                            sendPen(PEN_IN_RANGE | PEN_TOUCH, x / w, y / h, FINGER_PEN_PRESSURE, 0, 0, st);
                            host.ink(x, y, true);
                        }
                    } else touchDirty = true;
                    break;
                case TWO:
                    if (palmLike(c)) {          // not a two-finger tap: a hand
                        for (Contact o : new Contact[]{twoA, twoB})
                            if (o != null && o != c && o.state == S.TWO) o.state = S.PENDING;
                        twoA = twoB = null;
                        markPalm(c);
                        dbg(c, "two:palm");
                        break;
                    }
                    c.buf.add(new float[]{x, y, st - c.downT});
                    if (Math.hypot(x - c.x0, y - c.y0) > WRITE_MM * mm) {
                        // it is writing: no button tap after all, an ordinary
                        // stroke (its buffer intact), and LWW decides as usual
                        dbg(c, "two:writes");
                        for (Contact o : new Contact[]{twoA, twoB})
                            if (o != null && o.state == S.TWO) o.state = S.PENDING;
                        twoA = twoB = null;
                        decide(c);
                    }
                    break;
                case GESTURE:
                    // Sliding fingertips grow to ~7.4 px, past the pen-mode
                    // palm limit; only a clearly palm-sized one cancels.
                    if (c.major >= 2 * palmThreshold()) {
                        // a "finger" of the gesture grew into a palm: it was
                        // the hand resting, not a gesture -- swallow it
                        gestureFired = true;
                        dbg(c, "gesture:palm");
                    }
                    gestureMove();
                    break;
                default:
                    if (c.palm) updatePalm(c);
            }
        }
    }

    private void fingerUp(int id, boolean canceled) {
        Contact c = contacts.get(id);
        if (c == null) return;
        dbg(c, "up:" + c.state + (canceled ? ":canceled" : ""));
        // size summary for `foldpen calibrate` (the daemon keeps it only while
        // a calibration label is set)
        try {
            link.sendJson(Link.T_CMD, new JSONObject().put("cmd", "contact")
                    .put("m0", c.major0).put("me", c.mEarly).put("mx", c.major)
                    .put("ae", c.aEarly).put("ax", c.area)
                    .put("dur", SystemClock.uptimeMillis() - c.downT).put("path", c.path / mm));
        } catch (Exception ignored) { }
        if (c.state == S.PENDING && !canceled) {
            // A quick tap, shorter than the hold window: decide now, and let
            // the daemon see it down before it goes up. In pen mode only while
            // nothing is writing -- a tap during a stroke is the hand bouncing.
            if (!finger.equals("pen")) activate(c);
            else {
                if (SystemClock.uptimeMillis() - c.downT < BLIP_MS) dbg(c, "rej:blip");
                else decide(c);
            }
            flushTouch();
        }
        contacts.remove(id);
        if (c.state == S.ACTIVE) {
            if (c == penFinger) endPenFinger(false);
            else {
                if (canceled) { c.cancel = true; cancelled.add(c); }
                else freeSlot(c);
                touchDirty = true;
            }
        }
        if (c.palm) endPalm(c);
        if (c.state == S.GESTURE) gestureUp();
        if (c == twoA || c == twoB) twoUp(c);
    }

    private long holdMs() { return finger.equals("pen") ? DECIDE_MS : TOUCH_HOLD_MS; }

    /** Pending contacts that outlast the hold window: touches in touch mode;
     *  decided in pen mode (a still contact delivers no samples to count). */
    private void activateReady() {
        long now = SystemClock.uptimeMillis();
        long next = Long.MAX_VALUE;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state != S.PENDING) continue;
            long due = c.downT + holdMs();
            if (due > now) { next = Math.min(next, due); continue; }
            if (finger.equals("pen")) decide(c);
            else activate(c);
        }
        if (next != Long.MAX_VALUE) handler.postDelayed(tick, Math.max(1, next - now));
        flushTouch();
    }

    /** The one decision per stroke (pen mode), from its window's maxima. */
    private void decide(Contact c) {
        if (c.state != S.PENDING) return;
        if (palmLike(c)) { markPalm(c); dbg(c, "rej:palm"); return; }
        if (nearPalm(c)) { c.state = S.REJECTED; c.buf.clear(); dbg(c, "rej:near-palm"); return; }
        claimPen(c);
    }

    /** Pen mode arbitration, for a stroke that has qualified: last-writer-
     *  wins. The newer contact takes the pen and the older one -- the palm
     *  the pen usually lands after -- is set aside for good. */
    private void claimPen(Contact c) {
        Contact cur = penFinger;
        if (cur != null) {
            if (c.downT < cur.downT) {
                dbg(c, "rej:older");
                c.state = S.REJECTED;
                c.buf.clear();
                return;
            }
            dbg(cur, "lost:newer");
            endPenFinger(false);
        }
        activate(c);
    }

    private void activate(Contact c) {
        if (finger.equals("touch")) {
            c.slot = takeSlot();
            if (c.slot < 0) { c.state = S.REJECTED; return; }
            c.state = S.ACTIVE;
            touchDirty = true;
            return;
        }
        // finger = pen (claimPen has settled who): not a palm
        if (penFinger != null || stylusRecently() || palmLike(c) || nearPalm(c)) {
            if (palmLike(c)) markPalm(c); else c.state = S.REJECTED;
            dbg(c, stylusRecently() ? "rej:stylus" : palmLike(c) ? "rej:palm-size" : "rej:near-palm");
            c.buf.clear();
            return;
        }
        // whatever else is down and older is the hand holding the pen
        for (int i = 0; i < contacts.size(); i++) {
            Contact o = contacts.valueAt(i);
            if (o != c && o.state == S.PENDING && o.downT <= c.downT) { o.state = S.REJECTED; o.buf.clear(); }
        }
        c.state = S.ACTIVE;
        penFinger = c;
        dbg(c, "PEN");
        if (c.major > 0) majorEma = majorEma == 0 ? c.major : majorEma * 0.8f + c.major * 0.2f;
        float[] first = c.buf.get(0);
        sendPen(PEN_IN_RANGE, first[0] / w, first[1] / h, 0, 0, 0, c.downT);     // proximity in
        for (float[] s : c.buf) {
            sendPen(PEN_IN_RANGE | PEN_TOUCH, s[0] / w, s[1] / h, FINGER_PEN_PRESSURE, 0, 0, c.downT + (long) s[2]);
            host.ink(s[0], s[1], true);
        }
        c.buf.clear();
    }

    private void endPenFinger(boolean takenOver) {
        Contact c = penFinger;
        if (c == null) return;
        penFinger = null;
        if (!takenOver) host.ink(c.x, c.y, false);
        sendPen(0, c.x / w, c.y / h, 0, 0, 0);   // lift + proximity out
        if (contacts.get(c.id) == c) c.state = S.REJECTED;
    }

    private void cancelContact(Contact c) {
        if (c == penFinger) endPenFinger(false);
        else if (c.state == S.ACTIVE && c.slot >= 0) { c.cancel = true; cancelled.add(c); touchDirty = true; }
        c.buf.clear();
    }

    // ---------------------------------------------------------------- palm rejection

    /** Bigger than any pen tip: an absolute floor, raised by what this pen's
     *  contacts have actually measured (a fingertip-sized dummy pen and a
     *  fine-tipped one end up with different thresholds). */
    // From `foldpen calibrate fit` (px, px^2), via the daemon's state; <= 0 = unset.
    private float calPalmPx = -1, calPalmArea = -1, calGrowPx = -1;

    void setCalibration(double palmPx, double palmArea, double growPx) {
        calPalmPx = (float) palmPx; calPalmArea = (float) palmArea; calGrowPx = (float) growPx;
    }

    private float palmThreshold() {
        if (calPalmPx > 0) return calPalmPx;
        return Math.min(Math.max(PALM_MM * mm, majorEma * PALM_EMA_X), PALM_MAX_MM * mm);
    }

    /** Mid-stroke: a pressed rubber tip flattens and grows, so a live stroke
     *  ends only past the pen's own calibrated maximum (or the palm limit). */
    private boolean grewIntoPalm(Contact c) {
        return calGrowPx > 0 ? c.major >= calGrowPx : palmLike(c);
    }

    private boolean palmLike(Contact c) {
        float areaLimit = calPalmArea > 0 ? calPalmArea
                : PALM_AREA_MM2 * mm * mm * Math.max(1, palmThreshold() / (PALM_MM * mm));
        return (c.major > 0 && c.major >= palmThreshold()) || (c.area > 0 && c.area >= areaLimit);
    }

    private boolean atEdge(Contact c) {
        float m = EDGE_MM * mm;
        return c.x0 < m || c.y0 < m || c.x0 > w - m || c.y0 > h - m;
    }

    private void markPalm(Contact c) {
        if (c == penFinger) endPenFinger(false);
        c.state = S.REJECTED;
        if (!c.palm) { c.palm = true; palms.add(new float[]{c.x, c.y, c.major / 2, 0, c.id}); }
        updatePalm(c);
    }

    private void updatePalm(Contact c) {
        for (float[] p : palms) if (p[3] == 0 && p[4] == c.id) { p[0] = c.x; p[1] = c.y; p[2] = Math.max(p[2], c.major / 2); }
    }

    private void endPalm(Contact c) {
        for (float[] p : palms) if (p[3] == 0 && p[4] == c.id) p[3] = SystemClock.uptimeMillis();
    }

    /** A contact landing right next to a resting (or just-lifted) palm is
     *  more hand. Distance only: in landscape the pen is not reliably "above"
     *  the palm. */
    private boolean nearPalm(Contact c) {
        long now = SystemClock.uptimeMillis();
        for (int i = palms.size() - 1; i >= 0; i--) {
            float[] p = palms.get(i);
            if (p[3] != 0 && now - p[3] > PALM_MEMORY_MS) { palms.remove(i); continue; }
            float dx = c.x0 - p[0], dy = c.y0 - p[1];
            if (Math.hypot(dx, dy) < PALM_NEAR_MM * mm) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- touch frames

    private int takeSlot() {
        for (int i = 0; i < slots.length; i++) if (!slots[i]) { slots[i] = true; return i; }
        return -1;
    }

    private void freeSlot(Contact c) {
        if (c.slot >= 0) slots[c.slot] = false;
        c.slot = -1;
    }

    private void flushTouch() {
        if (!touchDirty) return;
        touchDirty = false;
        ArrayList<Contact> live = new ArrayList<>();
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state == S.ACTIVE && c != penFinger && c.slot >= 0 && !c.cancel) live.add(c);
        }
        live.addAll(cancelled);
        ByteBuffer b = ByteBuffer.allocate(1 + live.size() * 10);
        b.put((byte) live.size());
        for (Contact c : live) {
            b.put((byte) c.slot).put((byte) (c.cancel ? TOUCH_PALM : TOUCH_DOWN))
                    .putFloat(c.x / w).putFloat(c.y / h);
        }
        link.send(Link.T_TOUCH, b.array());
        for (Contact c : cancelled) {
            freeSlot(c);
            if (contacts.get(c.id) == c) c.state = S.REJECTED;
        }
        cancelled.clear();
    }

    // ================================================================ 2-finger tap

    private void maybeTwoTap(Contact c, long t) {
        if (c.major < TWO_MIN_PX || recentPalm()) return;
        Contact other = null;
        int n = 0;
        for (int i = 0; i < contacts.size(); i++) {
            Contact o = contacts.valueAt(i);
            if (o.state == S.REJECTED) continue;
            n++;
            if (o != c && o.state == S.PENDING && t - o.downT <= TWO_SPREAD_MS && o.major >= TWO_MIN_PX) other = o;
        }
        if (n != 2 || other == null) return;
        twoA = other; twoB = c;
        other.state = c.state = S.TWO;
        dbg(c, "two:start");
    }

    private void twoUp(Contact c) {
        Contact a = twoA, b = twoB;
        if (a == null || b == null) return;
        boolean bothUp = contacts.get(a.id) != a && contacts.get(b.id) != b;
        if (!bothUp) return;
        twoA = twoB = null;
        long dur = SystemClock.uptimeMillis() - Math.min(a.downT, b.downT);
        if (dur > TWO_TAP_MS || a.state != S.TWO || b.state != S.TWO) return;
        float x = (a.x0 + b.x0) / 2 / w, y = (a.y0 + b.y0) / 2 / h;
        // a tip tap with the barrel button held: the pen's "right click"
        sendPen(PEN_IN_RANGE | PEN_BTN1, x, y, 0, 0, 0);
        sendPen(PEN_IN_RANGE | PEN_TOUCH | PEN_BTN1, x, y, FINGER_PEN_PRESSURE, 0, 0);
        sendPen(PEN_IN_RANGE | PEN_BTN1, x, y, 0, 0, 0);
        sendPen(0, x, y, 0, 0, 0);
        dbg(c, "two:BUTTON");
        host.tick();
    }

    // ================================================================ gestures

    /** A gesture is exactly three fingers on an otherwise empty screen, all
     *  landed within GESTURE_SPREAD_MS. Anything else down -- a resting palm,
     *  whose edges also report as tiny contacts, or the pen -- means it is
     *  not one: palm + pen + a palm edge used to start a "gesture" that then
     *  swallowed every later contact, the pen included. */
    private boolean maybeStartGesture(long now) {
        if (contacts.size() != 3 || !palms.isEmpty() && recentPalm()) return false;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state == S.REJECTED || c.state == S.ACTIVE || c.palm
                    || now - c.downT > GESTURE_SPREAD_MS) return false;
        }
        gesture = true;
        gestureFired = false;
        gestureMax = 0;
        gestureT0 = now;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state != S.REJECTED && !palmLike(c) && now - c.downT <= GESTURE_SPREAD_MS) {
                cancelContact(c);
                joinGesture(c);
            }
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(gestureHold, GESTURE_HOLD_MS);
        handler.postDelayed(gestureGiveUp, GESTURE_GIVEUP_MS);
        return true;
    }

    private boolean recentPalm() {
        long now = SystemClock.uptimeMillis();
        for (float[] p : palms) if (p[3] == 0 || now - p[3] < PALM_MEMORY_MS) return true;
        return false;
    }

    /** A gesture that has not fired by now was a hand, not a command: its
     *  contacts are let go (rejected), and the next contact starts fresh. */
    private final Runnable gestureGiveUp = this::gestureGiveUp;

    private void gestureGiveUp() {
        if (!gesture) return;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state == S.GESTURE) { c.state = S.REJECTED; dbg(c, "gesture:timeout"); }
        }
        handler.removeCallbacks(gestureHold);
        gesture = false;
    }

    private void joinGesture(Contact c) {
        c.state = S.GESTURE;
        c.gx0 = c.x;
        c.gy0 = c.y;
    }

    private void gestureMove() {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.valueAt(i);
            if (c.state != S.GESTURE) continue;
            sx += c.x - c.gx0; sy += c.y - c.gy0; n++;
        }
        if (n == 0) return;
        float dx = sx / n, dy = sy / n;
        gestureMax = Math.max(gestureMax, (float) Math.hypot(dx, dy));
        if (gestureFired) return;
        if (Math.abs(dx) > SWIPE_MM * mm && Math.abs(dx) > 1.5f * Math.abs(dy)) fire("finger");
        else if (Math.abs(dy) > SWIPE_MM * mm) gestureFired = true;   // reserved, swallowed
    }

    private void gestureUp() {
        for (int i = 0; i < contacts.size(); i++) if (contacts.valueAt(i).state == S.GESTURE) return;
        handler.removeCallbacks(gestureHold);
        handler.removeCallbacks(gestureGiveUp);
        if (!gestureFired && gestureMax < SLOP_MM * mm
                && SystemClock.uptimeMillis() - gestureT0 < GESTURE_TAP_MS) fire("display");
        gesture = false;
    }

    private void fire(String what) {
        gestureFired = true;
        host.toggle(what);
    }

    // ================================================================ edit mode

    /** The rectangle as an object under the fingers: it follows them, and
     *  grows as they spread. Coalesced to one message per ~16 ms. */
    private void editUpdate(MotionEvent e, int am, int ai) {
        float cx = 0, cy = 0;
        int n = 0, key = 17;
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean leaving = i == ai && (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_POINTER_UP
                    || am == MotionEvent.ACTION_CANCEL);
            if (leaving) continue;
            Contact c = contacts.get(e.getPointerId(i));
            if (!isStylus(e, i) && (c == null || c.state != S.EDIT)) continue;
            cx += e.getX(i); cy += e.getY(i); n++;
            key = key * 31 + e.getPointerId(i);
        }
        if (n == 0) { editKey = 0; sendEdit(true); return; }
        cx /= n; cy /= n;
        float spread = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            Contact c = contacts.get(e.getPointerId(i));
            if (!isStylus(e, i) && (c == null || c.state != S.EDIT)) continue;
            spread += (float) Math.hypot(e.getX(i) - cx, e.getY(i) - cy);
        }
        spread /= n;
        if (key == editKey) {
            accDx += (cx - editCx) / w;
            accDy += (cy - editCy) / w;
            if (n >= 2 && editSpread > mm && spread > mm) accScale *= spread / editSpread;
            accCx = cx / w; accCy = cy / h;
        }
        editKey = key; editCx = cx; editCy = cy; editSpread = spread;
        sendEdit(false);
    }

    private void sendEdit(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (accDx == 0 && accDy == 0 && accScale == 1) return;
        if (!force && now - lastEditSend < 16) return;
        lastEditSend = now;
        try {
            link.sendJson(Link.T_CMD, new JSONObject().put("cmd", "rect")
                    .put("dx", accDx).put("dy", accDy).put("scale", accScale)
                    .put("cx", accCx).put("cy", accCy));
        } catch (Exception ignored) { }
        accDx = accDy = 0;
        accScale = 1;
    }
}
