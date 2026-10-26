package xyz.ox1de.cardash;

import java.util.Locale;

/**
 * The optimiser's decisions, in plain Java (no Android types) so they can be unit-tested.
 * Bias everywhere: when unsure, keep the app.
 */
final class Policy {
    private Policy() { }

    static final int GENTLE = 1, BALANCED = 2;
    static final long MIN = 60_000L, DAY = 24 * 60 * MIN;

    /** Never touched, whatever any list says. */
    static final String[] HARD = {"android", "com.android.systemui", "com.android.phone", "com.android.bluetooth",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.projection.gearhead", "com.android.providers.media", "com.android.shell",
            "com.android.server.telecom", "com.android.nfc"};

    /**
     * Package-name fragments that mean "the car or the phone connection depends on this":
     * phone projection (CarPlay / Android Auto / *link / mirroring apps), Bluetooth, cameras,
     * CAN/MCU, radio, TPMS, OBD, keyboards, voice.
     */
    static final String[] PKG_KEYS = {
            "link", "carplay", "airplay", "androidauto", "gearhead", "projection", "mirror", "easyconn",
            "carbit", "autokit", "speedplay", "carlife", "hicar", "bluetooth", "dvr", "camera", "radio",
            "canbus", "mcu", "tpms", "obd", "torque", "inputmethod", "keyboard", "tts", "launcher", "wallpaper"};

    /** Same idea for app labels, incl. the Chinese ones these units ship with (互联 = interconnect, 投屏 = screen cast). */
    static final String[] LABEL_KEYS = {
            "carplay", "car play", "airplay", "android auto", "link", "mirror", "projection", "easyconn",
            "easy conn", "autokit", "speedplay", "carlife", "hicar", "互联", "投屏"};

    static boolean hard(String pkg) {
        for (String h : HARD) if (h.equals(pkg)) return true;
        return false;
    }

    /** True if the package name or label says this is car / phone-connection critical. */
    static boolean carCritical(String pkg, String label) {
        if (pkg != null) {
            if (hard(pkg)) return true;
            String p = pkg.toLowerCase(Locale.US);
            for (String k : PKG_KEYS) if (p.contains(k)) return true;
        }
        if (label != null) {
            String l = label.toLowerCase(Locale.US);
            for (String k : LABEL_KEYS) if (l.contains(k)) return true;
        }
        return false;
    }

    /**
     * 0 = relaxed: do nothing, Android manages its own cache.
     * 1 = tight:   stop apps you haven't used for a while.
     * 2 = critical: stop everything that isn't protected or used in the last 10 minutes.
     */
    static int level(long avail, long total, long threshold, boolean lowMemory, int mode) {
        float free = avail / (float) total;
        if (mode == BALANCED) {
            if (lowMemory || free < 0.15f) return 2;
            if (free < 0.30f || avail < threshold * 2) return 1;
        } else {
            if (lowMemory || free < 0.10f) return 2;
            if (free < 0.20f || avail < threshold * 3 / 2) return 1;
        }
        return 0;
    }

    /**
     * Should this app be kept cached (= reopens instantly)?
     * @param lastOpen  last time it was in front (CarDash launch or Android usage stats), 0 if unknown
     * @param opens     how many times it was opened from CarDash
     */
    static boolean keepWarm(long lastOpen, int opens, long now, int level, int mode, boolean manual) {
        long since = now - lastOpen;
        long recent = (manual || level >= 2) ? 10 * MIN : (mode == BALANCED ? 20 * MIN : 60 * MIN);
        if (lastOpen > 0 && since < recent) return true;
        if (manual || level >= 2) return false;
        return mode == BALANCED ? (opens >= 5 && since < 3 * DAY) : (opens >= 3 && since < 7 * DAY);
    }
}
