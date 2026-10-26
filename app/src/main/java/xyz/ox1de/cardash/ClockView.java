package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Screensaver clock: thin digits inside a 60-tick seconds ring that fills blue → cyan,
 * blinking colon, date, now-playing line and speed while moving.
 *
 * Cost: redraws exactly once per second (aligned to the second), 60 lines + 4 texts.
 * Strings are rebuilt once per minute. The whole face drifts a few px every minute so
 * nothing sits on the same pixels for hours (LCD image retention).
 */
public class ClockView extends View {

    static final int BG = 0xFF000000, DIM = 0xFF1C2026, ACC1 = 0xFF3E6AE1, ACC2 = 0xFF4FD1FF;

    final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.text.TextPaint sub = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint spd = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int[] ramp = new int[60];
    final float[] cos = new float[60], sin = new float[60];

    boolean running;
    int lastMinute = -1, lastDay = -1;
    String hh = "", mm = "", ampm = "", date = "", nowPlaying = "", speed = "";
    CharSequence subShown = "";
    float subKey = -1;
    float dx, dy, drift;
    SimpleDateFormat dateFmt;

    public ClockView(Context c) { this(c, null); }

    public ClockView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(BG);
        tick.setStrokeCap(Paint.Cap.ROUND);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setColor(0xFFF2F3F5);
        time.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(0xFF8B919A);
        small.setTextAlign(Paint.Align.CENTER);
        small.setLetterSpacing(0.22f);
        sub.setColor(0xFFC9CDD3);
        sub.setTextAlign(Paint.Align.CENTER);
        spd.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        spd.setColor(ACC2);
        spd.setTextAlign(Paint.Align.CENTER);
        spd.setLetterSpacing(0.12f);
        for (int i = 0; i < 60; i++) {
            ramp[i] = lerp(ACC1, ACC2, i / 59f);
            double ang = Math.toRadians(i * 6 - 90);
            cos[i] = (float) Math.cos(ang);
            sin[i] = (float) Math.sin(ang);
        }
    }

    static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ API

    public void start() {
        if (running) return;
        running = true;
        lastMinute = -1;
        removeCallbacks(tickR);
        post(tickR);
    }

    public void stop() {
        running = false;
        removeCallbacks(tickR);
    }

    public void setNowPlaying(String s) {
        s = s == null ? "" : s;
        if (!s.equals(nowPlaying)) { nowPlaying = s; subKey = -1; if (running) invalidate(); }
    }

    /** Pass "" to hide. */
    public void setSpeed(String s) {
        s = s == null ? "" : s;
        if (!s.equals(speed)) { speed = s; if (running) invalidate(); }
    }

    final Runnable tickR = new Tick();

    class Tick implements Runnable {
        @Override public void run() {
            if (!running) return;
            invalidate();
            // next whole second (+5 ms so the clock has definitely rolled over)
            postDelayed(this, 1005 - (System.currentTimeMillis() % 1000));
        }
    }

    // ------------------------------------------------------------------ draw

    void refreshStrings(Calendar c) {
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (minute == lastMinute) return;
        lastMinute = minute;
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h24) {
            hh = (h < 10 ? "0" : "") + h;
            ampm = "";
        } else {
            int h12 = h % 12 == 0 ? 12 : h % 12;
            hh = String.valueOf(h12);
            ampm = h < 12 ? "AM" : "PM";
        }
        int m = c.get(Calendar.MINUTE);
        mm = (m < 10 ? "0" : "") + m;
        int day = c.get(Calendar.DAY_OF_YEAR);
        if (day != lastDay || dateFmt == null) {
            lastDay = day;
            dateFmt = new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault());
            date = dateFmt.format(c.getTime()).toUpperCase(Locale.getDefault());
        }
        // slow circular drift, one step per minute, ~1 hour per loop
        drift += 0.105f;
        float amp = 9 * getResources().getDisplayMetrics().density;
        dx = (float) Math.cos(drift) * amp;
        dy = (float) Math.sin(drift) * amp;
    }

    @Override
    protected void onDraw(Canvas cv) {
        Calendar c = Calendar.getInstance();
        refreshStrings(c);
        int sec = c.get(Calendar.SECOND);

        float w = getWidth(), h = getHeight();
        float hasSub = nowPlaying.isEmpty() ? 0f : 1f;
        float R = Math.min(w * 0.5f, h * (0.5f - 0.05f * hasSub)) * 0.78f;
        float cx = w / 2f + dx, cy = h / 2f - R * 0.10f * hasSub + dy;

        // seconds ring
        float len = R * 0.055f, lenMajor = R * 0.095f;
        tick.setStrokeWidth(Math.max(2f, R * 0.012f));
        for (int i = 0; i < 60; i++) {
            float l = (i % 5 == 0) ? lenMajor : len;
            float r0 = R - l, r1 = R;
            if (i == sec) { tick.setColor(0xFFFFFFFF); r0 -= R * 0.03f; }
            else if (i < sec) tick.setColor(ramp[i]);
            else tick.setColor(DIM);
            cv.drawLine(cx + cos[i] * r0, cy + sin[i] * r0, cx + cos[i] * r1, cy + sin[i] * r1, tick);
        }

        // HH:MM, colon breathes once per second
        float ts = R * 0.56f;
        time.setTextSize(ts);
        float base = cy + ts * 0.36f;
        float colonW = time.measureText(":");
        float gap = ts * 0.04f;
        time.setAlpha(255);
        time.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(hh, cx - colonW / 2 - gap, base, time);
        time.setTextAlign(Paint.Align.LEFT);
        cv.drawText(mm, cx + colonW / 2 + gap, base, time);
        time.setTextAlign(Paint.Align.CENTER);
        time.setAlpha((sec & 1) == 0 ? 255 : 90);
        cv.drawText(":", cx, base - ts * 0.04f, time);
        time.setAlpha(255);

        small.setTextSize(R * 0.068f);
        if (!ampm.isEmpty()) cv.drawText(ampm, cx, cy - ts * 0.52f, small);
        if (!speed.isEmpty()) {
            spd.setTextSize(R * 0.085f);
            cv.drawText(speed, cx, cy - ts * (ampm.isEmpty() ? 0.52f : 0.78f), spd);
        }
        cv.drawText(date, cx, base + R * 0.26f, small);

        if (hasSub > 0) {
            sub.setTextSize(R * 0.085f);
            if (subKey != w + R) { // ellipsize only when text or size changes, not every second
                subKey = w + R;
                subShown = TextUtils.ellipsize(nowPlaying, sub, w * 0.8f, TextUtils.TruncateAt.END);
            }
            cv.drawText(subShown, 0, subShown.length(), cx, cy + R + R * 0.24f, sub);
        }
    }
}
