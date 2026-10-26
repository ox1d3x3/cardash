package xyz.ox1de.cardash;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;

/**
 * Is a wireless Android Auto / CarPlay session (likely) running? Those run over a Wi-Fi network the
 * head unit hosts (hotspot or Wi-Fi Direct). Joining the DDPAI's Wi-Fi at the same time forces the
 * same radio to serve two networks, which on many head units makes the projection stutter or drop.
 */
final class PhoneLink {
    private PhoneLink() { }

    /** "p2p-wlan0-0 192.168.49.1" style description of a hosted network, or null. Cheap (no I/O). */
    static String hosted() {
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && Policy.hostedNetwork(ni.getName(), a.getAddress()))
                        return ni.getName() + " " + a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Package of a phone-link app that is currently playing (e.g. Android Auto audio through ZLINK), or null. */
    static String playing(MediaSessionManager msm, ComponentName listener, Map<String, String> labels) {
        try {
            for (MediaController c : msm.getActiveSessions(listener)) {
                PlaybackState s = c.getPlaybackState();
                boolean on = s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
                String p = c.getPackageName();
                if (on && Policy.phoneLink(p, labels == null ? null : labels.get(p))) return p;
            }
        } catch (Exception ignored) { } // no media access: rely on the network check
        return null;
    }

    /** What the Wi-Fi chip says it can do at once (informational: same-channel limits still apply). */
    static String concurrency(WifiManager wm) {
        StringBuilder b = new StringBuilder();
        try {
            if (Build.VERSION.SDK_INT >= 30) b.append("hotspot + client at once: ").append(wm.isStaApConcurrencySupported() ? "yes" : "no");
            if (Build.VERSION.SDK_INT >= 31) b.append(", two client networks at once: ")
                    .append(wm.isStaConcurrencyForLocalOnlyConnectionsSupported() ? "yes" : "no");
        } catch (Exception ignored) { }
        return b.length() == 0 ? "unknown" : b.toString();
    }
}
