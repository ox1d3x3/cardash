package xyz.ox1de.cardash;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Small UI motions. All of them animate RenderNode properties only (scale, alpha, translation):
 * the GPU re-composites the existing display list, nothing is re-measured, re-laid-out or re-drawn,
 * so they cost close to nothing even on a 2 GB head unit. Interpolators are shared; no allocation
 * per touch beyond the framework's own animator.
 */
final class Anim {
    private Anim() { }

    static final TimeInterpolator OUT = new DecelerateInterpolator(2f);
    static final TimeInterpolator POP = new OvershootInterpolator(2.2f);
    static final TimeInterpolator SOFT = new OvershootInterpolator(1.2f);

    /** Press feedback: shrinks while held, springs back on release. Clicks / long-presses unaffected. */
    static void press(View v, float to) {
        v.setOnTouchListener(new Press(to));
    }

    static final class Press implements View.OnTouchListener {
        final float to;
        Press(float to) { this.to = to; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(to).scaleY(to).setStartDelay(0).setDuration(90).setInterpolator(OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260).setInterpolator(POP).start();
                    break;
                default:
                    break;
            }
            return false; // let the view handle the click itself
        }
    }

    // NB: ViewPropertyAnimator keeps duration / interpolator / start delay between animations,
    // so every call here sets all three.

    /** Quick "pop" (play/pause icon swap). */
    static void pop(View v) {
        v.setScaleX(0.82f);
        v.setScaleY(0.82f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(300).setInterpolator(POP).start();
    }

    /** New content slides in from the side and fades up (track title / artist). */
    static void slideIn(View v, float dx, long delay) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(320).setInterpolator(OUT).start();
    }

    /** New image grows in (album art). */
    static void growIn(View v) {
        v.animate().cancel();
        v.setAlpha(0.2f);
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        // the art view has a background, so a fading alpha would be re-rendered offscreen every frame:
        // a hardware layer draws it once and the GPU just fades / scales that texture
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(340).setInterpolator(SOFT).withLayer().start();
    }

    /** Dashboard cards rise in, one after another (cold start only). */
    static void rise(View v, float dy, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420).setInterpolator(OUT).withLayer().start();
    }
}
