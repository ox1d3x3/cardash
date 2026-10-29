package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * The chase cam's sky, on its own layer underneath the road (DriveView leaves the sky transparent):
 * gradients, sun / moon, stars, the dusk glow, and the clouds.
 *
 * Why a separate view: the clouds drift all the time, and moving them used to mean redrawing the
 * whole road scene several times a second. Here the clouds are a child view that only changes its
 * translationX: the road, buildings and car are not redrawn at all, so the drift can run smoothly at
 * 30 fps for less work than the old 8-12 fps. The clouds still pass behind the buildings because
 * the road scene sits on top.
 *
 *  day:   clouds 30 fps (property change only), everything else still
 *  night: stars twinkle at 12 fps (this view only), a shooting star at full rate while it flies
 *  hidden / paused / top-down view: nothing at all
 */
public class SkyView extends FrameLayout {

    RearScene scene;
    float n = -1;            // 0 day .. 1 night
    boolean running;
    final CloudStrip strip;
    float cloudAlpha = -1;
    long cloudT0;            // drift clock: survives pauses so the clouds don't jump back

    public SkyView(Context c) { this(c, null); }

    public SkyView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        strip = new CloudStrip(c);
        addView(strip, new LayoutParams(0, 0));
        // round the top corners like the card (the road scene paints the card colour into the corners
        // on top as well; this keeps the drifting clouds inside them)
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                RearScene sc = scene;
                if (sc == null) { o.setEmpty(); return; }
                float r = 16 * sc.dens;
                o.setRoundRect(0, Math.round(sc.top), Math.round(sc.w), Math.round(sc.bottom), r);
            }
        });
        setClipToOutline(true);
    }

    /** After the scene was laid out (size / theme): take its sky bitmaps. */
    void setScene(RearScene sc) {
        scene = sc;
        strip.set(sc.clouds, Math.round(sc.w), sc.top + sc.skyH * 0.04f);
        invalidateOutline();
        invalidate();
    }

    /** Day / night blend, animated by the road scene during sunset and sunrise. */
    void setNight(float night) {
        if (Math.abs(night - n) < 0.002f) return;
        n = night;
        float ca = 1f - RearScene.smooth(0.2f, 0.7f, n);
        setCloudAlpha(ca * (235f / 255f));
        invalidate();
    }

    void setRunning(boolean r) {
        if (r == running) return;
        running = r;
        updateTicker();
        if (r) invalidate();
    }

    void setCloudAlpha(float a) {
        if (Math.abs(a - cloudAlpha) < 0.004f) return;
        cloudAlpha = a;
        strip.setAlpha(a);
        strip.setVisibility(a > 0.01f ? VISIBLE : INVISIBLE);
        updateTicker();
    }

    // ---- cloud drift: vsync-aligned, translation only

    boolean ticking;
    float lastX = 1;
    final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nanos) {
            if (!ticking) return;
            long next = 30;
            if (strip.w > 0) {
                float speed = strip.w * 0.012f / 1000f;                     // px per ms (~5 px a second)
                float x = ((SystemClock.uptimeMillis() - cloudT0) * speed) % strip.w - strip.w;
                if (x != lastX) { lastX = x; strip.setTranslationX(x); }   // sub-pixel step, filtered: it glides
                // ~0.16 px steps (30 a second); on a slow unit 0.5 px steps (10 a second): each move
                // redraws the whole screen there, and soft clouds still glide at half a pixel
                float step = Pace.half() ? 0.5f : 0.16f;
                next = Math.max(16, (long) (step / speed) - 4);
            }
            // the UI thread sleeps between moves instead of waking every refresh just to skip it
            Choreographer.getInstance().postFrameCallbackDelayed(this, next);
        }
    };

    void updateTicker() {
        if (strip == null) return;   // still constructing
        boolean want = running && cloudAlpha > 0.01f && getVisibility() == VISIBLE && strip.w > 0;
        if (want == ticking) return;
        ticking = want;
        if (want) {
            if (cloudT0 == 0) cloudT0 = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(tick);
        } else {
            Choreographer.getInstance().removeFrameCallback(tick);
        }
    }

    @Override
    public void setVisibility(int v) {
        super.setVisibility(v);
        updateTicker();
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        removeCallbacks(twinkleR);
        super.onDetachedFromWindow();
    }

    // ---- sky + stars

    @Override
    protected void onDraw(Canvas c) {
        RearScene sc = scene;
        if (sc == null || n < 0) return;
        long now = SystemClock.uptimeMillis();
        sc.drawSky(c, n, now);
        if (!running) return;
        if (sc.shooting(now)) postInvalidateOnAnimation();          // a shooting star: full rate while it flies
        else if (sc.starsVisible(n)) {                              // twinkle: a slow fade, 12 fps is plenty (8 on a slow unit)
            removeCallbacks(twinkleR);                              // one pending redraw at most, never a pile-up
            postDelayed(twinkleR, Pace.half() ? 125 : 83);
        }
    }

    final Runnable twinkleR = this::invalidate;

    // The strip is laid out here, directly, at its own size (twice the sky's width): setting its
    // layout params from inside a layout pass forced a second layout of the whole screen.
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        strip.place();
    }

    /** The cloud bitmap twice side by side (2 x width), so sliding it by one width loops seamlessly. */
    static final class CloudStrip extends View {
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap clouds;
        int w;
        float y;

        CloudStrip(Context c) { super(c); }

        void set(Bitmap b, int width, float top) {
            clouds = b;
            w = width;
            y = top;
            place();
            invalidate();
        }

        void place() {
            if (clouds == null) return;
            int pw = w * 2, ph = Math.round(y + clouds.getHeight()) + 2;
            if (getLeft() != 0 || getTop() != 0 || getWidth() != pw || getHeight() != ph) layout(0, 0, pw, ph);
        }

        @Override public boolean hasOverlappingRendering() { return false; } // alpha straight onto the two bitmaps

        @Override
        protected void onDraw(Canvas c) {
            if (clouds == null) return;
            c.drawBitmap(clouds, 0, y, p);
            c.drawBitmap(clouds, w, y, p);
        }
    }
}
