package xyz.ox1de.cardash;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/**
 * Live view from a DDPAI dash cam over its own Wi-Fi.
 *
 *  1. Find the camera: an already-joined Wi-Fi that answers the DDPAI API, otherwise ask Android to
 *     join the camera's Wi-Fi for this app only (WifiNetworkSpecifier: one-time system prompt; the
 *     previous Wi-Fi comes back by itself when the view closes).
 *  2. Log in + "start live" over the HTTP API (best effort: firmware differs by model).
 *  3. Stream: RTSP (rtsp://193.168.0.1:554/liveRTSP/av4 front, av5 rear), falling back to the
 *     older raw H.264 port 6200. Hardware-decoded straight to the screen.
 *  4. Reconnect on drops; every step is logged so problems can be diagnosed from the app.
 */
final class DashCam {

    interface Ui {                       // always called on the UI thread
        void camStatus(String s);
        void camPlaying();
        void camFailed(String s);
        void camSize(int w, int h);
    }

    static final String RTSP_FRONT = "rtsp://" + Ddpai.HOST + ":554/liveRTSP/av4";
    static final String RTSP_REAR = "rtsp://" + Ddpai.HOST + ":554/liveRTSP/av5";

    final Context ctx;
    final SharedPreferences sp;
    final Handler ui;
    final Ui cb;
    final ConnectivityManager cm;
    final WifiManager wm;
    final StringBuilder log = new StringBuilder();
    final long t0 = SystemClock.elapsedRealtime();

    volatile boolean stopped, rear, gotPicture;
    volatile Network camNet;
    volatile Rtsp rtsp;
    volatile RawStream raw;
    volatile CountDownLatch wifiLatch;
    ConnectivityManager.NetworkCallback netCb;
    AvcDecoder dec;
    Thread worker, beat;

    DashCam(Context c, SharedPreferences sp, Handler ui, Ui cb) {
        ctx = c.getApplicationContext();
        this.sp = sp;
        this.ui = ui;
        this.cb = cb;
        cm = ctx.getSystemService(ConnectivityManager.class);
        wm = ctx.getSystemService(WifiManager.class);
    }

    // ------------------------------------------------------------------ control (UI thread)

    void start(Surface surface, boolean rearCam) {
        rear = rearCam;
        dec = new AvcDecoder(surface, new AvcDecoder.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { if (!stopped) cb.camSize(w, h); }); }
            @Override public void onFirstFrame() { ui.post(() -> { if (!stopped) cb.camPlaying(); }); }
        });
        worker = new Thread(this::work, "dashcam");
        worker.start();
    }

    /** Show the live picture somewhere else (or nowhere) without dropping the connection. */
    void setSurface(Surface s) {
        if (dec != null) dec.setSurface(s);
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        closeStreams();
        CountDownLatch l = wifiLatch;
        if (l != null) l.countDown();
        if (worker != null) worker.interrupt();
        if (beat != null) beat.interrupt();
        if (dec != null) dec.release();                 // before the Surface goes away
        if (netCb != null) {                           // give the Wi-Fi back
            try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
            netCb = null;
        }
        log("stopped");
        sp.edit().putString("camLog", logText()).apply();
    }

    void closeStreams() {
        Rtsp r = rtsp;
        if (r != null) r.close();
        RawStream s = raw;
        if (s != null) s.close();
    }

    // ------------------------------------------------------------------ worker

    void work() {
        log("CarDash dash cam, Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL);
        log("Wi-Fi chip: " + PhoneLink.concurrency(wm));
        String link = PhoneLink.hosted();
        log("phone link (wireless AA/CarPlay): " + (link == null ? "not detected" : link));
        status("Looking for the dash cam…");
        Network n = findReachable();
        if (n == null && !stopped) {
            status("Joining the dash cam Wi-Fi…\nApprove the Android prompt if one appears.");
            n = requestWifi();
        }
        if (stopped) return;
        if (n == null) {
            fail("Couldn't reach the dash cam.\nIs it powered and its Wi-Fi on? Is your phone connected to it instead? Tap for details.");
            return;
        }
        camNet = n;
        final Network net = n;
        SocketFactory sf = net.getSocketFactory();
        final Ddpai api = new Ddpai(net::openConnection, this::log);
        status("Starting live view…");
        boolean http = api.handshake(uid(), sp.getBoolean("camSmooth", true));
        if (!http) log("HTTP API did not answer; trying the video streams anyway");
        if (!api.model.isEmpty()) sp.edit().putString("camModel", api.model).apply();
        if (http) startHeartbeat(api);

        int failures = 0;
        boolean everPlayed = false;
        while (!stopped) {
            long began = SystemClock.elapsedRealtime();
            gotPicture = false;
            boolean wantRear = rear;
            String url = wantRear ? RTSP_REAR : RTSP_FRONT;
            try {
                playRtsp(sf, url);
            } catch (IOException e) {
                log("RTSP " + url + ": " + e.getMessage());
            }
            if (!stopped && !gotPicture && !wantRear && rear == wantRear) { // older firmware: raw H.264 on 6200
                try {
                    if (http) api.cmd("APP_PlaybackLiveSwitch", "{\"switch\":\"live\",\"playtime\":\"\"}", 2500);
                    playRaw(sf);
                } catch (IOException e) {
                    log("raw :6200: " + e.getMessage());
                }
            }
            if (stopped) return;
            if (rear != wantRear) continue;                       // user switched camera: reconnect now
            if (gotPicture) {
                everPlayed = true;
                if (SystemClock.elapsedRealtime() - began > 15_000) failures = 0;
            }
            failures++;
            if (!everPlayed && failures >= 2) {
                fail(wantRear ? "The rear camera isn't sending video. Is one connected?\nTap for details."
                        : "Connected to the camera, but it isn't sending live video.\nTap for details.");
                return;
            }
            if (failures > 8) {
                fail("Lost the dash cam. Tap for details.");
                return;
            }
            status("Reconnecting…");
            sleep(Math.min(800L * failures, 4000));
            if (http && !stopped) api.handshake(uid(), sp.getBoolean("camSmooth", true));
        }
    }

    void playRtsp(SocketFactory sf, String url) throws IOException {
        final H264.Assembler asm = new H264.Assembler(this::onPicture);
        Rtsp r = new Rtsp(sf, url, "admin", "admin", new Rtsp.Listener() {
            @Override public void onNal(byte[] b, int off, int len) { asm.onNal(b, off, len); }
            @Override public void onPictureEnd() { asm.flush(); }
            @Override public void log(String s) { DashCam.this.log(s); }
        });
        rtsp = r;
        if (stopped) return;
        try { r.run(4000); } finally { rtsp = null; }
    }

    void playRaw(SocketFactory sf) throws IOException {
        final H264.Assembler asm = new H264.Assembler(this::onPicture);
        RawStream s = new RawStream(sf, Ddpai.HOST, 6200);
        raw = s;
        if (stopped) return;
        log("trying raw H.264 on :6200");
        try { s.run(asm, 4000); } finally { raw = null; }
    }

    void onPicture(byte[] au, int len, boolean key) {
        if (!gotPicture) { gotPicture = true; log("video arriving (first picture " + (key ? "keyframe" : "delta") + ")"); }
        dec.onAu(au, len, key);
    }

    void startHeartbeat(final Ddpai api) {
        beat = new Thread(() -> {
            while (!stopped) {
                try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
                if (!stopped) api.heartbeat();
            }
        }, "dashcam-beat");
        beat.setDaemon(true);
        beat.start();
    }

    // ------------------------------------------------------------------ Wi-Fi

    /** A Wi-Fi network we're already on that answers like a DDPAI camera (not just any host at that IP). */
    Network findReachable() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                Ddpai probe = new Ddpai(n::openConnection, s -> { });
                Ddpai.Res r = probe.cmd("API_RequestSessionID", "", 1500);
                if (r.err != -999) { log("camera found on an already-joined Wi-Fi"); return n; }
            }
        } catch (Exception e) {
            log("probe: " + e.getMessage());
        }
        return null;
    }

    Network requestWifi() {
        if (Build.VERSION.SDK_INT < 29) {
            log("Android < 10: join the camera Wi-Fi manually in Settings");
            return null;
        }
        String ssid = sp.getString("camSsid", "").trim();
        if (ssid.isEmpty()) ssid = scanForCamera();
        if (ssid.isEmpty()) ssid = sp.getString("camSsidSeen", "");
        String pw = sp.getString("camPass", "1234567890");
        WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder();
        if (!ssid.isEmpty()) {
            b.setSsid(ssid);
            log("requesting Wi-Fi \"" + ssid + "\"");
        } else {
            // any network whose name contains "ddpai" (covers vYou_DDPai_* and DDPAI_*)
            b.setSsidPattern(new PatternMatcher(".*[Dd][Dd][Pp][Aa][Ii].*", PatternMatcher.PATTERN_ADVANCED_GLOB));
            log("requesting any Wi-Fi named *DDPAI*");
        }
        if (pw.length() >= 8 && pw.length() <= 63) b.setWpa2Passphrase(pw);
        NetworkRequest req;
        try {
            req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(b.build())
                    .build();
        } catch (Exception e) {
            log("bad Wi-Fi request: " + e.getMessage());
            return null;
        }
        final Network[] got = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        wifiLatch = latch;
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { got[0] = n; latch.countDown(); }
            @Override public void onUnavailable() { log("Wi-Fi request declined or camera not found"); latch.countDown(); }
            @Override public void onLost(Network n) {
                if (n.equals(camNet)) { log("camera Wi-Fi lost"); closeStreams(); }
            }
        };
        try {
            cm.requestNetwork(req, netCb, 60_000);
            latch.await(65, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("Wi-Fi request: " + e.getMessage());
        }
        wifiLatch = null;
        if (got[0] != null) {
            log("joined the camera Wi-Fi");
            rememberSsid();
        }
        return got[0];
    }

    /** Exact SSID from the last scan, so Android can match it directly (and remember the approval). */
    String scanForCamera() {
        try {
            for (ScanResult r : wm.getScanResults()) {
                String s = r.SSID == null ? "" : r.SSID;
                String l = s.toLowerCase(Locale.US);
                if (l.contains("ddpai") || l.contains("vyou")) { log("seen in scan: \"" + s + "\""); return s; }
            }
        } catch (Exception e) {
            log("scan: " + e.getMessage());
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    void rememberSsid() {
        try {
            String s = wm.getConnectionInfo().getSSID();
            if (s != null) s = s.replace("\"", "");
            if (s != null && !s.isEmpty() && !s.contains("unknown")) {
                sp.edit().putString("camSsidSeen", s).apply();
                log("camera Wi-Fi is \"" + s + "\"");
            }
        } catch (Exception ignored) { }
    }

    /** Android 12+ chips that can stay on the normal Wi-Fi while also joined to the camera. */
    static boolean staConcurrency(WifiManager wm) {
        try { return Build.VERSION.SDK_INT >= 31 && wm.isStaConcurrencyForLocalOnlyConnectionsSupported(); }
        catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ misc

    String uid() {
        String u = sp.getString("camUid", null);
        if (u == null) {
            u = Long.toHexString(Double.doubleToLongBits(Math.random())) + Long.toHexString(System.nanoTime());
            u = u.length() > 16 ? u.substring(0, 16) : u;
            sp.edit().putString("camUid", u).apply();
        }
        return u;
    }

    void status(final String s) { log("» " + s.replace('\n', ' ')); ui.post(() -> { if (!stopped) cb.camStatus(s); }); }

    void fail(final String s) {
        log("FAILED: " + s.replace('\n', ' '));
        sp.edit().putString("camLog", logText()).apply();
        ui.post(() -> { if (!stopped) cb.camFailed(s); });
    }

    void log(String s) {
        Diag.log("cam", s);
        synchronized (log) {
            long ms = SystemClock.elapsedRealtime() - t0;
            log.append(String.format(Locale.US, "%3d.%03d  ", ms / 1000, ms % 1000)).append(s).append('\n');
            if (log.length() > 12000) log.delete(0, log.length() - 10000);
        }
    }

    String logText() {
        synchronized (log) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "\n" + log;
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
