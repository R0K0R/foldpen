package dev.foldpen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * One black full-screen view. No buttons: everything is a 3-finger gesture
 * (see Router), confirmed by a vibration pattern so the screen need not be
 * looked at. The only text field is the one-time pairing code.
 */
public final class MainActivity extends Activity implements Link.Listener, Router.Host {
    private Link link;
    private Router router;
    private PenView view;
    private EditText codeField;
    private Vibrator vibrator;
    private volatile boolean display;
    private boolean edit;
    private String finger = Flags.FINGER_PEN ? "pen" : "touch";
    private boolean haveState;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vibrator = getSystemService(Vibrator.class);
        link = new Link(this, this);
        router = new Router(link, this);
        view = new PenView(this);
        view.router = router;
        view.sizeListener = (w, h) -> {
            float dpi = (getResources().getDisplayMetrics().xdpi + getResources().getDisplayMetrics().ydpi) / 2;
            router.setSize(w, h, dpi);
            link.viewW = w;
            link.viewH = h;
            link.sendJson(Link.T_VIEW, link.viewJson());
        };

        codeField = new EditText(this);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setHint("pairing code  (foldpen pair)");
        codeField.setTextColor(Color.WHITE);
        codeField.setHintTextColor(0xff777777);
        codeField.setGravity(Gravity.CENTER);
        codeField.setVisibility(View.GONE);
        codeField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (s.length() == 6) {
                    link.setPairCode(s.toString());
                    s.clear();
                    showPairing(false);
                    view.setStatus("pairing…");
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        lp.leftMargin = lp.rightMargin = 80;
        root.addView(codeField, lp);
        setContentView(root);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams wl = getWindow().getAttributes();
        wl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(wl);
        getWindow().setDecorFitsSystemWindows(false);
        takeExtras(getIntent());
        view.setStatus("searching…");
        applyBrightness();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        takeExtras(i);
        link.reconnect();
    }

    /** `foldpen usb` / `foldpen install` hand over the token and host via adb. */
    private void takeExtras(Intent i) {
        if (i == null) return;
        SharedPreferences.Editor e = getSharedPreferences("foldpen", Context.MODE_PRIVATE).edit();
        if (i.hasExtra("token")) e.putString("token", i.getStringExtra("token"));
        if (i.hasExtra("host")) e.putString("host", i.getStringExtra("host"));
        if (i.hasExtra("port")) e.putInt("port", i.getIntExtra("port", Link.PORT));
        e.apply();
    }

    @Override protected void onStart() {
        super.onStart();
        Log.i("foldpen", "onStart");
        immersive();
        link.start();
    }

    @Override protected void onStop() {
        Log.i("foldpen", "onStop");
        link.stop();
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    private void immersive() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** Display forwarding off: the panel is dimmed to nearly nothing (black
     *  pixels on OLED cost nothing anyway). */
    private void applyBrightness() {
        WindowManager.LayoutParams wl = getWindow().getAttributes();
        wl.screenBrightness = display ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : 0.01f;
        getWindow().setAttributes(wl);
    }

    private void showPairing(boolean on) {
        codeField.setVisibility(on ? View.VISIBLE : View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (on) { codeField.requestFocus(); imm.showSoftInput(codeField, 0); }
        else imm.hideSoftInputFromWindow(codeField.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- Router.Host

    @Override public void toggle(String what) {
        if (!link.connected()) { buzz(0, 30, 60, 30, 60, 30); return; }   // nothing to toggle
        if (what.equals("finger") && !Flags.FINGER_PEN) {                // built without finger = pen
            buzz(0, 30, 60, 30, 60, 30);
            view.flash("finger = pen is disabled");
            return;
        }
        try { link.sendJson(Link.T_CMD, new JSONObject().put("cmd", "toggle").put("what", what)); }
        catch (Exception ignored) { }
    }

    @Override public void ink(float x, float y, boolean down) { view.ink(x, y, down); }

    @Override public void hover(float x, float y, boolean in) { view.hover(x, y, in); }

    @Override public void tick() { buzz(0, 12); }

    // ---------------------------------------------------------------- Link.Listener (any thread)

    @Override public void onConnected(boolean c) {
        runOnUiThread(() -> {
            if (c) { showPairing(false); view.setStatus(null); buzz(0, 15); }
            else {
                view.setStatus("searching…");
                view.setFrame(null);
                haveState = false;
                router.setModes(finger, false);
            }
        });
    }

    @Override public void onPairNeeded() {
        runOnUiThread(() -> { view.setStatus("not paired"); showPairing(true); });
    }

    @Override public void onState(JSONObject s) {
        runOnUiThread(() -> {
            boolean d = s.optBoolean("display"), e = s.optBoolean("edit");
            String f = s.optString("finger", "pen");
            if (!Flags.FINGER_PEN && f.equals("pen")) {
                // built without finger = pen: fingers are touch whatever the
                // PC says; flip the PC's state too, so `foldpen status` is true
                try { link.sendJson(Link.T_CMD, new JSONObject().put("cmd", "toggle").put("what", "finger")); }
                catch (Exception ignored) { }
                f = "touch";
            }
            if (haveState) {
                // Distinct patterns, so each toggle can be told apart blind:
                // display = short ticks, finger mode = firm pulses, edit = long.
                if (d != display) { buzz(d ? new long[]{0, 20} : new long[]{0, 20, 90, 20}); view.flash(d ? "display" : "display off"); }
                if (!f.equals(finger)) { buzz(f.equals("pen") ? new long[]{0, 55} : new long[]{0, 55, 90, 55}); view.flash(f.equals("pen") ? "finger = pen" : "finger = touch"); }
                if (e != edit) { buzz(e ? new long[]{0, 160} : new long[]{0, 160, 90, 25}); view.flash(e ? "edit" : "edit done"); }
            }
            haveState = true;
            display = d; edit = e; finger = f;
            router.setModes(f, e);
            JSONObject cal = s.optJSONObject("calib");
            if (cal != null) router.setCalibration(cal.optDouble("palm_px", -1),
                    cal.optDouble("palm_area", -1), cal.optDouble("grow_px", -1));
            else router.setCalibration(-1, -1, -1);
            if (!d) view.setFrame(null);
            applyBrightness();
        });
    }

    @Override public void onFrame(byte[] jpeg) {
        if (!display) return;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (b != null) runOnUiThread(() -> { if (display) view.setFrame(b); });
    }

    private void buzz(long... pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }
}
