package dev.foldpen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;

/**
 * Full-screen surface: the forwarded frame (if any), a short fading ink trail
 * drawn locally the instant the pen moves (the frames lag; this does not),
 * the pen's hover dot, and a one-word label when a mode changes.
 */
final class PenView extends View {
    interface SizeListener { void onSize(int w, int h); }

    static final long INK_MS = 450, LABEL_MS = 900;

    Router router;
    SizeListener sizeListener;
    private Bitmap frame;
    private final Rect src = new Rect(), dst = new Rect();
    private final ArrayDeque<float[]> ink = new ArrayDeque<>();   // x, y, time, penDown(1/0)
    private float hoverX, hoverY;
    private boolean hovering;
    private String label, status;
    private long labelT;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PenView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        float d = getResources().getDisplayMetrics().density;
        inkPaint.setStrokeWidth(2.5f * d);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setColor(0xccff9e26);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setColor(0xff555555);
        statusPaint.setTextSize(14 * d);
        statusPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        // Deliver every touch/pen event as it arrives instead of batching to
        // vsync: this is most of the latency a drawing app would otherwise add.
        requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER);
    }

    void setFrame(Bitmap b) { frame = b; invalidate(); }

    void setStatus(String s) { status = s; invalidate(); }

    void flash(String text) { label = text; labelT = SystemClock.uptimeMillis(); invalidate(); }

    void ink(float x, float y, boolean down) {
        ink.addLast(new float[]{x, y, SystemClock.uptimeMillis(), down ? 1 : 0});
        hovering = false;
        postInvalidateOnAnimation();
    }

    void hover(float x, float y, boolean in) {
        hoverX = x; hoverY = y; hovering = in;
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (sizeListener != null) sizeListener.onSize(w, h);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e);
        if (router != null) router.onTouch(e);
        return true;
    }

    @Override public boolean onHoverEvent(MotionEvent e) {
        if (router != null) router.onHover(e);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean animate = false;
        if (frame != null) {
            src.set(0, 0, frame.getWidth(), frame.getHeight());
            float s = Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight());
            int fw = Math.round(frame.getWidth() * s), fh = Math.round(frame.getHeight() * s);
            dst.set((getWidth() - fw) / 2, (getHeight() - fh) / 2, (getWidth() + fw) / 2, (getHeight() + fh) / 2);
            c.drawBitmap(frame, src, dst, null);
        }
        while (!ink.isEmpty() && now - ink.peekFirst()[2] > INK_MS) ink.removeFirst();
        float[] prev = null;
        for (float[] p : ink) {
            if (prev != null && prev[3] == 1 && p[3] == 1) {
                int a = (int) (255 * (1 - (now - p[2]) / (float) INK_MS));
                inkPaint.setColor(Color.argb(Math.max(a, 0), 255, 158, 38));
                c.drawLine(prev[0], prev[1], p[0], p[1], inkPaint);
            }
            prev = p;
        }
        if (!ink.isEmpty()) animate = true;
        if (hovering) c.drawCircle(hoverX, hoverY, 4 * getResources().getDisplayMetrics().density, dotPaint);
        if (label != null) {
            long age = now - labelT;
            if (age < LABEL_MS) {
                textPaint.setAlpha((int) (255 * (1 - age / (float) LABEL_MS)));
                c.drawText(label, getWidth() / 2f, getHeight() / 2f, textPaint);
                animate = true;
            } else label = null;
        }
        if (status != null) c.drawText(status, getWidth() / 2f, getHeight() - 40 * getResources().getDisplayMetrics().density, statusPaint);
        if (animate) postInvalidateOnAnimation();
    }
}
