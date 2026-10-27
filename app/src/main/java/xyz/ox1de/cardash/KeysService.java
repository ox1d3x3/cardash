package xyz.ox1de.cardash;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * "CarDash buttons": lets the dock's Back / Recent apps buttons and the system panel press Android's
 * own keys (back, recents, notifications, quick settings, screenshot, split screen, lock, power
 * menu). Android only allows that through an accessibility service the owner switches on once.
 *
 * It does nothing else: it listens to no other app (events are limited to CarDash's own package and
 * one rare event type), never reads window content, and costs nothing while idle.
 */
public class KeysService extends AccessibilityService {

    static volatile KeysService live;

    @Override protected void onServiceConnected() { live = this; }

    @Override public boolean onUnbind(Intent i) { live = null; return super.onUnbind(i); }

    @Override public void onDestroy() { live = null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    /** @return false if the service isn't switched on (or Android refused the action). */
    static boolean press(int action) {
        KeysService s = live;
        return s != null && s.performGlobalAction(action);
    }

    static ComponentName component(Context c) { return new ComponentName(c, KeysService.class); }

    /** Switched on in Settings → Accessibility (even if Android hasn't connected it yet). */
    static boolean enabled(Context c) {
        if (live != null) return true;
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        String me = component(c).flattenToString(), meShort = component(c).flattenToShortString();
        for (String part : on.split(":")) if (part.equalsIgnoreCase(me) || part.equalsIgnoreCase(meShort)) return true;
        return false;
    }
}
