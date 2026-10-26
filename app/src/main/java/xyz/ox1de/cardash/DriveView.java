package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The car on the road, in one of two views:
 *  - TOP:  top-down, lane markings scroll past
 *  - REAR: "chase cam" from behind, road to the horizon, roadside blocks stream past, number plate
 *
 * Lights (both views):
 *  - 6 pm - 5 am: headlamps power on with an LED-style ramp, beams on the road, tail lamps glow
 *  - brake lights pop on only when the speed drops suddenly (firm braking), held while it keeps
 *    dropping, then fade; the bloom flares and (rear view) the car's tail lifts a touch as they bite
 *  - start-up "welcome": the car fades/rises in and the lamps flash
 *  - colour / theme / body-style / plate changes cross-fade between the old and new car
 *
 * Performance (2 GB head units):
 *  - the car is rasterised once, on a background thread (CarArt); a frame is a few bitmap draws
 *    plus gradient shapes, no allocations
 *  - frames are requested only while something is moving or fading. Parked + steady = zero
 *    frames, zero CPU; activity paused = zero frames
 *  - motion uses the display's vsync timestamp, so it stays evenly spaced even if a frame drops
 */
public class DriveView extends View {

    static final int TOP = 0, REAR = 1;
    static final float VW = CarArt.VW, VH = CarArt.VH;

    final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint lane = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tailGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint lensGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint road = new Paint();
    final Path beamPath = new Path();
    final RearScene rear = new RearScene();

    Bitmap car, oldCar, headLit, tailLit; // TOP: headLit / tailLit = lamps on. REAR: headLit = stop lamp
    int carColor = 0xFFF2F3F5, style = CarArt.HATCH, view = TOP, bg = 0xFF131519;
    String plate = "";
    volatile int gen;
    CarArt.Spec spec = CarArt.spec(CarArt.HATCH);
    CarArt.RearSpec rspec = CarArt.WHITE_REAR;  // rear view: geometry of the art currently on screen
    float s;              // px per artwork unit
    float carL, carT;     // top-left of the artwork box, px (TOP)
    float drawnL, drawnT, oldL, oldT; // where the current / previous bitmaps were laid out
    float litHeadY, litTailY;         // offsets of the lamp bands inside the artwork, px
    int pad, oldPad;      // shadow margin inside the bitmap
    int drawnView = -1, oldView = -1; // which view each bitmap was made for
    float laneW, dash, period, laneX1, laneX2, edgeX1, edgeX2;
    float headLX, headRX, headY, tailLX, tailRX, tailY, tailR, lensR;

    // state
    float target, shown, offset, dashOff, blkOff, moveT, brakeT, head, tailRun, swapT = 1f;
    boolean night, active, light, welcomePending;
    long brakeUntil, brakeOnAt = -1, lastFrame, refAt, welcomeAt = -1, headOnAt = -1;
    float skyT;                 // chase-cam sky: 0 = day .. 1 = night, animated (sunset / sunrise)
    boolean skyInit;
    /** Chase cam while nothing else moves: clouds drift, stars twinkle, at a gentle 12 fps (not 60). */
    static final long AMBIENT_MS = 83;
    float refMs;

    public DriveView(Context c) { this(c, null); }

    public DriveView(Context c, AttributeSet a) {
        super(c, a);
        lane.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.FILL);
        beam.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ public API

    /** Speed in m/s, or negative when there is no GPS fix. */
    public void setSpeed(float ms) {
        long now = SystemClock.uptimeMillis();
        if (ms < 0) {
            target = 0;
            refAt = 0;
            kick();
            return;
        }
        // Brake lights: only for a SUDDEN drop. Measured over >= 0.4 s windows so it works the same
        // with 1 Hz and 10 Hz GPS; >= 1.8 m/s^2 (~6.5 km/h per second) is firm braking, coasting
        // and gentle slowing don't count; > 12 m/s^2 is impossible for a car = GPS glitch, ignored.
        if (refAt == 0 || now - refAt > 3000) {
            refAt = now;
            refMs = ms;
        } else if (now - refAt >= 400) {
            float dt = (now - refAt) / 1000f, drop = refMs - ms, decel = drop / dt;
            if (refMs > 2.5f && drop > 0.8f && decel > 1.8f && decel < 12f) {
                if (now >= brakeUntil) brakeOnAt = now;          // a fresh press: pop
                brakeUntil = now + Math.max(900, (long) (dt * 1000) + 400); // held until the next reading
            }
            refAt = now;
            refMs = ms;
        }
        target = ms;
        kick();
    }

    /** View + body + colour + theme + plate in one go: one re-render instead of several. */
    public void setLook(int v, int st, int color, boolean l, String pl, int bgColor) {
        pl = pl == null ? "" : pl;
        if (v == view && st == style && color == carColor && l == light && pl.equals(plate) && bgColor == bg && car != null) return;
        boolean viewChanged = v != view;
        boolean geo = viewChanged || st != style || l != light || bgColor != bg;
        boolean sh = l != light;
        view = v;
        style = st;
        spec = CarArt.spec(st);
        carColor = color;
        light = l;
        plate = pl;
        bg = bgColor;
        if (geo) layoutGeometry();
        if (sh) buildShaders();
        if (viewChanged && car != null) welcomePending = true; // switching view: the new one rises in
        rebuildCar();
    }

    public void setAppearance(int st, int color, boolean l) { setLook(view, st, color, l, plate, bg); }

    public void setCarColor(int c) { setLook(view, style, c, light, plate, bg); }

    public void setLight(boolean l) { setLook(view, style, carColor, l, plate, bg); }

    /** 6 pm - 5 am (or real sunset - sunrise): headlamps + tail lamps on, with a smooth power-on. */
    public void setNight(boolean n) {
        if (!skyInit) { skyInit = true; skyT = n ? 1f : 0f; }  // first call: no sunset animation at start-up
        if (n == night) return;
        night = n;
        if (n) headOnAt = SystemClock.uptimeMillis();
        kick();
    }

    /** Start-up greeting: car fades in, lamps flash once. Runs once the car bitmap is ready. */
    public void welcome() {
        if (car == null) { welcomePending = true; return; }
        welcomeAt = SystemClock.uptimeMillis();
        kick();
    }

    /** Call from onResume/onPause. When inactive no frames are ever scheduled. */
    public void setActive(boolean a) {
        active = a;
        lastFrame = 0;
        if (a) kick();
    }

    // ------------------------------------------------------------------ geometry

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        layoutGeometry();
        buildShaders();
        rebuildCar();
    }

    void layoutGeometry() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (view == REAR) {
            s = rear.layout(w, h, getPaddingTop(), getPaddingBottom(), light, bg, getResources().getDisplayMetrics().density);
            return;
        }
        float availH = h - getPaddingTop() - getPaddingBottom();
        float len = spec.tail - spec.nose;                    // visible car length in artwork units
        s = Math.min(availH * 0.91f / len, w * 0.5f / VW);
        carL = (w - VW * s) / 2f;
        // place the *car* (not the whole viewport), a little low: room ahead of it for the headlight beams
        carT = getPaddingTop() + (availH - len * s) * 0.72f - spec.nose * s;

        float cx = w / 2f;
        laneW = Math.max(2f, 2.4f * s);
        laneX1 = cx - 118 * s;
        laneX2 = cx + 118 * s;
        edgeX1 = cx - 330 * s;
        edgeX2 = cx + 330 * s;
        dash = 70 * s;
        period = 170 * s;

        headRX = carL + spec.headX * s;
        headLX = carL + (VW - spec.headX) * s;
        headY = carT + spec.headY * s;
        tailRX = carL + spec.tailX * s;
        tailLX = carL + (VW - spec.tailX) * s;
        tailY = carT + spec.tailY * s;
        lensR = 24 * s;
        tailR = 34 * s;

        // beams: from each lamp forward, widening, fading out
        float top = Math.max(0, headY - 320 * s);
        beamPath.reset();
        for (float hx : new float[]{headLX, headRX}) {
            boolean right = hx > cx;
            beamPath.moveTo(hx - 10 * s, headY - 8 * s);
            beamPath.lineTo(hx + 10 * s, headY - 8 * s);
            beamPath.lineTo(hx + (right ? 74 : 34) * s, top);
            beamPath.lineTo(hx - (right ? 34 : 74) * s, top);
            beamPath.close();
        }
        beam.setShader(new LinearGradient(0, headY, 0, top, 0x66E4F0FF, 0x00E4F0FF, Shader.TileMode.CLAMP));
        lensGlow.setShader(new RadialGradient(0, 0, lensR, new int[]{0xFFFFFFFF, 0x99D6E8FF, 0x00D6E8FF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        tailGlow.setShader(new RadialGradient(0, 0, tailR, new int[]{0xFFFF3B3B, 0x88FF2020, 0x00FF2020},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
    }

    void buildShaders() {
        int h = getHeight();
        if (h == 0) return;
        int rgb = light ? 0x000000 : 0xFFFFFF;
        Shader fade = new LinearGradient(0, 0, 0, h, new int[]{rgb, 0xFF000000 | rgb, 0xFF000000 | rgb, rgb},
                new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        lane.setShader(fade);
        edge.setShader(fade);
        int a = light ? 0x0E000000 : 0x10000000;
        road.setShader(new LinearGradient(0, 0, 0, h, new int[]{rgb, a | rgb, a | rgb, rgb},
                new float[]{0f, 0.25f, 0.75f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Re-render the car off the UI thread; the new one cross-fades in. */
    void rebuildCar() {
        if (s <= 0) return;
        final int g = ++gen, st = style, col = carColor, v = view;
        final float sc = s;
        final int pd = Math.round((v == REAR ? 16 : 14) * s);
        final boolean lb = light;
        final String pl = plate;
        final Context ctx = getContext();
        CarArt.EX.execute(() -> {
            if (g != gen) return; // superseded before it started: skip the work
            final CarArt.Set set = v == REAR ? CarArt.renderRear(ctx, st, col, sc, pd, lb, pl)
                    : CarArt.renderSet(ctx, st, col, sc, pd, lb, true, true);
            post(() -> {
                if (g != gen) return; // a newer request superseded this one
                boolean first = car == null || drawnView != v;
                oldCar = first ? null : car;
                oldPad = pad;
                oldL = drawnL;
                oldT = drawnT;
                oldView = drawnView;
                drawnL = carL;
                drawnT = carT;
                drawnView = v;
                car = set.body;
                headLit = set.head;
                tailLit = set.tail;
                litHeadY = CarArt.HEAD_Y0 * sc;
                litTailY = (v == REAR ? 0 : CarArt.spec(st).tailY0) * sc;
                if (set.rear != null) rspec = set.rear;
                pad = pd;
                swapT = first ? 1f : 0f;
                if (welcomePending) { welcomePending = false; welcomeAt = SystemClock.uptimeMillis(); }
                kick();
                invalidate();
            });
        });
    }

    // ------------------------------------------------------------------ animation

    void kick() {
        if (active && isAttachedToWindow()) postInvalidateOnAnimation();
    }

    static float approach(float v, float goal, float d) {
        if (v < goal) return Math.min(goal, v + d);
        if (v > goal) return Math.max(goal, v - d);
        return v;
    }

    static float ease3(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3 - 2 * t); }

    static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return 1f - (1f - t) * (1f - t) * (1f - t); }

    /** Smooth single bump between t0 and t0+d (seconds). */
    static float bump(float t, float t0, float d) {
        if (t < t0 || t > t0 + d) return 0f;
        return (float) Math.sin(Math.PI * (t - t0) / d);
    }

    /** Advance state by dt seconds. Returns true if another frame is needed. */
    boolean step(float dt, long now) {
        shown += (target - shown) * (1f - (float) Math.exp(-dt / 0.6f));
        if (target == 0 && shown < 0.05f) shown = 0;
        // TOP: lane passes per second; never strobes at speed
        float passes = 5f * (1f - (float) Math.exp(-shown / 14f));
        offset += passes * period * dt;
        if (offset >= period) offset %= period;
        // REAR: world units per second towards the camera
        float vw = 3.4f * (1f - (float) Math.exp(-shown / 12f));
        dashOff += vw * dt;
        if (dashOff >= RearScene.DASH_P) dashOff %= RearScene.DASH_P;
        blkOff += vw * dt;
        float wrap = RearScene.BLOCK_P * RearScene.BLOCKS;
        if (blkOff >= wrap) blkOff %= wrap;

        float mGoal = shown > 0.8f ? 1f : 0f;
        moveT = approach(moveT, mGoal, dt / 0.45f);
        boolean bOn = now < brakeUntil;
        brakeT = approach(brakeT, bOn ? 1f : 0f, dt / (bOn ? 0.08f : 0.5f)); // snaps on, fades off
        head = approach(head, night ? 1f : 0f, dt / (night ? 0.7f : 0.5f));
        tailRun = approach(tailRun, night ? 1f : 0f, dt / 0.6f);
        swapT = approach(swapT, 1f, dt / 0.45f);
        if (swapT >= 1f) oldCar = null;
        float skyGoal = night ? 1f : 0f;
        skyT = approach(skyT, skyGoal, dt / 3.2f);               // a slow, cinematic sunset / sunrise
        if (view == REAR) rear.advance(dt);

        boolean welcoming = welcomeAt >= 0 && now - welcomeAt < 1700;
        if (welcomeAt >= 0 && !welcoming) welcomeAt = -1;
        boolean ramp = headOnAt >= 0 && now - headOnAt < 900;
        if (headOnAt >= 0 && !ramp) headOnAt = -1;
        boolean popping = brakeOnAt >= 0 && now - brakeOnAt < 400;
        if (brakeOnAt >= 0 && !popping) brakeOnAt = -1;

        return shown > 0 || Math.abs(target - shown) > 0.01f || moveT != mGoal || brakeT != (bOn ? 1f : 0f) || bOn
                || head != (night ? 1f : 0f) || tailRun != (night ? 1f : 0f) || swapT < 1f || welcoming || ramp || popping
                || skyT != skyGoal;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = getDrawingTime(); // vsync time: evenly spaced motion
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        boolean more = step(dt, now);

        float wt = welcomeAt >= 0 ? (now - welcomeAt) / 1000f : 9f;
        float appear = welcomeAt >= 0 ? ease(wt / 0.6f) : 1f;
        float flash = Math.max(bump(wt, 0.55f, 0.45f), 0.8f * bump(wt, 1.05f, 0.5f));
        float pop = brakeOnAt >= 0 ? bump((now - brakeOnAt) / 1000f, 0f, 0.32f) : 0f;
        float rampT = headOnAt >= 0 ? (now - headOnAt) / 1000f : 9f;
        float lens = Math.min(1.25f, head + 0.35f * bump(rampT, 0.05f, 0.5f) + flash);
        // night: tail lamps at half strength; braking (day or night): full, with a flare as they pop on
        float lamp = Math.min(1f, Math.max(brakeT, 0.5f * tailRun) + 0.7f * flash + 0.25f * pop);
        float halo = Math.min(1f, Math.max(brakeT, 0.28f * tailRun) + 0.7f * flash);

        if (view == REAR && drawnView == REAR) {
            rear.draw(c, now, ease3(skyT), car, oldView == REAR ? oldCar : null, swapT, tailLit, pad, rspec,
                    dashOff, blkOff, moveT, head, lamp, halo, Math.min(1f, brakeT + 0.7f * flash), pop, appear);
        } else if (view == TOP && drawnView != REAR) {
            drawTop(c, appear, lens, lamp, halo, pop);
        }

        if (more && active) {
            lastFrame = now;
            postInvalidateOnAnimation();
        } else if (active && view == REAR && drawnView == REAR) {
            lastFrame = now;                       // ambient: sky keeps living, cheaply
            postInvalidateDelayed(AMBIENT_MS);
        } else {
            lastFrame = 0;
        }
    }

    void drawTop(Canvas c, float appear, float lens, float lamp, float halo, float pop) {
        int h = getHeight();
        // road
        c.drawRect(laneX1, 0, laneX2, h, road);
        float k = light ? 0.55f : 1f;
        edge.setAlpha((int) ((26 + 34 * moveT) * k));
        if (edgeX1 > 0) c.drawRect(edgeX1 - laneW / 2, 0, edgeX1 + laneW / 2, h, edge);
        if (edgeX2 < getWidth()) c.drawRect(edgeX2 - laneW / 2, 0, edgeX2 + laneW / 2, h, edge);
        lane.setAlpha((int) ((60 + 110 * moveT) * k));
        for (float y = offset - period; y < h; y += period) {
            c.drawRect(laneX1 - laneW / 2, y, laneX1 + laneW / 2, y + dash, lane);
            c.drawRect(laneX2 - laneW / 2, y, laneX2 + laneW / 2, y + dash, lane);
        }

        if (head > 0.01f) {
            beam.setAlpha((int) (255 * Math.min(1f, head * head) * (0.75f + 0.25f * moveT) * appear));
            c.drawPath(beamPath, beam);
        }

        int save = c.save();
        if (appear < 1f) {
            float sc = 0.94f + 0.06f * appear;
            c.translate(0, (1f - appear) * 14 * s);
            c.scale(sc, sc, getWidth() / 2f, carT + (spec.nose + spec.tail) / 2f * s);
        }
        boolean fading = oldCar != null && swapT < 1f && oldView == TOP;
        if (fading) {
            bmpPaint.setAlpha((int) (255 * appear * (1f - swapT * swapT)));
            c.drawBitmap(oldCar, oldL - oldPad, oldT - oldPad, bmpPaint);
            bmpPaint.setAlpha((int) (255 * appear * swapT));
        } else {
            bmpPaint.setAlpha((int) (255 * appear));
        }
        if (car != null) c.drawBitmap(car, drawnL - pad, drawnT - pad, bmpPaint);

        // lamps: the lit lamp shapes fade in over the car, plus a soft bloom around them
        float litK = fading ? appear * swapT : appear; // lamps belong to the new car
        if (lens > 0.01f) {
            if (headLit != null) {
                bmpPaint.setAlpha((int) (Math.min(1f, lens) * 255 * litK));
                c.drawBitmap(headLit, drawnL - pad, drawnT + litHeadY, bmpPaint);
            }
            lensGlow.setAlpha((int) (Math.min(1f, lens * 0.6f) * 255 * appear));
            float r = 0.85f + 0.2f * Math.min(1.25f, lens);
            dot(c, headLX, headY, r, lensR, lensGlow);
            dot(c, headRX, headY, r, lensR, lensGlow);
        }
        if (lamp > 0.01f && tailLit != null) {
            bmpPaint.setAlpha((int) (lamp * 255 * litK));
            c.drawBitmap(tailLit, drawnL - pad, drawnT + litTailY, bmpPaint);
        }
        bmpPaint.setAlpha(255);
        if (halo > 0.01f) {
            tailGlow.setAlpha((int) (halo * 255 * appear));
            float r = 0.8f + 0.25f * halo + 0.3f * pop;
            dot(c, tailLX, tailY, r, tailR, tailGlow);
            dot(c, tailRX, tailY, r, tailR, tailGlow);
        }
        c.restoreToCount(save);
    }

    /** Radial glow centred at (x,y); the shader is built once at the origin, so just translate. */
    static void dot(Canvas c, float x, float y, float scale, float r, Paint p) {
        int sv = c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.drawCircle(0, 0, r, p);
        c.restoreToCount(sv);
    }
}
