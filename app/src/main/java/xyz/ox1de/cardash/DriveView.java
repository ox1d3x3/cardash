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
 *  - slowing down: the stop lamps glow softly, brighter the quicker the car slows (a smoothed
 *    deceleration from GPS), and fade out once it stops slowing
 *  - braking (from ~1.3 m/s^2): they pop on at full strength, held while it keeps slowing that
 *    hard, then fade; the bloom flares and (rear view) the car's tail lifts a touch as they bite
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
    SkyView sky;          // chase-cam sky, its own layer underneath this view

    Bitmap car, oldCar, headLit, tailLit; // TOP: headLit / tailLit = lamps on. REAR: headLit = stop lamp
    int carColor = 0xFFF2F3F5, style = CarArt.HATCH, view = TOP, bg = 0xFF131519;
    String plate = "";
    CarArt.Badge badge;  // the owner's boot-lid badge (chase cam only)
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
    // gentle slowing: smoothed deceleration (m/s^2) from successive fixes -> a soft stop-lamp glow
    float decel, prevMs, slowGoal, slowT;
    long prevAt, brakeLogAt;

    static final float HARD = 1.3f;        // m/s^2 (~4.7 km/h per second): full brake lights
    static final float HOLD = 0.8f;        // m/s^2: once braking, they stay on while it slows at least this hard
    static final float SLOW_FROM = 0.45f;  // m/s^2 (~1.6 km/h per second): the soft glow starts
    float skyT;                 // chase-cam sky: 0 = day .. 1 = night, animated (sunset / sunrise)
    boolean skyInit;

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
            boolean still = target == 0 && shown == 0 && slowT == 0;
            target = 0;
            refAt = 0;
            prevAt = 0;
            decel = 0;
            slowGoal = 0;
            if (!still) kick();
            return;
        }
        // Speed changes are measured over >= 0.4 s windows, so 1 Hz and 10 Hz GPS behave the same.
        // > 12 m/s^2 is impossible for a car: a GPS glitch, ignored.
        //
        // Braking: a drop of >= 1.3 m/s^2 (~4.7 km/h per second) in one window lights the stop lamps
        // at full strength. They stay on while each new reading still shows >= 0.8 m/s^2, so braking
        // that hovers near the threshold (and GPS jitter) can't make them blink off and pop again.
        if (refAt == 0 || now - refAt > 3000) {
            refAt = now;
            refMs = ms;
        } else if (now - refAt >= 400) {
            float dt = (now - refAt) / 1000f, drop = refMs - ms, d = drop / dt;
            boolean braking = now < brakeUntil;
            if (refMs > 2.5f && d < 12f && ((drop > 0.5f && d > HARD) || (braking && d > HOLD))) {
                if (!braking) {                                   // a fresh press: pop
                    brakeOnAt = now;
                    if (Diag.on != null && now - brakeLogAt > 2000) {
                        brakeLogAt = now;
                        Diag.log("drive", String.format(java.util.Locale.US, "braking %.1f m/s² at %.0f km/h", d, refMs * 3.6f));
                    }
                }
                brakeUntil = now + Math.max(900, (long) (dt * 1000) + 400); // held until the next reading
            }
            refAt = now;
            refMs = ms;
        }
        // Slowing: the deceleration between fixes, smoothed over ~1 s (one noisy fix can't light it;
        // the jitter of a GPS speed at a steady speed stays below the threshold), sets a soft glow that
        // grows with how quickly the car slows. Below ~5 km/h the reading is mostly noise: no glow.
        // (Tuned on simulated 1 Hz GPS: steady cruising with normal jitter stays dark, ~0.8 m/s^2 of
        // slowing glows a clear low red, braking near the threshold pops once, not twice.)
        if (prevAt == 0 || now - prevAt > 3000) {
            prevAt = now;
            prevMs = ms;
            decel = 0;
        } else if (now - prevAt >= 250) {
            float dt = (now - prevAt) / 1000f, d = (prevMs - ms) / dt;
            if (d > 12f || d < -12f) d = 0;
            decel += (d - decel) * (1f - (float) Math.exp(-dt / 0.9f));
            boolean moving = Math.max(prevMs, ms) > 1.5f;
            // rises early (a curve, not a straight line), so ordinary slowing already reads clearly
            float k = Math.max(0f, Math.min(1f, (decel - SLOW_FROM) / (HARD - SLOW_FROM)));
            slowGoal = moving && k > 0 ? (float) Math.pow(k, 0.7) : 0f;
            prevAt = now;
            prevMs = ms;
        }
        // parked and staying parked: nothing to redraw (this used to cost a frame per GPS fix)
        boolean still = ms == 0 && target == 0 && shown == 0 && now >= brakeUntil && slowGoal == 0 && slowT == 0;
        target = ms;
        if (!still) kick();
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

    /** Boot-lid badge for the chase cam; re-renders only if it actually changed. */
    public void setBadge(CarArt.Badge b) {
        int was = badge == null ? -1 : badge.ver, now = b == null ? -1 : b.ver;
        badge = b;
        if (was != now && view == REAR && car != null) rebuildCar();
    }

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
        if (!a) { frames.cancel(); Pace.measuring = false; }
        syncSky();
        if (a) kick();
    }

    public void setSky(SkyView s) {
        sky = s;
        if (view == REAR && rear.skyDay != null) s.setScene(rear);
        syncSky();
    }

    /** The sky layer shows (and runs) only in the chase cam, and only while this view is active. */
    void syncSky() {
        if (sky == null) return;
        boolean rearView = view == REAR;
        int vis = rearView ? VISIBLE : GONE;
        if (sky.getVisibility() != vis) sky.setVisibility(vis);
        sky.setRunning(rearView && active);
    }

    // ------------------------------------------------------------------ geometry

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        if (Diag.on != null) {   // a resize rebuilds the scene: log what moved, to find needless ones
            StringBuilder b = new StringBuilder("car view ").append(w).append('x').append(h)
                    .append(" (was ").append(ow).append('x').append(oh).append(')');
            android.view.ViewParent box = getParent();
            if (box instanceof View && ((View) box).getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup col = (android.view.ViewGroup) ((View) box).getParent();
                b.append(", column rows");
                for (int i = 0; i < col.getChildCount(); i++) b.append(' ').append(col.getChildAt(i).getHeight());
            }
            b.append(", window ").append(getRootView().getHeight());
            Diag.log("ui", b.toString());
        }
        layoutGeometry();
        buildShaders();
        rebuildCar();
    }

    void layoutGeometry() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (view == REAR) {
            s = rear.layout(w, h, getPaddingTop(), getPaddingBottom(), light, bg, getResources().getDisplayMetrics().density);
            if (sky != null) sky.setScene(rear);
            syncSky();
            return;
        }
        syncSky();
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
        final CarArt.Badge bd = badge;
        final Context ctx = getContext();
        CarArt.EX.execute(() -> {
            if (g != gen) return; // superseded before it started: skip the work
            final CarArt.Set set = v == REAR ? CarArt.renderRear(ctx, st, col, sc, pd, lb, pl, bd)
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
                litHeadY = Math.round(CarArt.HEAD_Y0 * sc);
                litTailY = Math.round((v == REAR ? 0 : CarArt.spec(st).tailY0) * sc);
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

    /** Next animation frame: every refresh, or every other one on a unit that can't hold 60 fps. */
    final Pace.Frames frames = new Pace.Frames(this);

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
        // the slowing glow eases towards its level: up in ~0.3 s, down in ~0.6 s (no steps between fixes)
        if (slowT != slowGoal) {
            slowT += (slowGoal - slowT) * (1f - (float) Math.exp(-dt / (slowGoal > slowT ? 0.12f : 0.25f)));
            if (Math.abs(slowGoal - slowT) < 0.004f) slowT = slowGoal;
        }
        head = approach(head, night ? 1f : 0f, dt / (night ? 0.7f : 0.5f));
        tailRun = approach(tailRun, night ? 1f : 0f, dt / 0.6f);
        swapT = approach(swapT, 1f, dt / 0.45f);
        if (swapT >= 1f) oldCar = null;
        float skyGoal = night ? 1f : 0f;
        skyT = approach(skyT, skyGoal, dt / 3.2f);               // a slow, cinematic sunset / sunrise

        boolean welcoming = welcomeAt >= 0 && now - welcomeAt < 1700;
        if (welcomeAt >= 0 && !welcoming) welcomeAt = -1;
        boolean ramp = headOnAt >= 0 && now - headOnAt < 900;
        if (headOnAt >= 0 && !ramp) headOnAt = -1;
        boolean popping = brakeOnAt >= 0 && now - brakeOnAt < 400;
        if (brakeOnAt >= 0 && !popping) brakeOnAt = -1;

        return shown > 0 || Math.abs(target - shown) > 0.01f || moveT != mGoal || brakeT != (bOn ? 1f : 0f) || bOn
                || head != (night ? 1f : 0f) || tailRun != (night ? 1f : 0f) || swapT < 1f || welcoming || ramp || popping
                || skyT != skyGoal || slowT != slowGoal;
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
        // night: tail lamps at half strength. Slowing: a soft glow on top, up to just over half way to
        // full. Braking (day or night): full, with a flare as they pop on.
        float run = 0.5f * tailRun, runHalo = 0.28f * tailRun;
        float slowLamp = run + (1f - run) * 0.6f * slowT;
        float slowHalo = runHalo + (1f - runHalo) * 0.45f * slowT;
        float lamp = Math.min(1f, Math.max(brakeT, slowLamp) + 0.7f * flash + 0.25f * pop);
        float halo = Math.min(1f, Math.max(brakeT, slowHalo) + 0.7f * flash);
        float roadGlow = Math.min(1f, Math.max(brakeT, 0.35f * slowT) + 0.7f * flash);

        if (view == REAR && drawnView == REAR) {
            float n = ease3(skyT);
            if (sky != null) sky.setNight(n);
            rear.draw(c, now, n, car, oldView == REAR ? oldCar : null, swapT, tailLit, pad, rspec,
                    dashOff, blkOff, moveT, head, lamp, halo, roadGlow, pop, appear);
        } else if (view == TOP && drawnView != REAR) {
            drawTop(c, appear, lens, lamp, halo, pop);
        }

        if (more && active) {
            lastFrame = now;
            Pace.measuring = shown > 0.5f;         // the road is moving: these frames tell Auto how the unit copes
            frames.next(now);
        } else {
            lastFrame = 0;                         // still: no frames (the sky layer keeps itself alive)
            Pace.measuring = false;
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
            c.drawBitmap(oldCar, Math.round(oldL) - oldPad, Math.round(oldT) - oldPad, bmpPaint);
            bmpPaint.setAlpha((int) (255 * appear * swapT));
        } else {
            bmpPaint.setAlpha((int) (255 * appear));
        }
        // whole pixels: drawn 1:1 the car stays razor sharp (a fractional position resamples it)
        float bx = Math.round(drawnL) - pad, by = Math.round(drawnT);
        if (car != null) c.drawBitmap(car, bx, by - pad, bmpPaint);

        // lamps: the lit lamp shapes fade in over the car, plus a soft bloom around them
        float litK = fading ? appear * swapT : appear; // lamps belong to the new car
        if (lens > 0.01f) {
            if (headLit != null) {
                bmpPaint.setAlpha((int) (Math.min(1f, lens) * 255 * litK));
                c.drawBitmap(headLit, bx, by + litHeadY, bmpPaint);
            }
            lensGlow.setAlpha((int) (Math.min(1f, lens * 0.6f) * 255 * appear));
            float r = 0.85f + 0.2f * Math.min(1.25f, lens);
            dot(c, headLX, headY, r, lensR, lensGlow);
            dot(c, headRX, headY, r, lensR, lensGlow);
        }
        if (lamp > 0.01f && tailLit != null) {
            bmpPaint.setAlpha((int) (lamp * 255 * litK));
            c.drawBitmap(tailLit, bx, by + litTailY, bmpPaint);
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
