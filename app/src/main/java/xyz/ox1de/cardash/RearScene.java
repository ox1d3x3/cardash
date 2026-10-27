package xyz.ox1de.cardash;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * "Chase cam" view: the car from behind on a road running to the horizon, under a sky:
 *  - day: blue sky, the sun with its glow, soft clouds drifting by
 *  - night: deep blue-black sky, a crescent moon, twinkling stars, the odd shooting star
 *  - at 6 pm / 5 am the sun sets (warm dusk glow on the horizon) and the moon rises, or back
 * Lane dashes rush towards you and roadside buildings stream past with GPS speed.
 *
 * World: X across the road (lane lines at +-1), z = distance ahead of the camera (the car's rear
 * contact patch is at z = 1), Y = height (camera at HC). A ground point projects to
 *   x = cx + X * L / z,   y = hY + D / z
 * which is exact perspective for a flat road.
 *
 * Cost: everything that doesn't move (sky gradients, stars, sun, moon, clouds) is painted once into
 * small bitmaps when the size changes; a frame is a few bitmap draws plus ~40 flat convex shapes.
 * Paints and shaders are built once; nothing is allocated while drawing.
 */
final class RearScene {

    static final float HC = 1f;                              // camera height
    static final float LANE_X = 1f, EDGE_X = 2.7f, ROAD_X = 3.2f, BLOCK_X = 4.6f, BLOCK_X2 = 7.6f;
    static final float DASH_P = 2.6f, DASH_L = 1.15f, DASH_W = 0.035f, EDGE_W = 0.05f;
    static final float BLOCK_P = 3.6f, BLOCK_L = 2.3f, BLOCKS = 32;   // block heights repeat every 32 blocks
    static final float Z_FAR = 60f, Z_DASH = 30f, Z_BLOCK = 34f;

    // palette: day / night
    static final int SKY_TOP_D = 0xFF2F7BDB, SKY_MID_D = 0xFF6FACEC, HORIZON_D = 0xFFD9E9F6;
    static final int SKY_TOP_N = 0xFF02040B, SKY_MID_N = 0xFF081129, HORIZON_N = 0xFF1B2950;
    static final int GROUND_D = 0xFFBFC6CE, GROUND_N = 0xFF0A0D13;
    static final int ROAD_D = 0xFF8C949E, ROAD_N = 0xFF151920;
    static final int FACE_D = 0xFFAEB9C6, FRONT_D = 0xFFD5DCE5, FACE_N = 0xFF0D121C, FRONT_N = 0xFF141B28;
    static final int WIN_D = 0xFF8297AE, WIN_N = 0xFFFFC879;         // window glass by day, lit windows at night

    float w, h, cx, hY, D, L, s, carL, carT, yG, zNear, top, bottom, skyH, dens;
    int bg;
    boolean light;

    final Paint lane = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint groundD = new Paint(), groundN = new Paint(), hazeD = new Paint(), hazeN = new Paint(), dusk = new Paint();
    final Paint roadD = new Paint(Paint.ANTI_ALIAS_FLAG), roadN = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG), shoot = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tailGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint roadRed = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Path roadPath = new Path(), edgePath = new Path(), beamPath = new Path(), cornerPath = new Path(), q = new Path();
    float glowR;

    // sky (built once per size)
    Bitmap skyDay, skyNight, clouds, sun, moon;
    int skyW, skyHt;
    float sunX, sunY, moonX, moonY, sunR, moonR;
    final float[][] twinkle = new float[3][];
    long nextShoot = -1, shootAt = -1;
    float shX, shY, shDx, shDy;
    int seed = 12345;

    float sx(float x, float z) { return cx + x * L / z; }
    float sy(float z) { return hY + D / z; }
    float syH(float y, float z) { return hY + D * (1f - y / HC) / z; }

    /** @return the car scale (px per artwork unit). */
    float layout(int vw, int vh, int padTop, int padBottom, boolean lightTheme, int bgColor, float density) {
        w = vw; h = vh; top = padTop; bottom = vh - padBottom;
        light = lightTheme;
        bg = bgColor;
        dens = density;
        float availH = bottom - top;
        cx = w / 2f;
        s = Math.min(w * 0.74f / CarArt.RW, availH * 0.56f / CarArt.RH);
        yG = bottom - availH * 0.035f;
        hY = top + availH * 0.29f;            // horizon: the top ~30 % is sky
        skyH = hY - top;
        D = yG - hY;
        L = 225f * s;
        carL = cx - CarArt.RW / 2f * s;
        carT = yG - CarArt.R_GROUND * s;
        zNear = D / Math.max(1f, bottom - hY) * 0.98f;
        glowR = 40f * s;

        // lane paint and road edges fade in out of the haze
        Shader fadeIn = new LinearGradient(0, hY, 0, hY + D * 0.35f, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP);
        lane.setShader(fadeIn);
        edge.setShader(fadeIn);
        quadPath(roadPath, -ROAD_X, ROAD_X, -ROAD_X, ROAD_X, zNear, Z_FAR);
        edgePath.reset();
        for (float x : new float[]{-EDGE_X, EDGE_X}) addQuad(edgePath, x - EDGE_W, x + EDGE_W, x - EDGE_W, x + EDGE_W, zNear, Z_FAR);

        groundD.setShader(new LinearGradient(0, hY, 0, bottom, HORIZON_D, GROUND_D, Shader.TileMode.CLAMP));
        groundN.setShader(new LinearGradient(0, hY, 0, bottom, HORIZON_N, GROUND_N, Shader.TileMode.CLAMP));
        roadD.setShader(new LinearGradient(0, hY, 0, bottom, new int[]{HORIZON_D, ROAD_D, 0xFF7C848E}, new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        roadN.setShader(new LinearGradient(0, hY, 0, bottom, new int[]{HORIZON_N, ROAD_N, 0xFF1A1E26}, new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        float hz = hY + D * 0.16f;
        hazeD.setShader(new LinearGradient(0, hY - 2, 0, hz, HORIZON_D, HORIZON_D & 0x00FFFFFF, Shader.TileMode.CLAMP));
        hazeN.setShader(new LinearGradient(0, hY - 2, 0, hz, HORIZON_N, HORIZON_N & 0x00FFFFFF, Shader.TileMode.CLAMP));
        dusk.setShader(new LinearGradient(0, hY - skyH * 0.75f, 0, hY + 2, new int[]{0x00FF7A3C, 0x99FF8A4A, 0xFFFFB067},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));

        // night: the headlights' pool of light on the road ahead of the car
        quadPath(beamPath, -0.7f, 0.7f, -1.9f, 1.9f, 1.8f, 10f);
        beam.setShader(new LinearGradient(0, sy(1.8f), 0, sy(10f), 0x60E8F2FF, 0x00E8F2FF, Shader.TileMode.CLAMP));

        tailGlow.setShader(new RadialGradient(0, 0, glowR, new int[]{0xFFFF3B3B, 0x88FF2020, 0x00FF2020},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        roadRed.setShader(new RadialGradient(0, 0, glowR, new int[]{0x99FF2A2A, 0x33FF2020, 0x00FF2020},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        // rounded "window": the card colour fills just the corners (anti-aliased, unlike a clip)
        float r = 16 * dens;
        cornerPath.reset();
        cornerPath.setFillType(Path.FillType.EVEN_ODD);
        cornerPath.addRect(0, top, w, bottom, Path.Direction.CW);
        cornerPath.addRoundRect(new RectF(0, top, w, bottom), r, r, Path.Direction.CW);
        corner.setColor(bg);

        star.setStyle(Paint.Style.STROKE);
        star.setStrokeCap(Paint.Cap.ROUND);
        star.setColor(0xFFFFFFFF);
        star.setStrokeWidth(Math.max(1.5f, 1.7f * dens));
        shoot.setStrokeCap(Paint.Cap.ROUND);
        shoot.setColor(0xFFFFFFFF);

        buildSky();
        return s;
    }

    float rnd() {                                  // tiny deterministic PRNG (same sky every time)
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65535f;
    }

    /** Paint the sky layers once per size. */
    void buildSky() {
        int sw = Math.max(1, Math.round(w)), sh = Math.max(1, Math.round(skyH + 4));
        if (skyDay != null && sw == skyW && sh == skyHt) return;
        skyW = sw; skyHt = sh;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float t0 = top;                                     // sky bitmaps are drawn at y = top

        skyDay = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(skyDay);
        p.setShader(new LinearGradient(0, 0, 0, sh, new int[]{SKY_TOP_D, SKY_MID_D, HORIZON_D}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, p);

        skyNight = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        c = new Canvas(skyNight);
        p.setShader(new LinearGradient(0, 0, 0, sh, new int[]{SKY_TOP_N, SKY_MID_N, HORIZON_N}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, p);
        p.setShader(null);
        // faint stars, baked in (the bright ones twinkle on top, per frame)
        seed = 7;
        for (int i = 0; i < 90; i++) {
            float x = rnd() * sw, y = rnd() * sh * 0.92f, b = rnd();
            p.setColor(0xFFFFFFFF);
            p.setAlpha((int) (40 + 120 * b * (1f - y / sh)));
            c.drawCircle(x, y, (0.5f + 0.7f * b) * dens, p);
        }
        for (int g = 0; g < 3; g++) {
            float[] pts = new float[2 * 9];
            for (int i = 0; i < 9; i++) {
                pts[2 * i] = 8 * dens + rnd() * (sw - 16 * dens);
                pts[2 * i + 1] = t0 + 4 * dens + rnd() * sh * 0.72f;
            }
            twinkle[g] = pts;
        }

        // sun: white-hot disc in a big warm glow
        sunR = Math.max(6f, skyH * 0.12f);
        float sg = skyH * 0.95f;
        sun = Bitmap.createBitmap(Math.round(sg * 2), Math.round(sg * 2), Bitmap.Config.ARGB_8888);
        c = new Canvas(sun);
        p.setShader(new RadialGradient(sg, sg, sg, new int[]{0xCCFFF6D6, 0x55FFE9A8, 0x14FFE0A0, 0x00FFE0A0},
                new float[]{0f, sunR * 1.6f / sg, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sg, sg, sg, p);
        p.setShader(new RadialGradient(sg - sunR * 0.25f, sg - sunR * 0.25f, sunR, new int[]{0xFFFFFFFF, 0xFFFFF7DC, 0xFFFFE9A6},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sg, sg, sunR, p);
        sun.prepareToDraw();

        // moon: crescent with a soft glow, faint earthshine on the dark side, a few craters
        moonR = Math.max(5f, skyH * 0.1f);
        float mg = skyH * 0.6f;
        moon = Bitmap.createBitmap(Math.round(mg * 2), Math.round(mg * 2), Bitmap.Config.ARGB_8888);
        c = new Canvas(moon);
        p.setShader(new RadialGradient(mg, mg, mg, new int[]{0x55C8D8FF, 0x18A8C0FF, 0x00A8C0FF},
                new float[]{moonR / mg, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(mg, mg, mg, p);
        p.setShader(null);
        p.setColor(0x26C9D4E8);
        c.drawCircle(mg, mg, moonR, p);                                     // earthshine
        Path disc = new Path(), bite = new Path();
        disc.addCircle(mg, mg, moonR, Path.Direction.CW);
        bite.addCircle(mg + moonR * 0.55f, mg - moonR * 0.18f, moonR * 0.92f, Path.Direction.CW);
        disc.op(bite, Path.Op.DIFFERENCE);
        p.setShader(new LinearGradient(mg - moonR, mg - moonR, mg + moonR, mg + moonR, 0xFFFFFDF4, 0xFFE2DDCB, Shader.TileMode.CLAMP));
        c.drawPath(disc, p);
        p.setShader(null);
        p.setColor(0x22887F66);
        c.save();
        c.clipPath(disc);
        c.drawCircle(mg - moonR * 0.45f, mg - moonR * 0.25f, moonR * 0.16f, p);
        c.drawCircle(mg - moonR * 0.6f, mg + moonR * 0.3f, moonR * 0.11f, p);
        c.drawCircle(mg - moonR * 0.2f, mg + moonR * 0.62f, moonR * 0.09f, p);
        c.restore();
        moon.prepareToDraw();

        // clouds: a few soft puffs, painted once with a blur, then just slid across
        clouds = Bitmap.createBitmap(sw, Math.max(1, Math.round(sh * 0.75f)), Bitmap.Config.ARGB_8888);
        c = new Canvas(clouds);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, 3f * dens), BlurMaskFilter.Blur.NORMAL));
        seed = 99;
        for (int i = 0; i < 4; i++) {
            float ccx = sw * (0.12f + 0.25f * i + 0.06f * rnd()), ccy = clouds.getHeight() * (0.3f + 0.4f * rnd());
            float sc = skyH * (0.10f + 0.05f * rnd());
            p.setColor(0x30556F8C);                          // soft shade underneath
            c.drawOval(ccx - sc * 2.3f, ccy + sc * 0.1f, ccx + sc * 2.3f, ccy + sc * 0.95f, p);
            p.setColor(0xE6FFFFFF);
            for (int k = 0; k < 5; k++) {
                float ox = (k - 2) * sc * 0.85f, oy = -(float) Math.cos((k - 2) * 0.7f) * sc * 0.45f;
                c.drawCircle(ccx + ox, ccy + oy, sc * (0.7f + 0.35f * (1 - Math.abs(k - 2) / 2f)), p);
            }
        }
        p.setMaskFilter(null);
        clouds.prepareToDraw();
        skyDay.prepareToDraw();
        skyNight.prepareToDraw();

        sunX = w * 0.80f; sunY = top + skyH * 0.38f;
        moonX = w * 0.20f; moonY = top + skyH * 0.36f;
    }

    void quadPath(Path p, float xa0, float xa1, float xb0, float xb1, float z0, float z1) {
        p.reset();
        addQuad(p, xa0, xa1, xb0, xb1, z0, z1);
    }

    /** Ground quad: x range [xa0,xa1] at z0, [xb0,xb1] at z1. */
    void addQuad(Path p, float xa0, float xa1, float xb0, float xb1, float z0, float z1) {
        float y0 = sy(z0), y1 = sy(z1);
        p.moveTo(sx(xa0, z0), y0);
        p.lineTo(sx(xa1, z0), y0);
        p.lineTo(sx(xb1, z1), y1);
        p.lineTo(sx(xb0, z1), y1);
        p.close();
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Deterministic block height for block number k (0..BLOCKS-1). */
    static float blockH(int k, int side) {
        int x = (k * 73 + side * 151 + 17) * 0x9E3779B1;
        x ^= x >>> 15;
        float r = ((x >>> 8) & 0xFFFF) / 65535f;
        return 1.0f + 2.0f * r;
    }

    static int hash(int k, int side, int band) {
        int x = (k * 131 + side * 977 + band * 7919 + 3) * 0x9E3779B1;
        return (x ^ (x >>> 15)) >>> 8;
    }

    void drawBlocks(Canvas c, float blkOff, float n) {
        int fogCol = mix(HORIZON_D, HORIZON_N, n);
        int faceCol = mix(FACE_D, FACE_N, n), frontCol = mix(FRONT_D, FRONT_N, n);
        int kMax = (int) Math.floor((Z_BLOCK + blkOff) / BLOCK_P);   // beyond that the haze has swallowed them
        int kMin = (int) Math.ceil((zNear - BLOCK_L + blkOff) / BLOCK_P);
        for (int k = kMax; k >= kMin; k--) {               // far to near: near blocks cover far ones
            float z0 = k * BLOCK_P - blkOff, z1 = z0 + BLOCK_L;
            if (z1 <= zNear) continue;
            float zc0 = Math.max(z0, zNear);
            float fog = Math.min(1f, Math.max(0f, (z0 - 4f) / (Z_BLOCK - 4f)));
            fog = (float) Math.sqrt(fog);
            if (fog >= 0.995f) continue;
            int idx = ((k % (int) BLOCKS) + (int) BLOCKS) % (int) BLOCKS;
            for (int side = -1; side <= 1; side += 2) {
                float yTop = blockH(idx, side);
                float xi = side * BLOCK_X, xo = side * BLOCK_X2;
                face.setColor(mix(faceCol, fogCol, fog));
                q.rewind();
                q.moveTo(sx(xi, zc0), sy(zc0));
                q.lineTo(sx(xi, zc0), syH(yTop, zc0));
                q.lineTo(sx(xi, z1), syH(yTop, z1));
                q.lineTo(sx(xi, z1), sy(z1));
                q.close();
                c.drawPath(q, face);
                // window rows on the road-facing wall: blue glass by day, some warmly lit at night
                if (fog < 0.85f) {
                    float wz0 = Math.max(zc0, z0 + 0.25f), wz1 = z1 - 0.25f;
                    if (wz1 > wz0) {
                        for (int b = 0; b < 5; b++) {
                            float y0 = 0.42f + b * 0.5f, y1 = y0 + 0.2f;
                            if (y1 > yTop - 0.2f) break;
                            int hsh = hash(idx, side, b);
                            float litA = (hsh & 3) != 0 ? n : 0.12f * n;          // 3 in 4 rows lit at night
                            float dayA = 1f - n;
                            int col = mix(mix(WIN_D, faceCol, 0.35f), WIN_N, n * ((hsh & 3) != 0 ? 1f : 0.15f));
                            face.setColor(mix(col, fogCol, fog * (1f - 0.55f * litA)));
                            face.setAlpha((int) (255 * Math.min(1f, 0.55f * dayA + 0.9f * litA)));
                            q.rewind();
                            q.moveTo(sx(xi, wz0), syH(y0, wz0));
                            q.lineTo(sx(xi, wz0), syH(y1, wz0));
                            q.lineTo(sx(xi, wz1), syH(y1, wz1));
                            q.lineTo(sx(xi, wz1), syH(y0, wz1));
                            q.close();
                            c.drawPath(q, face);
                        }
                        face.setAlpha(255);
                    }
                }
                if (z0 > zNear) {
                    face.setColor(mix(frontCol, fogCol, fog));
                    float l = sx(Math.min(xi, xo), z0), r = sx(Math.max(xi, xo), z0);
                    c.drawRect(l, syH(yTop, z0), r, sy(z0), face);
                }
            }
        }
    }

    void drawDashes(Canvas c, float dashOff, int alpha) {
        lane.setAlpha(alpha);
        int kMax = (int) Math.floor((Z_DASH + dashOff) / DASH_P);
        int kMin = (int) Math.ceil((zNear - DASH_L + dashOff) / DASH_P);
        // one small convex quad per dash: the GPU's fast path for convex shapes, nothing to triangulate
        for (int k = kMin; k <= kMax; k++) {
            float z0 = Math.max(zNear, k * DASH_P - dashOff), z1 = k * DASH_P - dashOff + DASH_L;
            if (z1 <= z0) continue;
            for (int side = -1; side <= 1; side += 2) {
                float x = side * LANE_X;
                q.rewind();
                addQuad(q, x - DASH_W, x + DASH_W, x - DASH_W, x + DASH_W, z0, z1);
                c.drawPath(q, lane);
            }
        }
    }

    static void glow(Canvas c, float x, float y, float sxk, float syk, float r, Paint p) {
        int sv = c.save();
        c.translate(x, y);
        c.scale(sxk, syk);
        c.drawCircle(0, 0, r, p);
        c.restoreToCount(sv);
    }

    static float smooth(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, (t - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Stars are out: the sky view keeps a gentle twinkle going. */
    boolean starsVisible(float n) { return smooth(0.55f, 1f, n) > 0.01f; }

    /** A shooting star is flying (or about to): the sky view draws at full rate for it. */
    boolean shooting(long now) { return shootAt >= 0 || (nextShoot >= 0 && nextShoot - now < 100); }

    void drawSky(Canvas c, float n, long now) {
        if (skyDay == null) return;
        // sky: day gradient, night gradient on top as it gets dark
        if (n < 0.999f) { bmp.setAlpha(255); c.drawBitmap(skyDay, 0, top, bmp); }
        if (n > 0.001f) { bmp.setAlpha((int) (255 * n)); c.drawBitmap(skyNight, 0, top, bmp); }
        bmp.setAlpha(255);
        // twinkling stars + the odd shooting star
        float sa = smooth(0.55f, 1f, n);
        if (sa > 0.01f) {
            float t = now / 1000f;
            for (int g = 0; g < 3; g++) {
                float tw = 0.55f + 0.45f * (float) Math.sin(t * (1.7f + 0.6f * g) + g * 2.1f);
                star.setAlpha((int) (255 * sa * tw));
                c.drawPoints(twinkle[g], star);
            }
            drawShootingStar(c, now, sa);
        }
        // the sun sets in the first half of the change, the moon rises in the second
        float set = smooth(0f, 0.6f, n), rise = 1f - smooth(0.45f, 1f, n);
        if (set < 1f) {
            float y = sunY + (hY + sunR * 1.5f - sunY) * set;
            bmp.setAlpha((int) (255 * (1f - 0.4f * set)));
            c.drawBitmap(sun, sunX - sun.getWidth() / 2f, y - sun.getHeight() / 2f, bmp);
        }
        if (rise < 1f) {
            float y = moonY + (hY + moonR * 1.6f - moonY) * rise;
            bmp.setAlpha((int) (255 * (1f - 0.5f * rise)));
            c.drawBitmap(moon, moonX - moon.getWidth() / 2f, y - moon.getHeight() / 2f, bmp);
        }
        // (the clouds are a separate sliding layer: SkyView)
        // sunset / sunrise glow on the horizon while the change happens
        float dk = (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, n)));
        if (dk > 0.01f) {
            dusk.setAlpha((int) (210 * dk));
            c.drawRect(0, top, w, hY + 2, dusk);
        }
    }

    void drawShootingStar(Canvas c, long now, float sa) {
        if (nextShoot < 0) nextShoot = now + 4000 + (long) (rnd() * 9000);
        if (shootAt < 0 && now >= nextShoot) {
            shootAt = now;
            shX = w * (0.15f + 0.6f * rnd());
            shY = top + skyH * (0.1f + 0.3f * rnd());
            shDx = w * 0.22f * (rnd() < 0.5f ? -1 : 1);
            shDy = skyH * 0.28f;
        }
        if (shootAt < 0) return;
        float t = (now - shootAt) / 900f;
        if (t > 1f) { shootAt = -1; nextShoot = now + 9000 + (long) (rnd() * 14000); return; }
        float a = (float) Math.sin(Math.PI * t) * sa;
        float hx = shX + shDx * t, hy = shY + shDy * t;
        for (int i = 0; i < 6; i++) {                       // tail: segments fading away from the head
            float f0 = i / 6f * 0.35f, f1 = (i + 1) / 6f * 0.35f;
            shoot.setAlpha((int) (255 * a * (1f - i / 6f)));
            shoot.setStrokeWidth(Math.max(1f, (1.8f - i * 0.22f) * dens));
            c.drawLine(hx - shDx * f0, hy - shDy * f0, hx - shDx * f1, hy - shDy * f1, shoot);
        }
    }

    /**
     * @param n     0 = day .. 1 = night (animated across sunset / sunrise)
     * @param lamp  tail lamp intensity 0..1 (night running ~0.5, braking 1)
     * @param halo  bloom around the tail lamps 0..1
     * @param brake road reflection 0..1
     * @param pop   brake "pop" 0..1 (just pressed)
     * @param appear start-up fade/rise 0..1
     */
    void draw(Canvas c, long now, float n, Bitmap car, Bitmap oldCar, float swapT, Bitmap tail, int pad, CarArt.RearSpec sp,
              float dashOff, float blkOff, float moveT, float head, float lamp, float halo, float brake, float pop, float appear) {
        // the sky is drawn underneath by SkyView; this view leaves it transparent

        // ground + road (day / night versions cross-fade)
        if (n < 0.999f) { groundD.setAlpha(255); c.drawRect(0, hY, w, bottom, groundD); roadD.setAlpha(255); c.drawPath(roadPath, roadD); }
        if (n > 0.001f) {
            int a = (int) (255 * n);
            groundN.setAlpha(a); c.drawRect(0, hY, w, bottom, groundN);
            roadN.setAlpha(a); c.drawPath(roadPath, roadN);
        }
        edge.setAlpha((int) ((150 + 60 * moveT) * (1f - 0.35f * n)));
        c.drawPath(edgePath, edge);
        drawDashes(c, dashOff, (int) ((175 + 60 * moveT) * (1f - 0.3f * n)));
        // haze where the ground meets the sky
        if (n < 0.999f) { hazeD.setAlpha(255); c.drawRect(0, hY - 2, w, hY + D * 0.16f, hazeD); }
        if (n > 0.001f) { hazeN.setAlpha((int) (255 * n)); c.drawRect(0, hY - 2, w, hY + D * 0.16f, hazeN); }
        drawBlocks(c, blkOff, n);

        if (head > 0.01f) {
            beam.setAlpha((int) (255 * Math.min(1f, head * head) * (0.7f + 0.3f * moveT) * appear));
            c.drawPath(beamPath, beam);
        }

        // car: rises in at start-up; the rear lifts a touch when the brakes bite
        float dy = (1f - appear) * 46f * s - pop * 2.6f * s;
        float x = carL - pad, y = carT - pad + dy;
        if (oldCar != null && swapT < 1f) {
            bmp.setAlpha((int) (255 * appear * (1f - swapT * swapT)));
            c.drawBitmap(oldCar, x, y, bmp);
            bmp.setAlpha((int) (255 * appear * swapT));
        } else {
            bmp.setAlpha((int) (255 * appear));
        }
        if (car != null) c.drawBitmap(car, x, y, bmp);
        float litK = appear * ((oldCar == null || swapT >= 1f) ? 1f : swapT);

        if (lamp > 0.01f && tail != null) {
            bmp.setAlpha((int) (Math.min(1f, lamp) * 255 * litK));
            c.drawBitmap(tail, x, carT + sp.bandY0 * s + dy, bmp);
        }
        bmp.setAlpha(255);
        if (halo > 0.01f) {
            float a = Math.min(1f, halo) * appear;
            float r = 0.85f + 0.25f * Math.min(1f, halo) + 0.3f * pop;
            tailGlow.setAlpha((int) (255 * a));
            float lx = carL + sp.lampLX * s, ly = carT + sp.lampLY * s + dy;
            float rx = carL + sp.lampRX * s, ry = carT + sp.lampRY * s + dy;
            glow(c, lx + 6 * s, ly, r * 0.9f, r * 0.5f, glowR, tailGlow);  // flat bloom round both ends of the bar
            glow(c, rx - 6 * s, ry, r * 0.9f, r * 0.5f, glowR, tailGlow);
            tailGlow.setAlpha((int) (150 * a));
            glow(c, (lx + rx) / 2f, (ly + ry) / 2f, (rx - lx) / 2f / glowR * r * 1.15f, r * 0.3f, glowR, tailGlow); // along the bar
        }
        if (brake > 0.01f) {
            float a = Math.min(1f, brake) * appear;
            roadRed.setAlpha((int) (255 * a * (0.55f + 0.35f * n)));
            glow(c, cx, yG + 4f * s, 3.4f, 0.32f, glowR, roadRed);
        }

        // rounded corners in the card colour
        c.drawPath(cornerPath, corner);
    }
}
