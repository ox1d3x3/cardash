package xyz.ox1de.cardash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * The screensaver cat (seen from above, like the parked car). A few seconds after the car has
 * locked, a ginger tabby wanders in from the bottom, stops beside the car, wiggles, jumps onto the
 * roof, turns round once and curls up to sleep, breathing, with little z's floating up. Tap the
 * screen and it startles, leaps off and runs away across the clock; then the dashboard comes back.
 *
 * Cost: all vector shapes (no bitmaps), a handful of paths per frame, nothing allocated while
 * drawing. Smooth 60 fps only while it's moving (about 6 s in, about 1.3 s out); asleep it ticks at
 * 10 fps, which is plenty for slow breathing and drifting z's.
 *
 * Cat units: the standing cat is ~100 long with its head towards -y; k = px per cat unit.
 */
final class SleepyCat {

    static final int FUR = 0xFFF0A04B, FUR_LIGHT = 0xFFFBC27E, FUR_DARK = 0xFFD9782C, STRIPE = 0xFFB85E22,
            SOCK = 0xFFFFF2E2, EAR = 0xFFE58E43, EAR_IN = 0xFFF7B3A8, NOSE = 0xFFF28E9A, EYE = 0xFF2A1A10;

    // arrival timeline, ms since the cat set off
    static final long WALK = 1900, TURN = 2400, CROUCH = 2900, JUMP = 3450, LAND = 3700, CIRCLE = 4900, SETTLE = 5600;
    // leaving, ms since the tap
    static final long STARTLE = 140, LEAP = 600, RUN = 650;
    static final long SLEEP_FRAME = 100;
    static final int ZZZ = 0xFF8AA0C2;   // slate blue: readable on a white roof, a dark roof and the black screen

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fur = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint z = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF oval = new RectF();
    // fur shading in cat units, built once: the canvas transform scales them with the cat
    final Shader bodySh = shade(0, 4, 30), headSh = shade(0, 0, 15.5f), curlSh = shade(0, 0, 29);

    long startAt = -1, leaveAt = -1;
    boolean gone;
    Runnable onGone;

    // the pose of the current frame
    float x, y, ang, phase, sc, squash, sw, curl, shadow, wig, sleepT;
    boolean bang;

    SleepyCat() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        z.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        z.setTextAlign(Paint.Align.CENTER);
        z.setColor(ZZZ);
    }

    static Shader shade(float cx, float cy, float r) {
        return new RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * 1.25f, new int[]{FUR_LIGHT, FUR, FUR_DARK},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    void start(long now) { startAt = now; leaveAt = -1; gone = false; onGone = null; }

    void reset() { startAt = -1; leaveAt = -1; gone = false; onGone = null; }

    boolean started() { return startAt >= 0; }

    /** On screen and not already running away? */
    boolean present() { return startAt >= 0 && !gone; }

    /** Has it actually walked on yet (not just scheduled)? */
    boolean visible(long now) { return present() && now >= startAt; }

    /** A tap: startle and run. @return false if there is no cat to scare (the caller wakes the dashboard itself). */
    boolean tap(long now, Runnable whenGone) {
        if (!visible(now)) return false;
        if (leaveAt < 0) { leaveAt = now; onGone = whenGone; }
        return true;
    }

    // ------------------------------------------------------------------ timeline

    static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3 - 2 * t); }

    static float easeOut(float t) { t = Math.max(0f, Math.min(1f, t)); return 1f - (1f - t) * (1f - t) * (1f - t); }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /**
     * Draws the cat for this frame (canvas already in the scene's coordinates).
     * @param carCx   centre of the car, px
     * @param roofY   middle of the roof, px
     * @param bayR    right-hand bay line, px
     * @param s       car scale (px per car-art unit)
     * @return ms until the next frame is wanted: 0 = next vsync, SLEEP_FRAME while asleep, -1 = nothing to draw
     */
    long draw(Canvas c, long now, float carCx, float roofY, float bayR, float s, float w, float h) {
        if (startAt < 0 || gone) return -1;
        if (now < startAt) return startAt - now;    // still on its way
        float k = s * 0.8f;
        long t = now - startAt, lt = leaveAt < 0 ? -1 : now - leaveAt;
        if (lt >= 0 && lt >= leaveTotal(t - lt)) {
            gone = true;
            Runnable r = onGone;
            onGone = null;
            if (r != null) r.run();
            return -1;
        }
        pose(t, lt, carCx, roofY, bayR, s, k, w, h);
        drawPose(c, k);
        boolean asleep = lt < 0 && t >= SETTLE;
        return asleep ? SLEEP_FRAME : 0;
    }

    long leaveTotal(long tAtTap) { return tAtTap >= JUMP ? LEAP + RUN : STARTLE + RUN; }

    void pose(long t, long lt, float carCx, float roofY, float bayR, float s, float k, float w, float h) {
        float ax = bayR + 60 * s, ay = h + 70 * k;      // enters from below, right of the bay
        float p1x = bayR + 50 * s, p1y = roofY + 28 * s; // stops beside the car
        float walkLen = (float) Math.hypot(ax - p1x, ay - p1y);
        ang = 0; phase = 0; sc = 1; squash = 1; sw = 0.5f; curl = 0; shadow = 0; wig = 0; bang = false;
        // when it was tapped, the arrival is frozen at that moment
        long at = lt < 0 ? t : t - lt;
        if (at < WALK) {
            float u = at / (float) WALK;
            float e = u < 0.75f ? u / 0.75f * 0.82f : 0.82f + 0.18f * easeOut((u - 0.75f) / 0.25f);
            x = lerp(ax, p1x, e);
            y = lerp(ay, p1y, e);
            phase = (float) Math.hypot(ax - x, ay - y) / (34 * k);
            sw = (float) Math.sin(at / 260.0);
        } else if (at < TURN) {
            float u = (at - WALK) / (float) (TURN - WALK);
            x = p1x; y = p1y;
            ang = -(float) Math.PI / 2 * ease(u);
            phase = walkLen / (34 * k) + u * 0.6f;
            sw = (float) Math.sin(at / 260.0);
        } else if (at < CROUCH) {                          // the pre-pounce wiggle
            float u = (at - TURN) / (float) (CROUCH - TURN);
            x = p1x; y = p1y;
            ang = -(float) Math.PI / 2;
            squash = 1 - 0.14f * ease(u * 2);
            sw = (float) Math.sin(at / 70.0) * 0.9f;
            wig = (float) Math.sin(at / 45.0) * 1.6f * Math.min(1f, u * 3);
        } else if (at < JUMP) {                            // up and onto the roof: bigger = closer to us
            float u = (at - CROUCH) / (float) (JUMP - CROUCH), e = ease(u);
            x = lerp(p1x, carCx, e);
            y = lerp(p1y, roofY, e);
            ang = -(float) Math.PI / 2;
            sc = 1 + 0.42f * (float) Math.sin(Math.PI * u) + 0.04f * u;
            squash = 1.1f;
            sw = -0.9f;
            shadow = u;
        } else if (at < LAND) {
            float u = (at - JUMP) / (float) (LAND - JUMP);
            x = carCx; y = roofY;
            ang = -(float) Math.PI / 2;
            sc = 1.04f;
            squash = 0.88f + 0.12f * easeOut(u);
            sw = -0.6f;
            shadow = 1;
        } else if (at < CIRCLE) {                          // cats turn round before they lie down
            float u = (at - LAND) / (float) (CIRCLE - LAND);
            float a = ease(u) * (float) Math.PI * 1.75f;
            x = carCx + (float) Math.sin(a) * 6 * k;
            y = roofY - (1 - (float) Math.cos(a)) * 4 * k;
            ang = -(float) Math.PI / 2 + a;
            phase = u * 3.2f;
            sc = 1.04f;
            sw = (float) Math.sin(at / 200.0);
            shadow = 1;
        } else if (at < SETTLE) {
            float u = (at - CIRCLE) / (float) (SETTLE - CIRCLE);
            x = carCx; y = roofY;
            ang = -(float) Math.PI / 2 + (float) Math.PI * 1.75f;
            sc = 1.04f;
            curl = ease(u);
            squash = 1 - 0.1f * curl;
            shadow = 1;
        } else {
            x = carCx; y = roofY;
            sc = 1.04f;
            curl = 1;
            shadow = 1;
        }
        sleepT = Math.max(0f, (at - SETTLE) / 1000f);

        if (lt >= 0) {                                     // startled: hop, leap off the roof, run across the clock
            float l1x = bayR + 75 * s, l1y = roofY + 70 * s, l2x = w + 140 * k, l2y = roofY + 170 * s;
            boolean onRoof = at >= JUMP;
            float fx = x, fy = y;
            if (lt < STARTLE) {
                curl = Math.max(0f, curl - lt / (float) STARTLE);
                sc *= 1 + 0.15f * (float) Math.sin(Math.PI * lt / STARTLE);
                bang = true;
            } else if (onRoof && lt < LEAP) {
                float u = (lt - STARTLE) / (float) (LEAP - STARTLE), e = ease(u);
                x = lerp(fx, l1x, e);
                y = lerp(fy, l1y, e);
                curl = 0;
                ang = (float) Math.atan2(l1x - fx, -(l1y - fy));
                sc = 1.04f + 0.4f * (float) Math.sin(Math.PI * u);
                squash = 1.12f;
                sw = -0.9f;
                shadow = 1 - u;
                bang = lt < 500;
                wig = 0;
            } else {
                float sx = onRoof ? l1x : fx, sy = onRoof ? l1y : fy;
                long t0 = onRoof ? LEAP : STARTLE;
                float u = Math.min(1f, (lt - t0) / (float) RUN), e = u * u * 0.4f + u * 0.6f;
                x = lerp(sx, l2x, e);
                y = lerp(sy, l2y, e);
                curl = 0;
                ang = (float) Math.atan2(l2x - sx, -(l2y - sy));
                phase = (float) Math.hypot(x - sx, y - sy) / (44 * k);
                sc = 1; squash = 1;
                sw = (float) Math.sin(lt / 60.0) * 0.7f;
                shadow = 0;
                wig = 0;
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    void drawPose(Canvas c, float k) {
        int sv = c.save();
        c.translate(x + wig * k * (float) Math.cos(ang), y);
        if (shadow > 0.01f) {
            fill.setShader(null);
            fill.setColor(((int) (0x38 * shadow) << 24));
            c.save();
            c.translate(5 * k, 7 * k);
            if (curl > 0.5f) {
                c.drawCircle(0, 0, 33 * k * sc, fill);
            } else {
                c.rotate((float) Math.toDegrees(ang));
                oval.set(-21 * k * sc, -40 * k * sc, 21 * k * sc, 40 * k * sc);
                c.drawOval(oval, fill);
            }
            c.restore();
        }
        c.scale(sc, sc);
        if (curl < 1f) {
            int a = Math.round(255 * (1 - curl));
            c.save();
            c.rotate((float) Math.toDegrees(ang));
            c.scale(k, k * squash);
            drawStanding(c, a);
            c.restore();
        }
        if (curl > 0f) {
            int a = Math.round(255 * curl);
            float g = 0.9f + 0.1f * curl;
            c.save();
            c.scale(k * g, k * g);
            float breathe = (1 - (float) Math.cos(2 * Math.PI * sleepT / 3.4)) / 2;
            drawCurled(c, a, breathe);
            if (sleepT > 0.8f) drawZ(c, sleepT - 0.8f, a);
            c.restore();
        }
        if (bang) {
            z.setColor(0xFFFFD54A);
            z.setAlpha(255);
            z.setTextSize(22 * k);
            z.setFakeBoldText(true);
            c.drawText("!", 0, -52 * k, z);
            z.setFakeBoldText(false);
            z.setColor(ZZZ);
        }
        c.restoreToCount(sv);
    }

    void oval(Canvas c, float cx, float cy, float rx, float ry, int color, int alpha) {
        fill.setShader(null);
        fill.setColor(color);
        fill.setAlpha(alpha * Color.alpha(color) / 255);
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(oval, fill);
    }

    void stroke(int color, float width, int alpha) {
        line.setColor(color);
        line.setAlpha(alpha * Color.alpha(color) / 255);
        line.setStrokeWidth(width);
    }

    static final class Color {
        static int alpha(int c) { return c >>> 24; }
    }

    void drawStanding(Canvas c, int a) {
        float f = (float) Math.sin(phase * Math.PI * 2) * 7;
        // paws: white socks, diagonal pairs step together
        oval(c, -14, -20 + f, 5.5f, 7, SOCK, a);
        oval(c, 14, -20 - f, 5.5f, 7, SOCK, a);
        oval(c, -14, 26 - f, 5.5f, 7, SOCK, a);
        oval(c, 14, 26 + f, 5.5f, 7, SOCK, a);
        // tail, with a darker tip
        stroke(FUR, 8.5f, a);
        path.rewind();
        path.moveTo(0, 36);
        path.cubicTo(sw * 6, 56, sw * 24, 66, sw * 18, 84);
        c.drawPath(path, line);
        stroke(FUR_DARK, 8.5f, a);
        path.rewind();
        path.moveTo(sw * 21.5f, 74);
        path.lineTo(sw * 18, 84);
        c.drawPath(path, line);
        // body + tabby stripes
        fur.setShader(bodySh);
        fur.setAlpha(a);
        oval.set(-18.5f, 4 - 35, 18.5f, 4 + 35);
        c.drawOval(oval, fur);
        stroke(STRIPE, 3, a);
        for (int i = 0; i < 4; i++) {
            float yy = -8 + 12 * i;
            path.rewind();
            path.moveTo(-12, yy + 3);
            path.quadTo(0, yy - 3, 12, yy + 3);
            c.drawPath(path, line);
        }
        c.save();
        c.translate(0, -34);
        drawHead(c, 15.5f, false, a);
        c.restore();
    }

    void drawCurled(Canvas c, int a, float breathe) {
        float R = 29 * (1 + 0.035f * breathe);
        // tail along the lower-right edge from the rump round to the chin, darker tip
        oval.set(-R - 0.5f, -R - 0.5f, R + 0.5f, R + 0.5f);
        stroke(FUR, 7.5f, a);
        c.drawArc(oval, 144f, -147.6f, false, line);
        stroke(FUR_DARK, 7.5f, a);
        c.drawArc(oval, 3.6f, -21.6f, false, line);
        // body
        fur.setShader(curlSh);
        fur.setAlpha(a);
        c.save();
        c.scale(R / 29f, R / 29f);
        c.drawCircle(0, 0, 29, fur);
        c.restore();
        // stripes across the curled back
        stroke(STRIPE, 3.2f, a);
        for (int deg = 150; deg <= 250; deg += 25) {
            double r = Math.toRadians(deg);
            float cs = (float) Math.cos(r), sn = (float) Math.sin(r);
            c.drawLine(cs * R * 0.55f, sn * R * 0.55f, cs * R * 0.93f, sn * R * 0.93f, line);
        }
        // front paws under the chin, head resting on them
        oval(c, R * 0.62f, -R * 0.12f, 5.5f, 6.5f, SOCK, a);
        oval(c, R * 0.3f, -R * 0.42f, 5.5f, 6.5f, SOCK, a);
        c.save();
        c.translate(R * 0.42f, -R * 0.62f);
        c.rotate(31.5f);
        drawHead(c, 15, true, a);
        c.restore();
    }

    /** Head at the origin, facing -y: ears, fur, forehead "M", muzzle, nose, eyes, whiskers. */
    void drawHead(Canvas c, float r, boolean asleep, int a) {
        for (int sd = -1; sd <= 1; sd += 2) {
            fill.setShader(null);
            fill.setColor(EAR);
            fill.setAlpha(a);
            path.rewind();
            path.moveTo(sd * r * 0.95f, -r * 0.15f);
            path.quadTo(sd * r * 1.15f, -r * 1.05f, sd * r * 1.02f, -r * 1.45f);
            path.quadTo(sd * r * 0.55f, -r * 1.15f, sd * r * 0.25f, -r * 0.85f);
            path.close();
            c.drawPath(path, fill);
            fill.setColor(EAR_IN);
            fill.setAlpha(a);
            path.rewind();
            path.moveTo(sd * r * 0.85f, -r * 0.35f);
            path.quadTo(sd * r * 0.98f, -r * 0.95f, sd * r * 0.92f, -r * 1.2f);
            path.quadTo(sd * r * 0.6f, -r * 1.0f, sd * r * 0.42f, -r * 0.8f);
            path.close();
            c.drawPath(path, fill);
        }
        fur.setShader(headSh);
        fur.setAlpha(a);
        c.save();
        c.scale(r / 15.5f, r / 15.5f);
        c.drawCircle(0, 0, 15.5f, fur);
        c.restore();
        stroke(STRIPE, r * 0.13f, a);
        for (int i = -1; i <= 1; i++) {
            float sx = i * 0.32f;
            c.drawLine(sx * r, -r * 0.82f, sx * r * 0.85f, -r * 0.42f, line);
        }
        oval(c, 0, r * 0.42f, r * 0.42f, r * 0.3f, FUR_LIGHT, a);
        oval(c, 0, r * 0.3f, r * 0.13f, r * 0.09f, NOSE, a);
        if (asleep) {
            stroke(EYE, r * 0.1f, a);
            for (int sd = -1; sd <= 1; sd += 2) {
                path.rewind();
                path.moveTo(sd * r * 0.55f, -r * 0.02f);
                path.quadTo(sd * r * 0.38f, r * 0.16f, sd * r * 0.2f, -r * 0.02f);
                c.drawPath(path, line);
            }
        } else {
            oval(c, -r * 0.38f, -r * 0.05f, r * 0.13f, r * 0.17f, EYE, a);
            oval(c, r * 0.38f, -r * 0.05f, r * 0.13f, r * 0.17f, EYE, a);
        }
        stroke(0xBFFFFFFF, 0.9f, a);
        for (int sd = -1; sd <= 1; sd += 2)
            for (int i = -1; i <= 1; i++)
                c.drawLine(sd * r * 0.45f, r * 0.42f, sd * r * 1.25f, r * (0.32f + 0.16f * i), line);
    }

    /** Three z's drifting up from the sleeping head, each fading in and out. */
    void drawZ(Canvas c, float t, int a) {
        for (int i = 0; i < 3; i++) {
            float u = (t / 2.6f + i / 3f) % 1f;
            float al = (float) Math.sin(Math.PI * u);
            z.setAlpha(Math.round(a * al * 0.92f));
            z.setTextSize(11 + 8 * u);
            c.drawText("z", 16 + 12 * u + 3 * (float) Math.sin(u * 6), -30 - 30 * u, z);
        }
    }
}
