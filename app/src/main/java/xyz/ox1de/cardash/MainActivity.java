package xyz.ox1de.cardash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends Activity {

    static final String AIMP = "com.aimp.player";
    static final int SLOTS = 6;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();

    SharedPreferences sp;
    PackageManager pm;
    AudioManager am;
    LocationManager lm;
    MediaSessionManager msm;
    ConnectivityManager cm;
    ComponentName listenerCn;

    TextView speedTv, unitTv, gpsTv, headingTv, clockTv, dateTv;
    TextView appTv, titleTv, artistTv, posTv, durTv, volTxt;
    ImageView artIv, wifiIv;
    ImageButton playBtn, muteBtn;
    ProgressBar prog;
    SeekBar volBar;
    LinearLayout content, drivePanel, rightCol, dock;
    FrameLayout drawer;
    GridView grid;

    final List<AppEntry> apps = new ArrayList<>();
    final Map<String, AppEntry> byPkg = new HashMap<>();
    final List<Runnable> afterLoad = new ArrayList<>();
    boolean appsLoaded, appsLoading, appsDirty;
    AppAdapter gridAdapter;

    MediaController ctl;
    Bitmap artBmp;
    long duration;
    boolean needAccess, resumed, locating, draggingVol, receiversOn;
    long lastFix;
    float lastSpeed = -1;
    int iconPx;

    static class AppEntry {
        String pkg, label;
        Bitmap icon;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("cardash", MODE_PRIVATE);
        pm = getPackageManager();
        am = getSystemService(AudioManager.class);
        lm = getSystemService(LocationManager.class);
        msm = getSystemService(MediaSessionManager.class);
        cm = getSystemService(ConnectivityManager.class);
        listenerCn = new ComponentName(this, MediaListener.class);
        iconPx = dp(48);

        speedTv = findViewById(R.id.speed);
        unitTv = findViewById(R.id.unit);
        gpsTv = findViewById(R.id.gps);
        headingTv = findViewById(R.id.heading);
        clockTv = findViewById(R.id.clock);
        dateTv = findViewById(R.id.date);
        appTv = findViewById(R.id.mApp);
        titleTv = findViewById(R.id.title);
        artistTv = findViewById(R.id.artist);
        posTv = findViewById(R.id.pos);
        durTv = findViewById(R.id.dur);
        volTxt = findViewById(R.id.volTxt);
        artIv = findViewById(R.id.art);
        wifiIv = findViewById(R.id.wifi);
        playBtn = findViewById(R.id.play);
        muteBtn = findViewById(R.id.mute);
        prog = findViewById(R.id.prog);
        volBar = findViewById(R.id.vol);
        content = findViewById(R.id.content);
        drivePanel = findViewById(R.id.drivePanel);
        rightCol = findViewById(R.id.rightCol);
        dock = findViewById(R.id.dock);
        drawer = findViewById(R.id.drawer);
        grid = findViewById(R.id.grid);

        artIv.setClipToOutline(true);

        gridAdapter = new AppAdapter();
        grid.setAdapter(gridAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            hideDrawer();
            launch(apps.get(pos).pkg);
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            openAppInfo(apps.get(pos).pkg);
            return true;
        });
        drawer.setOnClickListener(v -> hideDrawer());

        playBtn.setOnClickListener(v -> playPause());
        findViewById(R.id.prev).setOnClickListener(v -> skip(false));
        findViewById(R.id.next).setOnClickListener(v -> skip(true));
        View media = findViewById(R.id.media);
        media.setOnClickListener(v -> openPlayer());
        media.setOnLongClickListener(v -> { pickPlayer(); return true; });
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.speedBox).setOnClickListener(v -> toggleUnits());
        wifiIv.setOnClickListener(v -> startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        muteBtn.setOnClickListener(v -> {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            syncVolume();
        });
        volBar.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                setT(volTxt, String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { draggingVol = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { draggingVol = false; syncVolume(); }
        });

        unitTv.setText(mph() ? "MPH" : "KM/H");
        applyOrientation(getResources().getConfiguration());
        registerReceivers();
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }

        buildDock();
        loadApps(null);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateClock();
        syncVolume();
        startLocation();
        connectMedia();
        applyImmersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Do nothing while another app (maps, player) is in front: saves CPU + RAM on 2GB units.
        stopLocation();
        disconnectMedia();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiversOn) {
            try { unregisterReceiver(timeRx); } catch (Exception ignored) { }
            try { unregisterReceiver(volRx); } catch (Exception ignored) { }
            try { unregisterReceiver(pkgRx); } catch (Exception ignored) { }
        }
        try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        hideDrawer(); // Home pressed while we're already on top
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer();
        // a launcher never finishes on back
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation(c);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) applyImmersive();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !resumed) {
            artBmp = null;
            artIv.setImageDrawable(null); // re-bound on resume
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (resumed) startLocation();
    }

    // ------------------------------------------------------------------ layout

    void applyOrientation(Configuration c) {
        boolean port = c.orientation == Configuration.ORIENTATION_PORTRAIT;
        content.setOrientation(port ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        drivePanel.setLayoutParams(port
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 42f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 38f));
        rightCol.setLayoutParams(port
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 58f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 62f));
    }

    @SuppressWarnings("deprecation")
    void applyImmersive() {
        boolean on = sp.getBoolean("immersive", false);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : 0);
        }
    }

    // ------------------------------------------------------------------ receivers

    final BroadcastReceiver timeRx = new TimeRx();
    class TimeRx extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    final BroadcastReceiver volRx = new VolRx();
    class VolRx extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { syncVolume(); }
    };
    final BroadcastReceiver pkgRx = new PkgRx();
    class PkgRx extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            appsLoaded = false;
            loadApps(null);
        }
    };

    void registerReceivers() {
        IntentFilter t = new IntentFilter();
        t.addAction(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        reg(timeRx, t);

        IntentFilter v = new IntentFilter();
        v.addAction("android.media.VOLUME_CHANGED_ACTION");
        v.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        reg(volRx, v);

        IntentFilter p = new IntentFilter();
        p.addAction(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_CHANGED);
        p.addDataScheme("package");
        reg(pkgRx, p);
        receiversOn = true;
    }

    void reg(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    final ConnectivityManager.NetworkCallback netCb = new NetCb();
    class NetCb extends ConnectivityManager.NetworkCallback {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            final boolean wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            ui.post(() -> wifiIv.setAlpha(wifi ? 1f : 0.25f));
        }
        @Override public void onLost(Network n) {
            ui.post(() -> wifiIv.setAlpha(0.25f));
        }
    };

    // ------------------------------------------------------------------ clock

    void updateClock() {
        Date d = new Date();
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        setT(clockTv, new SimpleDateFormat(pattern, Locale.getDefault()).format(d));
        String dp = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        setT(dateTv, new SimpleDateFormat(dp, Locale.getDefault()).format(d));
    }

    // ------------------------------------------------------------------ speed (GPS)

    final LocationListener locL = new LocL();
    class LocL implements LocationListener {
        @Override public void onLocationChanged(Location l) { onFix(l); }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { setT(gpsTv, "GPS · SEARCHING"); }
        @Override public void onProviderDisabled(String p) { setT(gpsTv, "GPS · OFF"); renderSpeed(-1); }
    };

    final Runnable gpsWatch = new GpsWatch();
    class GpsWatch implements Runnable {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() - lastFix > 4000) {
                if (!"GPS · OFF".contentEquals(gpsTv.getText())) setT(gpsTv, "GPS · SEARCHING");
                renderSpeed(-1);
            }
            ui.postDelayed(this, 3000);
        }
    };

    void startLocation() {
        if (locating) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setT(gpsTv, "GPS · NO PERMISSION");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, locL, Looper.getMainLooper());
            locating = true;
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) setT(gpsTv, "GPS · OFF");
            else setT(gpsTv, "GPS · SEARCHING");
            ui.postDelayed(gpsWatch, 3000);
        } catch (Exception e) {
            setT(gpsTv, "NO GPS");
        }
    }

    void stopLocation() {
        ui.removeCallbacks(gpsWatch);
        if (!locating) return;
        try { lm.removeUpdates(locL); } catch (Exception ignored) { }
        locating = false;
    }

    void onFix(Location l) {
        lastFix = SystemClock.elapsedRealtime();
        float s = l.hasSpeed() ? l.getSpeed() : 0f;
        if (s < 0.6f) s = 0f; // kill standstill jitter
        renderSpeed(s);
        setT(gpsTv, l.hasAccuracy() ? "GPS · ±" + Math.round(l.getAccuracy()) + " M" : "GPS · LOCKED");
        if (l.hasBearing() && s > 1.5f) setT(headingTv, "HEADING " + compass(l.getBearing()));
    }

    void renderSpeed(float ms) {
        lastSpeed = ms;
        if (ms < 0) { setT(speedTv, "--"); return; }
        setT(speedTv, Integer.toString(Math.round(ms * (mph() ? 2.23694f : 3.6f))));
    }

    static String compass(float b) {
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[Math.round((((b % 360) + 360) % 360) / 45f) % 8];
    }

    boolean mph() { return sp.getBoolean("mph", false); }

    void toggleUnits() {
        sp.edit().putBoolean("mph", !mph()).apply();
        unitTv.setText(mph() ? "MPH" : "KM/H");
        renderSpeed(lastSpeed);
    }

    // ------------------------------------------------------------------ media

    final MediaSessionManager.OnActiveSessionsChangedListener sessL = this::pickController;

    final MediaController.Callback mcb = new Mcb();
    class Mcb extends MediaController.Callback {
        @Override public void onMetadataChanged(MediaMetadata m) { renderMeta(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { renderState(); }
        @Override public void onSessionDestroyed() {
            ui.post(() -> {
                if (!resumed) return;
                try { pickController(msm.getActiveSessions(listenerCn)); }
                catch (Exception e) { setController(null); }
            });
        }
    };

    final Runnable ticker = new Ticker();
    class Ticker implements Runnable {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 1000);
        }
    };

    boolean listenerOk() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName() + "/");
    }

    void connectMedia() {
        if (!listenerOk()) { showNeedAccess(); return; }
        needAccess = false;
        try {
            msm.addOnActiveSessionsChangedListener(sessL, listenerCn, ui);
            pickController(msm.getActiveSessions(listenerCn));
        } catch (SecurityException e) {
            showNeedAccess();
        }
    }

    void disconnectMedia() {
        try { msm.removeOnActiveSessionsChangedListener(sessL); } catch (Exception ignored) { }
        if (ctl != null) { ctl.unregisterCallback(mcb); ctl = null; }
        ui.removeCallbacks(ticker);
    }

    void showNeedAccess() {
        needAccess = true;
        setController(null);
        setT(appTv, "Setup");
        setT(titleTv, "Tap to enable media access");
        setT(artistTv, "Lets CarDash show and control " + playerLabel());
    }

    void pickController(List<MediaController> list) {
        MediaController best = null;
        String pref = player();
        if (list != null) {
            for (MediaController c : list) if (isPlaying(c)) { best = c; break; }
            if (best == null && pref != null)
                for (MediaController c : list) if (pref.equals(c.getPackageName())) { best = c; break; }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        if (best != null && ctl != null && best.getSessionToken().equals(ctl.getSessionToken())) {
            renderMeta();
            renderState();
            return;
        }
        setController(best);
    }

    static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    void setController(MediaController c) {
        if (ctl != null) ctl.unregisterCallback(mcb);
        ctl = c;
        if (c != null) c.registerCallback(mcb, ui);
        renderMeta();
        renderState();
    }

    void renderMeta() {
        if (needAccess) { setArt(null); return; }
        MediaMetadata m = ctl == null ? null : ctl.getMetadata();
        setT(appTv, ctl == null ? playerLabel() : labelOf(ctl.getPackageName()));
        if (m == null) {
            setT(titleTv, ctl == null ? "Nothing playing" : "—");
            setT(artistTv, ctl == null ? "Press play to start " + playerLabel() : "");
            setArt(null);
            duration = 0;
            return;
        }
        String t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        setT(titleTv, t == null ? "Unknown title" : t);
        setT(artistTv, a == null ? "" : a);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        setArt(b);
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    void setArt(Bitmap b) {
        if (b != null && b == artBmp) return;
        artBmp = b;
        if (b == null) {
            artIv.setScaleType(ImageView.ScaleType.CENTER);
            artIv.setImageResource(R.drawable.ic_music);
        } else {
            artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artIv.setImageBitmap(b);
        }
    }

    void renderState() {
        boolean p = ctl != null && isPlaying(ctl);
        playBtn.setImageResource(p ? R.drawable.ic_pause : R.drawable.ic_play);
        updateProgress();
        ui.removeCallbacks(ticker);
        if (p && resumed) ui.postDelayed(ticker, 1000);
    }

    void updateProgress() {
        PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
        if (s == null || duration <= 0) {
            prog.setProgress(0);
            setT(posTv, "");
            setT(durTv, duration > 0 ? fmt(duration) : "");
            return;
        }
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(pos, duration));
        prog.setProgress((int) (pos * 1000 / duration));
        setT(posTv, fmt(pos));
        setT(durTv, fmt(duration));
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    void playPause() {
        if (needAccess) { openListenerSettings(); return; }
        if (ctl != null) {
            MediaController.TransportControls t = ctl.getTransportControls();
            if (isPlaying(ctl)) t.pause(); else t.play();
            return;
        }
        // No live session: poke the last player with a media key, then open the chosen player if nothing wakes up.
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        final String p = player();
        ui.postDelayed(() -> { if (ctl == null && p != null && resumed) launch(p); }, 1200);
    }

    void skip(boolean next) {
        if (ctl != null) {
            if (next) ctl.getTransportControls().skipToNext();
            else ctl.getTransportControls().skipToPrevious();
        } else {
            sendKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    void sendKey(int code) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    void openPlayer() {
        if (needAccess) { openListenerSettings(); return; }
        if (ctl != null) {
            PendingIntent pi = ctl.getSessionActivity();
            if (pi != null) {
                try { pi.send(); return; } catch (Exception ignored) { }
            }
            launch(ctl.getPackageName());
        } else {
            String p = player();
            if (p != null) launch(p); else pickPlayer();
        }
    }

    String player() {
        String p = sp.getString("player", null);
        if (p != null && installed(p)) return p;
        return installed(AIMP) ? AIMP : null;
    }

    String playerLabel() {
        String p = player();
        return p == null ? "your music app" : labelOf(p);
    }

    void pickPlayer() {
        pickApp("Music player", player(), false, pkg -> {
            sp.edit().putString("player", pkg).apply();
            if (resumed) { disconnectMedia(); connectMedia(); }
        });
    }

    void openListenerSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerCn.flattenToString());
            if (tryStart(i)) return;
        }
        if (tryStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
        // Some head units hide this screen entirely: give the adb fallback.
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle("Media access")
                .setMessage("This head unit hides the notification-access screen. Grant it once over adb:\n\n"
                        + "adb shell cmd notification allow_listener " + listenerCn.flattenToString())
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ volume

    void syncVolume() {
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        if (!draggingVol) volBar.setProgress(v);
        setT(volTxt, muted ? "" : String.valueOf(v));
        muteBtn.setImageResource(muted || v == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume);
    }

    // ------------------------------------------------------------------ apps

    void loadApps(Runnable then) {
        if (then != null) {
            if (appsLoaded) { then.run(); return; }
            afterLoad.add(then);
        }
        if (appsLoading) { appsDirty = true; return; }
        appsLoading = true;
        appsDirty = false;
        final String self = getPackageName();
        bg.execute(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            final List<AppEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || !seen.add(pkg)) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                try { e.icon = toBitmap(ri.loadIcon(pm)); } catch (Exception ignored) { }
                out.add(e);
            }
            final Collator col = Collator.getInstance();
            Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
            ui.post(() -> {
                apps.clear();
                apps.addAll(out);
                byPkg.clear();
                for (AppEntry e : out) byPkg.put(e.pkg, e);
                appsLoaded = true;
                appsLoading = false;
                gridAdapter.notifyDataSetChanged();
                buildDock();
                if (!needAccess && ctl == null) setT(appTv, playerLabel());
                List<Runnable> run = new ArrayList<>(afterLoad);
                afterLoad.clear();
                for (Runnable r : run) r.run();
                if (appsDirty) { appsLoaded = false; loadApps(null); }
            });
        });
    }

    Bitmap toBitmap(Drawable d) {
        Bitmap b = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, iconPx, iconPx);
        d.draw(c);
        return b;
    }

    boolean installed(String pkg) {
        if (byPkg.containsKey(pkg)) return true;
        try { pm.getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    String labelOf(String pkg) {
        AppEntry e = byPkg.get(pkg);
        if (e != null) return e.label;
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception ex) { return pkg; }
    }

    void launch(String pkg) {
        if (pkg == null) return;
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { toast("App not found"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafe(i);
    }

    void openAppInfo(String pkg) {
        startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    void showDrawer() {
        if (!appsLoaded) loadApps(null);
        drawer.setAlpha(0f);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().alpha(1f).setDuration(140).start();
    }

    void hideDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().cancel();
        drawer.setVisibility(View.GONE);
        grid.setSelection(0);
    }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout l = new LinearLayout(MainActivity.this);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setGravity(Gravity.CENTER_HORIZONTAL);
                l.setPadding(dp(4), dp(10), dp(4), dp(10));
                l.setBackgroundResource(R.drawable.ripple_rect);
                ImageView iv = new ImageView(MainActivity.this);
                l.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
                TextView tv = new TextView(MainActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTextColor(getColor(R.color.text2));
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                l.addView(tv, lp);
                v = l;
            }
            AppEntry e = apps.get(p);
            LinearLayout l = (LinearLayout) v;
            ((ImageView) l.getChildAt(0)).setImageBitmap(e.icon);
            ((TextView) l.getChildAt(1)).setText(e.label);
            return v;
        }
    }

    // ------------------------------------------------------------------ dock

    void ensureDockDefaults() {
        if (sp.getBoolean("dockInit", false)) return;
        String[] d = new String[SLOTS];
        d[0] = resolvePkg(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")));
        d[1] = player();
        d[2] = resolvePkg(new Intent(Intent.ACTION_DIAL));
        d[3] = resolvePkg(new Intent(Settings.ACTION_SETTINGS));
        SharedPreferences.Editor ed = sp.edit();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            if (d[i] != null && used.add(d[i])) ed.putString("dock" + i, d[i]);
        }
        ed.putBoolean("dockInit", true).apply();
    }

    String resolvePkg(Intent i) {
        try {
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            String p = ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
            if (p == null || "android".equals(p)) {
                List<ResolveInfo> all = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
                p = all.isEmpty() ? null : all.get(0).activityInfo.packageName;
            }
            return p != null && pm.getLaunchIntentForPackage(p) != null ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    void buildDock() {
        ensureDockDefaults();
        dock.removeAllViews();
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            final String pkg = sp.getString("dock" + i, null);
            ImageButton b = dockButton();
            AppEntry e = pkg == null ? null : byPkg.get(pkg);
            if (e != null && e.icon != null) {
                b.setImageBitmap(e.icon);
                b.setContentDescription(e.label);
            } else if (pkg == null || appsLoaded) {
                b.setImageResource(R.drawable.ic_add);
                b.setPadding(dp(16), dp(16), dp(16), dp(16));
            }
            b.setOnClickListener(v -> {
                if (pkg == null || (appsLoaded && !byPkg.containsKey(pkg))) pickDock(slot);
                else launch(pkg);
            });
            b.setOnLongClickListener(v -> { pickDock(slot); return true; });
            dock.addView(b);
        }
        ImageButton all = dockButton();
        all.setImageResource(R.drawable.ic_apps);
        all.setColorFilter(getColor(R.color.text2));
        all.setPadding(dp(15), dp(15), dp(15), dp(15));
        all.setOnClickListener(v -> showDrawer());
        dock.addView(all);
    }

    ImageButton dockButton() {
        ImageButton b = new ImageButton(this);
        b.setBackgroundResource(R.drawable.bg_slot);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.leftMargin = lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    void pickDock(int slot) {
        pickApp("Dock slot " + (slot + 1), sp.getString("dock" + slot, null), true, pkg -> {
            if (pkg == null) sp.edit().remove("dock" + slot).apply();
            else sp.edit().putString("dock" + slot, pkg).apply();
            buildDock();
        });
    }

    // ------------------------------------------------------------------ dialogs

    void pickApp(String title, String current, boolean allowClear, Consumer<String> done) {
        if (!appsLoaded) {
            toast("Loading apps…");
            loadApps(() -> pickApp(title, current, allowClear, done));
            return;
        }
        final List<AppEntry> list = new ArrayList<>();
        if (allowClear) {
            AppEntry none = new AppEntry();
            none.label = "— Empty slot —";
            list.add(none);
        }
        list.addAll(apps);
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int p) { return list.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                if (v == null) {
                    LinearLayout l = new LinearLayout(MainActivity.this);
                    l.setOrientation(LinearLayout.HORIZONTAL);
                    l.setGravity(Gravity.CENTER_VERTICAL);
                    l.setPadding(dp(20), dp(10), dp(20), dp(10));
                    l.addView(new ImageView(MainActivity.this), new LinearLayout.LayoutParams(dp(36), dp(36)));
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.leftMargin = dp(16);
                    l.addView(tv, lp);
                    v = l;
                }
                AppEntry e = list.get(p);
                LinearLayout l = (LinearLayout) v;
                ((ImageView) l.getChildAt(0)).setImageBitmap(e.icon);
                TextView tv = (TextView) l.getChildAt(1);
                tv.setText(e.label);
                boolean sel = e.pkg != null && e.pkg.equals(current);
                tv.setTextColor(getColor(sel ? R.color.accent : R.color.text));
                return v;
            }
        };
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(title)
                .setAdapter(ad, (d, w) -> done.accept(list.get(w).pkg))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showSettings() {
        final boolean imm = sp.getBoolean("immersive", false);
        final boolean locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        String[] items = {
                "Music player:  " + playerLabel(),
                "Speed units:  " + (mph() ? "mph" : "km/h"),
                "Media access:  " + (listenerOk() ? "granted" : "tap to grant"),
                "Location (speed):  " + (locOk ? "granted" : "tap to grant"),
                "Hide status/nav bars:  " + (imm ? "on" : "off"),
                "Set CarDash as Home app",
                "Reset dock to defaults",
                "Android settings",
        };
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle("CarDash")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: pickPlayer(); break;
                        case 1: toggleUnits(); break;
                        case 2: openListenerSettings(); break;
                        case 3:
                            if (locOk) openAppInfo(getPackageName());
                            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                            break;
                        case 4:
                            sp.edit().putBoolean("immersive", !imm).apply();
                            applyImmersive();
                            break;
                        case 5:
                            if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS)))
                                startSafe(new Intent(Settings.ACTION_SETTINGS));
                            break;
                        case 6: {
                            SharedPreferences.Editor ed = sp.edit().remove("dockInit");
                            for (int i = 0; i < SLOTS; i++) ed.remove("dock" + i);
                            ed.apply();
                            buildDock();
                            break;
                        }
                        case 7: startSafe(new Intent(Settings.ACTION_SETTINGS)); break;
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ utils

    boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void startSafe(Intent i) {
        if (!tryStart(i)) toast("Not available on this unit");
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** setText only when it actually changed: avoids needless relayouts every tick. */
    static void setT(TextView tv, CharSequence s) {
        if (!TextUtils.equals(tv.getText(), s)) tv.setText(s);
    }
}
