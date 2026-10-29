package xyz.ox1de.cardash;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;

/**
 * Frame pacing for the animations that run for a long time: the road while driving, the screensaver
 * intro and cat, the cloud drift and the twinkling stars.
 *
 * A unit that can't finish a frame inside one 60 Hz refresh doesn't really show "60 fps": some frames
 * reach the screen a refresh late, so the motion stutters, and it runs hot doing it. A steady 30 fps
 * looks smoother on such a unit and costs half. Auto starts from a guess (32-bit-only head units are
 * the slow ones) and then goes by this unit's real frame times while the road is moving: it moves up
 * to 60 if frames are quick, and back to 30 for good if 60 can't be held. Settings can force either.
 */
final class Pace {
    private Pace() { }

    static final int AUTO = 0, SMOOTH = 1, LIGHT = 2;

    static volatile int mode = AUTO;
    /** Auto's verdict for this unit: true = 30 fps. Starts from a guess, then measured. */
    static volatile boolean slow = Build.SUPPORTED_64_BIT_ABIS.length == 0;
    /** True while the road is moving at full rate: the frames worth judging. */
    static volatile boolean measuring;

    /** Run continuous animations at half rate (every other refresh)? */
    static boolean half() { return mode == LIGHT || (mode == AUTO && slow); }

    static String describe() {
        if (mode == SMOOTH) return "60 fps";
        if (mode == LIGHT) return "30 fps";
        return "Auto (now " + (slow ? "30" : "60") + " fps)";
    }

    // ------------------------------------------------------------------ measuring (Auto only)

    static final int WINDOW = 90;          // frames per verdict (1.5 s at 60 fps, 3 s at 30)
    static int n, late, quick;
    static boolean tried60, locked;        // one try at 60 per run; a failed try is final
    static HandlerThread thread;
    static Window watched;
    static Window.OnFrameMetricsAvailableListener listener;

    /** Watch this window's frame times while Auto still has something to decide. */
    static synchronized void watch(Window w) {
        if (mode != AUTO || locked || listener != null) return;
        if (thread == null) {
            thread = new HandlerThread("cardash-pace", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
        }
        listener = (win, fm, dropped) -> onFrame(fm);
        try {
            w.addOnFrameMetricsAvailableListener(listener, new Handler(thread.getLooper()));
            watched = w;
        } catch (Exception e) {
            listener = null;
        }
    }

    /** The activity owning this window is going: stop watching it (but not a newer one's window). */
    static synchronized void unwatch(Window w) {
        if (watched == w) unwatch();
    }

    static synchronized void unwatch() {
        if (listener != null && watched != null) {
            try { watched.removeOnFrameMetricsAvailableListener(listener); } catch (Exception ignored) { }
        }
        listener = null;
        watched = null;
    }

    static void setMode(int m) {
        mode = m;
        n = late = quick = 0;
        if (m != AUTO) unwatch();
    }

    /** Frame-metrics thread. */
    static void onFrame(FrameMetrics fm) {
        if (mode != AUTO || locked || !measuring) return;
        long total = fm.getMetric(FrameMetrics.TOTAL_DURATION);
        n++;
        if (total > 20_000_000L) late++;     // missed its refresh at 60 fps
        if (total < 11_000_000L) quick++;    // plenty of room
        if (n < WINDOW) return;
        boolean wasSlow = slow;
        if (!slow && late * 4 >= n) {        // a quarter of the frames late: 60 isn't holding
            slow = true;
            if (tried60) locked = true;
        } else if (slow && !tried60 && quick * 20 >= n * 19) {   // 95 % quick at 30: try 60 once
            slow = false;
            tried60 = true;
        }
        if (slow != wasSlow) {
            Diag.log("perf", "motion: " + (slow ? "30" : "60") + " fps (" + late + " of " + n
                    + " frames over 20 ms, " + quick + " under 11 ms)" + (locked ? ", final" : ""));
        }
        n = late = quick = 0;
        if (locked) {
            new Handler(android.os.Looper.getMainLooper()).post(Pace::unwatch);
        }
    }

    // ------------------------------------------------------------------ scheduling

    /**
     * One view's next animation frame: the next refresh at 60 fps, or the one after it at 30.
     * Never more than one pending, so repeated requests can't pile up into extra frames.
     */
    static final class Frames implements Choreographer.FrameCallback {
        final View v;
        boolean pending;

        Frames(View v) { this.v = v; }

        /** @param drawTime the frame time of the draw asking (View.getDrawingTime / uptimeMillis) */
        void next(long drawTime) {
            if (!half()) {
                cancel();
                v.postInvalidateOnAnimation();
                return;
            }
            if (pending) return;
            pending = true;
            // due between the next refresh and the one after it, so it lands on the one after
            long delay = drawTime + 24 - SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallbackDelayed(this, Math.max(0, delay));
        }

        void cancel() {
            if (!pending) return;
            pending = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long nanos) {
            pending = false;
            v.invalidate();    // drawn in this same frame (animation callbacks run before drawing)
        }
    }
}
