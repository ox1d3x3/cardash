package xyz.ox1de.cardash;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * Media card: swipe left = next track, swipe right = previous. A plain tap still opens the player
 * and a long-press still picks the player. While dragging, the album art follows the finger a
 * little (GPU translation only). Also gives the card its press feedback.
 */
final class Swipe implements View.OnTouchListener {

    interface Target { void onSwipe(boolean next); }

    final View follow;
    final Target target;
    final float slop, trigger, maxShift;
    float x0, y0, dx;
    boolean dragging;

    Swipe(View follow, float density, Target target) {
        this.follow = follow;
        this.target = target;
        slop = 14 * density;
        trigger = 64 * density;
        maxShift = 56 * density;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getRawX();
                y0 = e.getRawY();
                dx = 0;
                dragging = false;
                follow.animate().cancel();
                v.animate().scaleX(0.975f).scaleY(0.975f).setStartDelay(0).setDuration(90).setInterpolator(Anim.OUT).start();
                return false;
            case MotionEvent.ACTION_MOVE: {
                dx = e.getRawX() - x0;
                float dy = e.getRawY() - y0;
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    dragging = true;
                    v.cancelLongPress();
                    v.setPressed(false);
                    ViewParent p = v.getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    follow.setTranslationX(Math.max(-maxShift, Math.min(maxShift, dx * 0.4f)));
                    return true; // the card never sees the drag, so it can't turn into a click
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260).setInterpolator(Anim.POP).start();
                if (!dragging) return false;
                dragging = false;
                v.setPressed(false);
                follow.animate().translationX(0f).setStartDelay(0).setDuration(240).setInterpolator(Anim.OUT).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) > trigger) target.onSwipe(dx < 0);
                return true;
            }
            default:
                return dragging;
        }
    }
}
