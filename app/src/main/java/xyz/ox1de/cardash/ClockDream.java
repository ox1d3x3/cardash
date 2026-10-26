package xyz.ox1de.cardash;

import android.service.dreams.DreamService;

/** Same clock as Android's system screensaver (Settings → Display → Screen saver → CarDash Clock). */
public class ClockDream extends DreamService {
    ClockView clock;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        clock = new ClockView(this);
        setContentView(clock);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); clock.start(); }

    @Override public void onDreamingStopped() { clock.stop(); super.onDreamingStopped(); }
}
