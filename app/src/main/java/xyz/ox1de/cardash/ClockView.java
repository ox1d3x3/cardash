package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Screensaver: your car parked in a bay on the left, a ring clock on the right
 * (thin digits inside a 60-tick seconds ring that fills blue to cyan, breathing colon, date,
 * now-playing line, speed while moving). On narrow screens: clock only.
 *
 * Intro (about 2.5 s, once per start): the car rolls into the bay, brakes, and the indicators
 * flash twice as it "locks".
 *
 * Cost: after the intro, exactly one redraw per second (aligned to the second): one bitmap,
 * 60 lines, a few texts. Strings are rebuilt once per minute. The whole scene drifts a few px
 * every minute so nothing sits on the same pixels for hours (LCD image retention).
 */
public class ClockView extends View {

    static final int BG = 0xFF000000, DIM = 0xFF1C2026, ACC1 = 0xFF3E6AE1, ACC2 = 0xFF4FD1FF;
    static final long ROLL_MS = 1500, LOCK_AT = 1650, INTRO_MS = 2700;

    final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.text.TextPaint sub = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint spd = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint bayV = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bayH = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stop = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint amber = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int[] ramp = new int[60];
    final float[] cos = new float[60], sin = new float[60];

    boolean running, introPending;
    int lastMinute = -1, lastDay = -1;
    String hh = "", mm = "", ampm = "", date = "", nowPlaying = "", speed = "";
    CharSequence subShown = "";
    float subKey = -1;
    float dx, dy, drift;
    SimpleDateFormat dateFmt;
    final Calendar cal = Calendar.getInstance(); // reused: no Calendar allocated per frame

    // parked car
    int carStyle = -1, carColor = 0xFFF4F5F7;
    volatile int gen;
    CarArt.Spec spec = CarArt.spec(CarArt.HATCH);
    Bitmap car, tailLit;
    float litTailY;
    int pad;
    float s, carCx, carL, carT, bayL, bayR, bayTop, bayBot, lineW, glowR;
    long introAt = -1;

    // the cat that comes to sleep on the roof
    final SleepyCat cat = new SleepyCat();
    boolean catOn = true;
    static final long CAT_AFTER = 1300; // ms after the car has locked

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
        stop.setColor(0xFF1E2228);
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
        introPending = true;
        introAt = -1;
        removeCallbacks(tickR);
        post(tickR);
    }

    public void stop() {
        running = false;
        removeCallbacks(tickR);
        cat.reset();
    }

    public void setCat(boolean on) {
        catOn = on;
        if (!on) cat.reset();
    }

    /**
     * A tap while the cat is there: it startles and runs off, then {@code whenGone} runs.
     * @return false if there's no cat (yet): wake straight away as usual
     */
    public boolean catTap(Runnable whenGone) {
        if (!running || !split() || !cat.tap(SystemClock.uptimeMillis(), () -> post(whenGone))) return false;
        invalidate();
        return true;
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

    /** style: CarArt.HATCH / SEDAN, or -1 for clock only. colour 0 = white (the saver is always dark). */
    public void setCar(int style, int color) {
        if (color == 0) color = 0xFFF4F5F7;
        if (style == carStyle && color == carColor && (car != null || style < 0)) return;
        carStyle = style;
        carColor = color;
        if (style >= 0) spec = CarArt.spec(style);
        layoutCar();
        rebuildCar();
        invalidate();
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

    // ------------------------------------------------------------------ layout

    boolean split() {
        return carStyle >= 0 && getWidth() >= getHeight() * 1.25f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        subKey = -1;
        layoutCar();
        rebuildCar();
    }

    void layoutCar() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || carStyle < 0) return;
        float len = spec.tail - spec.nose;
        s = Math.min(h * 0.66f / len, w * 0.19f / CarArt.VW);
        carCx = w * 0.255f;
        carL = carCx - CarArt.VW / 2f * s;
        carT = h / 2f - (spec.nose + spec.tail) / 2f * s;
        bayL = carCx - 112 * s;
        bayR = carCx + 112 * s;
        bayTop = carT + (spec.nose - 34) * s;
        bayBot = Math.min(h, carT + (spec.tail + 60) * s);
        lineW = Math.max(2f, 4.5f * s);
        glowR = 30 * s;
        int line = 0xFF3A3F47;
        bayV.setShader(new LinearGradient(0, bayTop, 0, bayBot, new int[]{line, line, 0x003A3F47},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        float ext = 70 * s;
        bayH.setShader(new LinearGradient(bayL - ext, 0, bayR + ext, 0, new int[]{0x003A3F47, line, line, 0x003A3F47},
                new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        amber.setShader(new RadialGradient(0, 0, glowR, new int[]{0xFFFFE0A0, 0xCCFFA726, 0x00FF9800},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        red.setShader(new RadialGradient(0, 0, glowR * 1.15f, new int[]{0xFFFF3B3B, 0x88FF2020, 0x00FF2020},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
    }

    void rebuildCar() {
        if (carStyle < 0 || s <= 0) { car = null; return; }
        final int g = ++gen, st = carStyle, col = carColor;
        final float sc = s;
        final int pd = Math.round(14 * s);
        final Context ctx = getContext();
        CarArt.EX.execute(() -> {
            if (g != gen) return;
            final CarArt.Set set = CarArt.renderSet(ctx, st, col, sc, pd, false, false, true);
            final float ty = CarArt.spec(st).tailY0 * sc;
            post(() -> {
                if (g != gen) return;
                car = set.body;
                tailLit = set.tail;
                litTailY = ty;
                pad = pd;
                if (running) invalidate();
            });
        });
    }

    // ------------------------------------------------------------------ draw

    void refreshStrings(Calendar c) {
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (minute == lastMinute) return;
        // once a minute: pick up a time-zone change (travel, network time) before formatting
        c.setTimeZone(java.util.TimeZone.getDefault());
        c.setTimeInMillis(System.currentTimeMillis());
        minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
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

    static float easeOut(float t) { t = Math.max(0f, Math.min(1f, t)); return 1f - (1f - t) * (1f - t) * (1f - t); }

    static float bump(float t, float t0, float d) {
        if (t < t0 || t > t0 + d) return 0f;
        return (float) Math.sin(Math.PI * (t - t0) / d);
    }

    @Override
    protected void onDraw(Canvas cv) {
        Calendar c = cal;
        c.setTimeInMillis(System.currentTimeMillis());
        refreshStrings(c);
        int sec = c.get(Calendar.SECOND);

        float w = getWidth(), h = getHeight();
        boolean split = split();
        if (split) drawCar(cv, h);

        float hasSub = nowPlaying.isEmpty() ? 0f : 1f;
        float areaW = split ? w * 0.62f : w;
        float R = Math.min(areaW * 0.5f, h * (0.5f - 0.05f * hasSub)) * 0.78f;
        float cx = (split ? w * 0.655f : w / 2f) + dx, cy = h / 2f - R * 0.10f * hasSub + dy;

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
                subShown = TextUtils.ellipsize(nowPlaying, sub, (split ? 0.6f : 0.8f) * w, TextUtils.TruncateAt.END);
            }
            cv.drawText(subShown, 0, subShown.length(), cx, cy + R + R * 0.24f, sub);
        }
    }

    void drawCar(Canvas cv, float h) {
        int sv = cv.save();
        cv.translate(dx, dy);

        // the bay: side lines fading towards the viewer, a front line, a wheel stop
        cv.drawRect(bayL - lineW / 2, bayTop, bayL + lineW / 2, bayBot, bayV);
        cv.drawRect(bayR - lineW / 2, bayTop, bayR + lineW / 2, bayBot, bayV);
        float ext = 70 * s;
        cv.drawRect(bayL - ext, bayTop - lineW / 2, bayR + ext, bayTop + lineW / 2, bayH);
        float sy = carT + (spec.nose - 20) * s;
        cv.drawRoundRect(carCx - 46 * s, sy - 5 * s, carCx + 46 * s, sy + 5 * s, 5 * s, 5 * s, stop);

        if (car == null) { cv.restoreToCount(sv); return; }

        long now = SystemClock.uptimeMillis();
        if (introPending && running) { introPending = false; introAt = now; }
        long t = introAt < 0 ? INTRO_MS : now - introAt;
        boolean intro = t < INTRO_MS;
        float tt = t / 1000f;

        float roll = easeOut(t / (float) ROLL_MS);
        float yOff = (1f - roll) * (h * 0.62f);
        bmp.setAlpha((int) (255 * Math.min(1f, 0.15f + roll * 1.2f)));
        cv.drawBitmap(car, carL - pad, carT - pad + yOff, bmp);

        // brake as it comes to rest, then two indicator flashes as it locks
        float brake = 0f;
        if (intro) {
            float b0 = ROLL_MS * 0.55f, b1 = ROLL_MS + 250;
            if (t > b0 && t < b1) brake = Math.min(1f, (t - b0) / 200f);
            else if (t >= b1) brake = Math.max(0f, 1f - (t - b1) / 400f);
        }
        float lock = intro ? Math.max(bump(tt, LOCK_AT / 1000f, 0.32f), bump(tt, LOCK_AT / 1000f + 0.45f, 0.32f)) : 0f;
        float tailY = carT + spec.tailY * s + yOff, headY = carT + spec.headY * s + yOff;
        float tR = carL + spec.tailX * s, tL = carL + (CarArt.VW - spec.tailX) * s;
        float hR = carL + (spec.headX + 6) * s, hL = carL + (CarArt.VW - spec.headX - 6) * s;
        if (brake > 0.01f) {
            if (tailLit != null) {
                bmp.setAlpha((int) (255 * brake));
                cv.drawBitmap(tailLit, carL - pad, carT + litTailY + yOff, bmp);
                bmp.setAlpha(255);
            }
            red.setAlpha((int) (255 * brake));
            glow(cv, tL, tailY, red);
            glow(cv, tR, tailY, red);
        }
        if (lock > 0.01f) {
            amber.setAlpha((int) (255 * lock));
            glow(cv, hL, headY, amber);
            glow(cv, hR, headY, amber);
            glow(cv, tL - 2 * s, tailY - 10 * s, amber);
            glow(cv, tR + 2 * s, tailY - 10 * s, amber);
        }
        // the cat: walks on after the lock flash, sleeps on the roof
        long catNext = -1;
        if (catOn && running) {
            if (!cat.started() && introAt >= 0) cat.start(introAt + INTRO_MS + CAT_AFTER);
            float roofY = carT + (spec.nose + 0.6f * (spec.tail - spec.nose)) * s;
            catNext = cat.draw(cv, now, carCx, roofY, bayR, s, getWidth(), h);
        }
        cv.restoreToCount(sv);
        if (!running) return;
        if (intro || catNext == 0) postInvalidateOnAnimation();
        else if (catNext > 0 && catNext < 1000) postInvalidateDelayed(catNext);
    }

    void glow(Canvas cv, float x, float y, Paint p) {
        cv.save();
        cv.translate(x, y);
        cv.drawCircle(0, 0, glowR * 1.15f, p);
        cv.restore();
    }
}
