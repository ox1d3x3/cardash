package xyz.ox1de.cardash;

import android.content.SharedPreferences;
import android.service.dreams.DreamService;

/** Same screen as Android's system screensaver (Settings → Display → Screen saver → CarDash Clock). */
public class ClockDream extends DreamService {
    ClockView clock;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        clock = new ClockView(this);
        SharedPreferences sp = getSharedPreferences("cardash", MODE_PRIVATE);
        clock.setCar(sp.getBoolean("saverCar", true) ? sp.getInt("carStyle", CarArt.HATCH) : -1, sp.getInt("carColor", 0));
        setContentView(clock);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); clock.start(); }

    @Override public void onDreamingStopped() { clock.stop(); super.onDreamingStopped(); }
}
