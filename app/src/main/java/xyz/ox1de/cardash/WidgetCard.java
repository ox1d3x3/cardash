package xyz.ox1de.cardash;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * The music card when it hosts a music app's own widget. Taps go straight to the widget's buttons;
 * a long-press anywhere (even on a button) opens CarDash's menu instead, and the widget then gets a
 * cancel rather than a click.
 */
public class WidgetCard extends FrameLayout {

    Runnable onLongPress;
    private final int slop;
    private final long timeout;
    private float downX, downY;
    private boolean fired;
    private final Runnable check = () -> {
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (onLongPress != null) onLongPress.run();
    };

    public WidgetCard(Context c) { this(c, null); }

    public WidgetCard(Context c, AttributeSet a) {
        super(c, a);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        timeout = ViewConfiguration.getLongPressTimeout();
    }

    private void track(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fired = false;
                downX = e.getX();
                downY = e.getY();
                removeCallbacks(check);
                postDelayed(check, timeout);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) removeCallbacks(check);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(check);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        track(e);
        return fired; // after a long-press, take the rest of the gesture away from the widget
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN || !fired) track(e);
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(check);
        super.onDetachedFromWindow();
    }
}
