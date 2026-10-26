package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Top-down car on a road. Lane markings scroll with GPS speed, brake lights glow when
 * slowing/stopped, headlight beams at night.
 *
 * Performance rules (2 GB head units):
 *  - The car is rasterised ONCE into a bitmap (on size / colour change). A frame is one
 *    drawBitmap plus a handful of rects, all GPU-composited.
 *  - Frames are requested only while something visibly changes. Parked and steady =
 *    zero frames, zero CPU. Paused activity = zero frames.
 *  - No allocations in onDraw.
 */
public class DriveView extends View {

    // Artwork viewport (see res/drawable/car_*.xml): 200 x 400, car spans y 9..388.
    static final float VW = 200f, VH = 400f;

    final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint lane = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path beamPath = new Path();

    Bitmap car;
    int carColor = 0xFFF2F3F5;
    float s;              // px per artwork unit
    float carL, carT;     // top-left of artwork box in px
    int pad;              // shadow margin inside the bitmap
    float laneW, dash, period, laneX1, laneX2, edgeX1, edgeX2;
    float tailLX, tailRX, tailY, glowR;

    // motion state
    float target, shown, offset;
    float moveT, brakeT;
    boolean everMoved, night, active, light;
    long brakeUntil, lastFrame, lastSpeedAt;
    float lastMs;

    public DriveView(Context c) { this(c, null); }

    public DriveView(Context c, AttributeSet a) {
        super(c, a);
        lane.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.FILL);
        beam.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ public API

    public void setCarColor(int c) {
        if (c == carColor && car != null) return;
        carColor = c;
        rebuildCar();
        invalidate();
    }

    /** Speed in m/s, or negative when there is no GPS fix. */
    public void setSpeed(float ms) {
        long now = SystemClock.uptimeMillis();
        if (ms < 0) {
            target = 0;
            everMoved = false; // lost GPS (tunnel, car park): don't fake brake lights
        } else {
            if (lastSpeedAt > 0) {
                float dt = (now - lastSpeedAt) / 1000f;
                if (dt > 0.25f && dt < 3f && lastMs > 1.5f && (lastMs - ms) / dt > 1.3f) {
                    brakeUntil = now + 900; // decelerating harder than ~0.13 g
                }
            }
            if (ms > 2f) everMoved = true;
            target = ms;
            lastMs = ms;
            lastSpeedAt = now;
        }
        kick();
    }

    /** Light theme: dark lane paint and a softer shadow. */
    public void setLight(boolean l) {
        if (l == light) return;
        light = l;
        buildShaders();
        rebuildCar();
        invalidate();
    }

    public void setNight(boolean n) {
        if (n == night) return;
        night = n;
        invalidate();
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
        float availH = h - getPaddingTop() - getPaddingBottom();
        // leave road visible ahead/behind (motion reads better), and never wider than 42% of
        // the view so lane lines always fit beside it
        s = Math.min(availH * 0.8f / VH, w * 0.42f / VW);
        carL = (w - VW * s) / 2f;
        carT = getPaddingTop() + (availH - VH * s) / 2f;

        float cx = w / 2f;
        laneW = Math.max(2f, 2.4f * s);
        laneX1 = cx - 118 * s;
        laneX2 = cx + 118 * s;
        edgeX1 = cx - 330 * s;
        edgeX2 = cx + 330 * s;
        dash = 70 * s;
        period = 170 * s;

        buildShaders();

        // headlight beams: from the lamps (artwork 45,29 and 155,29) up to the top edge
        float hy = carT + 22 * s, top = Math.max(0, hy - 300 * s);
        beamPath.reset();
        for (float hx : new float[]{carL + 45 * s, carL + 155 * s}) {
            float dir = hx < cx ? -1 : 1;
            beamPath.moveTo(hx - 12 * s, hy);
            beamPath.lineTo(hx + 12 * s, hy);
            beamPath.lineTo(hx + (dir > 0 ? 70 : 30) * s, top);
            beamPath.lineTo(hx - (dir > 0 ? 30 : 70) * s, top);
            beamPath.close();
        }
        beam.setShader(new LinearGradient(0, hy, 0, top, 0x55DCEBFF, 0x00DCEBFF, Shader.TileMode.CLAMP));

        tailLX = carL + 30 * s;
        tailRX = carL + 170 * s;
        tailY = carT + 352 * s;
        glowR = 38 * s;
        glow.setShader(new RadialGradient(0, 0, glowR, 0xDDFF2B2B, 0x00FF2B2B, Shader.TileMode.CLAMP));

        rebuildCar();
    }

    void buildShaders() {
        int h = getHeight();
        if (h == 0) return;
        int rgb = light ? 0x000000 : 0xFFFFFF;
        int faint = rgb, solid = 0xFF000000 | rgb;
        Shader fade = new LinearGradient(0, 0, 0, h,
                new int[]{faint, solid, solid, faint}, new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        lane.setShader(fade);
        edge.setShader(fade);
    }

    void rebuildCar() {
        if (s <= 0) return;
        int cw = Math.round(VW * s), ch = Math.round(VH * s);
        pad = Math.round(10 * s);
        Bitmap b = Bitmap.createBitmap(cw + pad * 2, ch + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(pad, pad);

        // soft ground shadow (software canvas: blur is fine, it runs once)
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(light ? 0x48000000 : 0xB0000000);
        sh.setMaskFilter(new BlurMaskFilter(9 * s, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(20 * s, 12 * s, 180 * s, 388 * s), 40 * s, 40 * s, sh);

        layer(c, R.drawable.car_tyres, 0, cw, ch);
        layer(c, R.drawable.car_body, carColor, cw, ch);
        layer(c, R.drawable.car_detail, 0, cw, ch);

        b.prepareToDraw(); // upload to GPU now, not on first frame
        car = b;
    }

    void layer(Canvas c, int res, int tint, int w, int h) {
        Drawable d = getContext().getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        if (tint != 0) {
            d.setTint(tint);
            d.setTintMode(PorterDuff.Mode.MULTIPLY); // keeps the grey shading under any paint colour
        }
        d.setBounds(0, 0, w, h);
        d.draw(c);
    }

    // ------------------------------------------------------------------ animation

    void kick() {
        if (active && isAttachedToWindow()) postInvalidateOnAnimation();
    }

    boolean brakeGoal(long now) {
        return now < brakeUntil || (everMoved && target < 0.5f);
    }

    /** Advance state by dt seconds. Returns true if another frame is needed. */
    boolean step(float dt, long now) {
        // ease displayed speed toward GPS speed (GPS is 1-2 Hz, the screen is 60 Hz)
        shown += (target - shown) * (1f - (float) Math.exp(-dt / 0.6f));
        if (target == 0 && shown < 0.05f) shown = 0;

        // lane scroll: passes/second rises with speed but saturates, so it never strobes
        float passes = 5f * (1f - (float) Math.exp(-shown / 14f));
        offset += passes * period * dt;
        if (offset >= period) offset %= period;

        float mGoal = shown > 0.8f ? 1f : 0f;
        moveT = approach(moveT, mGoal, dt / 0.45f);

        boolean bOn = brakeGoal(now);
        brakeT = approach(brakeT, bOn ? 1f : 0f, dt / (bOn ? 0.12f : 0.35f));

        boolean timedBrake = now < brakeUntil;
        return shown > 0 || Math.abs(target - shown) > 0.01f || moveT != mGoal
                || brakeT != (bOn ? 1f : 0f) || timedBrake;
    }

    static float approach(float v, float goal, float d) {
        if (v < goal) return Math.min(goal, v + d);
        if (v > goal) return Math.max(goal, v - d);
        return v;
    }

    @Override
    protected void onDraw(Canvas c) {
        // vsync timestamp of this frame (same clock as uptimeMillis): evenly spaced, so motion
        // stays smooth even when a draw happens to start a little late
        long now = getDrawingTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        boolean more = step(dt, now);

        int h = getHeight();

        // road edges (static) and lane dashes (scrolling toward the rear)
        float k = light ? 0.55f : 1f;
        int a = (int) ((26 + 34 * moveT) * k);
        edge.setAlpha(a);
        if (edgeX1 > 0) c.drawRect(edgeX1 - laneW / 2, 0, edgeX1 + laneW / 2, h, edge);
        if (edgeX2 < getWidth()) c.drawRect(edgeX2 - laneW / 2, 0, edgeX2 + laneW / 2, h, edge);

        lane.setAlpha((int) ((60 + 110 * moveT) * k));
        for (float y = offset - period; y < h; y += period) {
            c.drawRect(laneX1 - laneW / 2, y, laneX1 + laneW / 2, y + dash, lane);
            c.drawRect(laneX2 - laneW / 2, y, laneX2 + laneW / 2, y + dash, lane);
        }

        if (night) {
            beam.setAlpha((int) (150 + 105 * moveT));
            c.drawPath(beamPath, beam);
        }

        if (car != null) c.drawBitmap(car, carL - pad, carT - pad, bmpPaint);

        float g = Math.max(brakeT, night ? 0.3f : 0f);
        if (g > 0.01f) {
            glow.setAlpha((int) (255 * g));
            c.save();
            c.translate(tailLX, tailY);
            c.drawCircle(0, 0, glowR, glow);
            c.restore();
            c.save();
            c.translate(tailRX, tailY);
            c.drawCircle(0, 0, glowR, glow);
            c.restore();
        }

        if (more && active) {
            lastFrame = now;
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }
}
