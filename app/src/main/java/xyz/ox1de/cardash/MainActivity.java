package xyz.ox1de.cardash;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
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
import android.view.MotionEvent;
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

    // index 0 = Auto: white car on the dark theme, black car on the light theme
    static final String[] CAR_COLOR_NAMES = {"Auto (white at night, black by day)", "White", "Silver", "Grey", "Black", "Red", "Blue", "Beige"};
    static final int[] CAR_COLORS = {0, 0xFFF4F5F7, 0xFFC4C8CE, 0xFF70757D, 0xFF2C2F35, 0xFFC2242C, 0xFF2E5BB8, 0xFFCBB896};
    static final String[] THEME_NAMES = {"Auto: light 5 am – 6 pm", "Auto: real sunrise / sunset (GPS)", "Always dark", "Always light"};
    static final int[] ALERTS = {0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130};

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
    DriveView drive;
    View volCard, settingsBtn, speedBox, mediaCard, prevBtn, nextBtn;
    Look look;
    Booster booster;
    TextView ramTv;
    ClockView saver;
    View mainView;
    boolean saverOn, swallowTouch;
    boolean lastPlaying, lastMuteIcon, iconsInit;
    SimpleDateFormat clockFmt, dateFmt;
    String clockKey;

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
        boolean sys;
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
        iconPx = dp(52); // exactly the drawer icon size: no per-frame bitmap scaling

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
        drive = findViewById(R.id.car);
        volCard = findViewById(R.id.volCard);
        settingsBtn = findViewById(R.id.settings);
        speedBox = findViewById(R.id.speedBox);
        mediaCard = findViewById(R.id.media);
        prevBtn = findViewById(R.id.prev);
        nextBtn = findViewById(R.id.next);
        ramTv = findViewById(R.id.ram);
        saver = findViewById(R.id.saver);
        mainView = findViewById(R.id.main);
        refreshTheme(true); // before the first frame, so there is never a wrong-theme flash
        booster = new Booster(this, sp, ui, listenerCn, new BoostL());
        ramTv.setOnClickListener(v -> manualClean());
        ramTv.setOnLongClickListener(v -> { showOptimizer(); return true; });
        clockTv.setOnLongClickListener(v -> { showSaver(); return true; });
        ui.postDelayed(bootClean, 30_000); // after boot, once the head unit's own services have started
        maybeResumeMusic();
        speedTv.setFontFeatureSettings("tnum"); // fixed-width digits: no jitter as speed changes

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
        drive.setActive(!saverOn);
        updateRam();
        ui.postDelayed(ramTick, 15_000);
        ui.postDelayed(homeClean, 1500);   // you just came back home: tidy up after the app you left
        ui.postDelayed(periodicClean, 3 * 60_000);
        resetIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        drive.setActive(false);
        hideSaver();
        ui.removeCallbacks(ramTick);
        ui.removeCallbacks(homeClean);
        ui.removeCallbacks(periodicClean);
        ui.removeCallbacks(idleR);
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
        hideSaver();
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
            int lightBars = look != null && look.light
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
            w.getDecorView().setSystemUiVisibility(lightBars | (on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : 0));
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
            Uri d = i.getData();
            if (d == null) return;
            // an update sends REMOVED(replacing) + ADDED: only the ADDED matters
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            pendingPkgs.add(d.getSchemeSpecificPart());
            if (booster != null) booster.invalidateCarApps();
            ui.removeCallbacks(pkgFlush);
            ui.postDelayed(pkgFlush, 1500); // coalesce bursts into one refresh
        }
    };

    final Set<String> pendingPkgs = new HashSet<>();
    final Runnable pkgFlush = new PkgFlush();

    /** Refresh only the packages that changed, instead of reloading every icon on the unit. */
    class PkgFlush implements Runnable {
        @Override public void run() {
            final Set<String> pk = new HashSet<>(pendingPkgs);
            pendingPkgs.clear();
            if (!appsLoaded) { if (appsLoading) appsDirty = true; else loadApps(null); return; }
            final String self = getPackageName();
            bg.execute(() -> {
                final Map<String, AppEntry> fresh = new HashMap<>();
                for (String p : pk) {
                    if (p.equals(self)) continue;
                    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(p);
                    List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
                    if (!r.isEmpty()) fresh.put(p, entry(r.get(0)));
                }
                ui.post(() -> {
                    for (int i = apps.size() - 1; i >= 0; i--) if (pk.contains(apps.get(i).pkg)) apps.remove(i);
                    for (String p : pk) byPkg.remove(p);
                    apps.addAll(fresh.values());
                    byPkg.putAll(fresh);
                    final Collator col = Collator.getInstance();
                    Collections.sort(apps, (a, b) -> col.compare(a.label, b.label));
                    gridAdapter.notifyDataSetChanged();
                    buildDock();
                    shareLabels();
                });
            });
        }
    }

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
        boolean h24 = DateFormat.is24HourFormat(this);
        String key = h24 + Locale.getDefault().toString();
        if (!key.equals(clockKey)) { // formatters are cached; rebuilt only if locale / 24h setting changes
            clockKey = key;
            clockFmt = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault());
            dateFmt = new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault());
        }
        setT(clockTv, clockFmt.format(d));
        setT(dateTv, dateFmt.format(d));
        refreshTheme(false);
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
        rememberPlace(l);
    }

    void rememberPlace(Location l) {
        float lat = sp.getFloat("lat", 999f), lon = sp.getFloat("lon", 999f);
        if (Math.abs(lat - l.getLatitude()) + Math.abs(lon - l.getLongitude()) > 0.5) {
            sp.edit().putFloat("lat", (float) l.getLatitude()).putFloat("lon", (float) l.getLongitude()).apply();
            sunDay = -1; // recompute
        }
    }

    void renderSpeed(float ms) {
        lastSpeed = ms;
        drive.setSpeed(ms);
        if (ms < 0) {
            setT(speedTv, "--");
            speedTv.setTextColor(look.text);
            if (saverOn) saver.setSpeed("");
            return;
        }
        int v = Math.round(ms * (mph() ? 2.23694f : 3.6f));
        setT(speedTv, Integer.toString(v));
        if (saverOn) saver.setSpeed(v >= 5 ? v + (mph() ? " MPH" : " KM/H") : "");
        int alert = sp.getInt("alert", 0);
        int col = alert > 0 && v > alert ? look.alert : look.text;
        if (speedTv.getCurrentTextColor() != col) speedTv.setTextColor(col);
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
            ui.postDelayed(this, updateProgress());
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
        if (saverOn) saver.setNowPlaying(nowPlayingLine());
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
            Drawable ph = getDrawable(R.drawable.ic_music).mutate();
            ph.setTint(look.text3);
            artIv.setImageDrawable(ph);
        } else {
            artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artIv.setImageBitmap(b);
        }
    }

    void renderState() {
        boolean p = ctl != null && isPlaying(ctl);
        if (p != lastPlaying || !iconsInit) {
            lastPlaying = p;
            playBtn.setImageResource(p ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        long next = updateProgress();
        ui.removeCallbacks(ticker);
        if (p && resumed && !saverOn) ui.postDelayed(ticker, next);
    }

    /** @return ms until the position next crosses a whole second, so the counter never skips. */
    long updateProgress() {
        PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
        if (s == null || duration <= 0) {
            prog.setProgress(0);
            setT(posTv, "");
            setT(durTv, duration > 0 ? fmt(duration) : "");
            return 1000;
        }
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(pos, duration));
        prog.setProgress((int) (pos * 1000 / duration));
        setT(posTv, fmt(pos));
        setT(durTv, fmt(duration));
        float speed = Math.max(0.25f, Math.abs(s.getPlaybackSpeed()));
        return Math.max(50, (long) ((1000 - pos % 1000) / speed) + 20);
    }

    /** m:ss or h:mm:ss without String.format (which allocates a Formatter + parses a pattern every call). */
    static String fmt(long ms) {
        long s = ms / 1000;
        StringBuilder b = new StringBuilder(8);
        if (s >= 3600) {
            b.append(s / 3600).append(':');
            two(b, (s / 60) % 60);
        } else {
            b.append(s / 60);
        }
        b.append(':');
        two(b, s % 60);
        return b.toString();
    }

    static void two(StringBuilder b, long v) {
        if (v < 10) b.append('0');
        b.append(v);
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
            booster.noteLaunch(ctl.getPackageName());
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
        new AlertDialog.Builder(this, dlg())
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
        boolean off = muted || v == 0;
        if (off != lastMuteIcon || !iconsInit) {
            lastMuteIcon = off;
            muteBtn.setImageResource(off ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        }
        iconsInit = true;
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
        final Set<String> dockPk = dockSet();
        final boolean first = apps.isEmpty();
        bg.execute(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            final Map<String, AppEntry> done = new HashMap<>();
            // 1) Dock first: on a cold boot the dock is usable after ~6 icons, not after all ~100.
            if (first && !dockPk.isEmpty()) {
                final List<AppEntry> quick = new ArrayList<>();
                for (ResolveInfo ri : ris) {
                    String pkg = ri.activityInfo.packageName;
                    if (dockPk.contains(pkg) && !done.containsKey(pkg)) { AppEntry e = entry(ri); done.put(pkg, e); quick.add(e); }
                }
                ui.post(() -> {
                    if (appsLoaded) return;
                    for (AppEntry e : quick) byPkg.put(e.pkg, e);
                    buildDock();
                });
            }
            // 2) Everything else
            final List<AppEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || !seen.add(pkg)) continue;
                AppEntry e = done.get(pkg);
                out.add(e != null ? e : entry(ri));
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
                shareLabels();
                if (!needAccess && ctl == null) setT(appTv, playerLabel());
                List<Runnable> run = new ArrayList<>(afterLoad);
                afterLoad.clear();
                for (Runnable r : run) r.run();
                if (appsDirty) { appsLoaded = false; loadApps(null); }
            });
        });
    }

    AppEntry entry(ResolveInfo ri) {
        AppEntry e = new AppEntry();
        e.pkg = ri.activityInfo.packageName;
        e.label = String.valueOf(ri.loadLabel(pm));
        e.sys = (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        try { e.icon = toBitmap(ri.loadIcon(pm)); } catch (Exception ignored) { }
        return e;
    }

    /** Give the optimiser the app names, so "ZLINK", "Apple CarPlay", "手机互联" etc. are recognised by label too. */
    void shareLabels() {
        if (booster == null) return;
        Map<String, String> m = new HashMap<>();
        for (AppEntry e : apps) m.put(e.pkg, e.label);
        booster.labels = m;
        booster.invalidateCarApps();
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
        booster.noteLaunch(pkg);
        startSafe(i);
    }

    void openAppInfo(String pkg) {
        startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    void showDrawer() {
        if (!appsLoaded) loadApps(null);
        drawer.setAlpha(0f);
        drawer.setVisibility(View.VISIBLE);
        drive.setActive(false); // hidden behind the drawer: don't animate it
        drawer.animate().alpha(1f).setDuration(140).withLayer().start();
    }

    void hideDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().cancel();
        drawer.setVisibility(View.GONE);
        drive.setActive(resumed && !saverOn);
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
                l.setTag(null);
                ImageView iv = new ImageView(MainActivity.this);
                l.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
                TextView tv = new TextView(MainActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
            if (l.getTag() != look) { // recycled view from the other theme
                l.setTag(look);
                l.setBackground(look.rippleRect(dp(16)));
                ((TextView) l.getChildAt(1)).setTextColor(look.text2);
            }
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
        all.setColorFilter(look.text2);
        all.setPadding(dp(15), dp(15), dp(15), dp(15));
        all.setOnClickListener(v -> showDrawer());
        dock.addView(all);
    }

    ImageButton dockButton() {
        ImageButton b = new ImageButton(this);
        b.setBackground(look.slot(dp(16)));
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
                tv.setTextColor(sel ? look.accent : look.text);
                return v;
            }
        };
        new AlertDialog.Builder(this, dlg())
                .setTitle(title)
                .setAdapter(ad, (d, w) -> done.accept(list.get(w).pkg))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showSettings() {
        final boolean imm = sp.getBoolean("immersive", false);
        final boolean locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        final int alert = sp.getInt("alert", 0);
        final String unit = mph() ? "mph" : "km/h";
        final int saverMin = saverMinutes();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Music player:  " + playerLabel());                           acts.add(this::pickPlayer);
        labels.add("Theme:  " + THEME_NAMES[themeMode()]);                       acts.add(this::pickTheme);
        labels.add("Car colour:  " + CAR_COLOR_NAMES[carColorIndex()]);          acts.add(this::pickCarColor);
        labels.add("Speed units:  " + unit);                                     acts.add(this::toggleUnits);
        labels.add("Speed alert:  " + (alert == 0 ? "off" : alert + " " + unit)); acts.add(this::pickAlert);
        labels.add("Screensaver:  " + (saverMin == 0 ? "off" : "after " + saverMin + " min idle")); acts.add(this::pickSaver);
        labels.add("RAM optimiser…");                                            acts.add(this::showOptimizer);
        final boolean autoPlay = sp.getBoolean("autoPlay", false);
        labels.add("Resume music after start:  " + (autoPlay ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("autoPlay", !autoPlay).apply(); toast("Resume music after start: " + (!autoPlay ? "on" : "off")); });
        labels.add("Media access:  " + (listenerOk() ? "granted" : "tap to grant")); acts.add(this::openListenerSettings);
        labels.add("Location (speed):  " + (locOk ? "granted" : "tap to grant"));
        acts.add(() -> {
            if (locOk) openAppInfo(getPackageName());
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        });
        labels.add("Hide status/nav bars:  " + (imm ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("immersive", !imm).apply(); applyImmersive(); });
        labels.add("Set CarDash as Home app");
        acts.add(() -> { if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS))) startSafe(new Intent(Settings.ACTION_SETTINGS)); });
        labels.add("Reset dock to defaults");
        acts.add(() -> {
            SharedPreferences.Editor ed = sp.edit().remove("dockInit");
            for (int i = 0; i < SLOTS; i++) ed.remove("dock" + i);
            ed.apply();
            buildDock();
        });
        labels.add("Android settings");                                          acts.add(() -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
        new AlertDialog.Builder(this, dlg())
                .setTitle("CarDash")
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    int carColorIndex() {
        int c = sp.getInt("carColor", 0);
        for (int i = 0; i < CAR_COLORS.length; i++) if (CAR_COLORS[i] == c) return i;
        return 0;
    }

    void pickCarColor() {
        new AlertDialog.Builder(this, dlg())
                .setTitle("Car colour")
                .setSingleChoiceItems(CAR_COLOR_NAMES, carColorIndex(), (d, w) -> {
                    sp.edit().putInt("carColor", CAR_COLORS[w]).apply();
                    drive.setCarColor(carColor());
                    d.dismiss();
                })
                .show();
    }

    // ------------------------------------------------------------------ theme

    int sunDay = -1;
    int[] sun; // {sunriseMin, sunsetMin} for sunDay

    int themeMode() { return sp.getInt("theme", 0); }

    int carColor() {
        int c = sp.getInt("carColor", 0);
        return c == 0 ? look.carAuto : c;
    }

    int dlg() { return look != null && look.light ? R.style.DialogLight : R.style.Dialog; }

    /** Night = 6 pm to 5 am, or real sunset to sunrise when that mode is on and a location is known. */
    boolean isNight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        int rise = 5 * 60, set = 18 * 60;
        if (themeMode() == 1) {
            float lat = sp.getFloat("lat", 999f), lon = sp.getFloat("lon", 999f);
            if (lat == 999f) {
                Location l = lastKnown();
                if (l != null) { rememberPlace(l); lat = (float) l.getLatitude(); lon = (float) l.getLongitude(); }
            }
            if (lat != 999f) {
                int day = cal.get(java.util.Calendar.YEAR) * 400 + cal.get(java.util.Calendar.DAY_OF_YEAR);
                if (day != sunDay) { sunDay = day; sun = Sun.times(lat, lon, cal); }
                if (sun != null) { rise = sun[0]; set = sun[1]; }
            }
        }
        return min >= set || min < rise;
    }

    Location lastKnown() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null) return l; } catch (Exception ignored) { }
        }
        return null;
    }

    /** Cheap: called every minute. Only touches views when the theme actually flips. */
    void refreshTheme(boolean force) {
        boolean night = isNight();
        drive.setNight(night);
        int mode = themeMode();
        boolean light = mode == 3 || (mode < 2 && !night);
        Look want = light ? Look.LIGHT : Look.DARK;
        if (want != look || force) applyTheme(want);
    }

    void applyTheme(Look L) {
        look = L;
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(L.bg)); // the only full-screen fill (root has none)
        w.setStatusBarColor(L.bg);
        w.setNavigationBarColor(L.bg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(L.light ? mask : 0, mask);
            }
        }
        applyImmersive();

        int one = Math.max(1, dp(1));
        drivePanel.setBackground(L.card(dp(20), one));
        mediaCard.setBackground(L.card(dp(20), one));
        volCard.setBackground(L.card(dp(20), one));
        settingsBtn.setBackground(L.card(dp(20), one));
        settingsBtn.setForeground(L.rippleRect(dp(20)));
        speedBox.setBackground(L.rippleRect(dp(16)));
        artIv.setBackground(L.round(L.cardHi, dp(14)));
        dock.setBackground(L.dockBg(one));
        drawer.setBackgroundColor(L.drawer);

        speedTv.setTextColor(L.text);
        clockTv.setTextColor(L.text);
        titleTv.setTextColor(L.text);
        unitTv.setTextColor(L.text2);
        dateTv.setTextColor(L.text2);
        artistTv.setTextColor(L.text2);
        volTxt.setTextColor(L.text2);
        gpsTv.setTextColor(L.text3);
        if (ramTv != null) { ramTv.setTextColor(L.text3); ramTv.setBackground(L.rippleRect(dp(8))); }
        headingTv.setTextColor(L.text3);
        posTv.setTextColor(L.text3);
        durTv.setTextColor(L.text3);
        appTv.setTextColor(L.accent);

        ColorStateList t1 = L.csl(L.text), t2 = L.csl(L.text2), ln = L.csl(L.line);
        wifiIv.setImageTintList(t2);
        ((ImageButton) prevBtn).setImageTintList(t1);
        ((ImageButton) nextBtn).setImageTintList(t1);
        prevBtn.setBackground(L.rippleRound());
        nextBtn.setBackground(L.rippleRound());
        playBtn.setBackground(L.play());
        playBtn.setImageTintList(L.csl(L.bg));
        muteBtn.setImageTintList(t2);
        muteBtn.setBackground(L.rippleRound());
        ((ImageButton) settingsBtn).setImageTintList(t2);
        prog.setProgressTintList(t1);
        prog.setProgressBackgroundTintList(ln);
        volBar.setProgressTintList(t1);
        volBar.setProgressBackgroundTintList(ln);
        volBar.setThumbTintList(t1);

        drive.setLight(L.light);
        drive.setCarColor(carColor());
        if (artBmp == null) setArt(null);
        renderSpeed(lastSpeed);
        if (gridAdapter != null) { gridAdapter.notifyDataSetChanged(); buildDock(); }
    }

    void pickTheme() {
        new AlertDialog.Builder(this, dlg())
                .setTitle("Theme")
                .setSingleChoiceItems(THEME_NAMES, themeMode(), (d, w) -> {
                    sp.edit().putInt("theme", w).apply();
                    sunDay = -1;
                    d.dismiss();
                    refreshTheme(false);
                })
                .show();
    }

    // ------------------------------------------------------------------ resume music after start

    /** Opt-in. Once per boot: if nothing is playing ~8 s after start, press "play" for the last player. */
    void maybeResumeMusic() {
        if (!sp.getBoolean("autoPlay", false)) return;
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (Math.abs(sp.getLong("autoPlayBoot", 0) - bootAt) < 120_000) return; // already done this boot
        sp.edit().putLong("autoPlayBoot", bootAt).apply();
        ui.postDelayed(() -> {
            if (ctl != null && isPlaying(ctl)) return;
            if (ctl != null) ctl.getTransportControls().play();
            else sendKey(KeyEvent.KEYCODE_MEDIA_PLAY); // Android routes this to the last music app, even if closed
        }, 8000);
    }

    // ------------------------------------------------------------------ screensaver

    int saverMinutes() { return sp.getInt("saverMin", 2); }

    final Runnable idleR = new IdleR();
    class IdleR implements Runnable {
        @Override public void run() {
            if (!resumed || saverOn) return;
            if (drawer.getVisibility() == View.VISIBLE || lastSpeed > 2.8f) { // drawer open / driving: wait
                ui.postDelayed(this, 30_000);
                return;
            }
            showSaver();
        }
    }

    void resetIdle() {
        ui.removeCallbacks(idleR);
        int min = saverMinutes();
        if (min > 0 && resumed && !saverOn) ui.postDelayed(idleR, min * 60_000L);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdle();
    }

    /** While the saver is up, a touch only wakes the dashboard; it never presses what is underneath. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (saverOn && a == MotionEvent.ACTION_DOWN) {
            hideSaver();
            swallowTouch = true;
        }
        if (swallowTouch) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallowTouch = false;
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverOn && e.getAction() == KeyEvent.ACTION_DOWN) hideSaver();
        return super.dispatchKeyEvent(e);
    }

    String nowPlayingLine() {
        MediaMetadata m = ctl == null ? null : ctl.getMetadata();
        if (m == null) return "";
        String t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (t == null) return "";
        return "\u266A  " + t + (a == null ? "" : "  \u00B7  " + a);
    }

    void showSaver() {
        if (saverOn || saver == null) return;
        saverOn = true;
        ui.removeCallbacks(idleR);
        ui.removeCallbacks(ticker);
        saver.setNowPlaying(nowPlayingLine());
        int v = lastSpeed < 0 ? 0 : Math.round(lastSpeed * (mph() ? 2.23694f : 3.6f));
        saver.setSpeed(v >= 5 ? v + (mph() ? " MPH" : " KM/H") : "");
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.start();
        saver.animate().alpha(1f).setDuration(700).withLayer()
                .withEndAction(() -> { if (saverOn) mainView.setVisibility(View.INVISIBLE); }) // nothing drawn underneath
                .start();
        drive.setActive(false);
    }

    void hideSaver() {
        if (!saverOn) return;
        saverOn = false;
        saver.animate().cancel();
        mainView.setVisibility(View.VISIBLE);
        saver.stop();
        saver.setVisibility(View.GONE);
        drive.setActive(resumed);
        if (resumed) renderState(); // restart the progress ticker
        resetIdle();
    }

    void pickSaver() {
        final int[] opts = {0, 1, 2, 5, 10, 15};
        String[] names = new String[opts.length];
        int cur = 0;
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i] == 0 ? "Off" : "After " + opts[i] + " min idle (when parked)";
            if (opts[i] == saverMinutes()) cur = i;
        }
        new AlertDialog.Builder(this, dlg())
                .setTitle("Screensaver  ·  long-press the clock to start it now")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    sp.edit().putInt("saverMin", opts[w]).apply();
                    d.dismiss();
                    resetIdle();
                })
                .show();
    }

    // ------------------------------------------------------------------ RAM optimiser

    final Runnable ramTick = new RamTick();
    class RamTick implements Runnable {
        @Override public void run() { updateRam(); ui.postDelayed(this, 15_000); }
    }

    final Runnable homeClean = new HomeClean();
    class HomeClean implements Runnable {
        @Override public void run() { booster.auto(dockSet(), player()); }
    }

    final Runnable periodicClean = new PeriodicClean();
    class PeriodicClean implements Runnable {
        @Override public void run() { booster.auto(dockSet(), player()); ui.postDelayed(this, 3 * 60_000); }
    }

    final Runnable bootClean = new BootClean();
    class BootClean implements Runnable {
        @Override public void run() { booster.lastAuto = 0; booster.auto(dockSet(), player()); }
    }

    Set<String> dockSet() {
        Set<String> d = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) { String p = sp.getString("dock" + i, null); if (p != null) d.add(p); }
        return d;
    }

    void updateRam() {
        int pct = booster.usedPct();
        setT(ramTv, "RAM " + pct + "%");
        int col = pct >= 88 ? look.alert : look.text3;
        if (ramTv.getCurrentTextColor() != col) ramTv.setTextColor(col);
    }

    void manualClean() {
        if (booster.busy) return;
        setT(ramTv, "CLEANING");
        booster.cleanNow(dockSet(), player());
    }

    static String mb(long bytes) { return Math.round(bytes / 1048576f) + " MB"; }

    class BoostL implements Booster.Listener {
        @Override public void onCleaned(Booster.Result r) {
            updateRam();
            if (!r.manual) return; // automatic passes are silent
            String used = "RAM " + Math.round(100f * (r.total - r.avail) / r.total) + "% used";
            if (r.freed < 8L << 20 && r.stopped <= 0) toast("Already lean  ·  " + used);
            else toast("Freed " + mb(r.freed) + (r.stopped > 0 ? " from " + r.stopped + " apps" : "") + "  ·  " + used);
        }

        @Override public void onAutoBlocked(String pkg) {
            toast(labelOf(pkg) + " kept restarting itself, so it is now blocked from running in the background. "
                    + "Undo in RAM optimiser.");
        }

        @Override public void onDeepResult(boolean ok) {
            toast(ok ? "Deep mode on (root granted)" : "Root was not granted: staying in normal mode");
            showOptimizer();
        }
    }

    void showOptimizer() {
        ActivityManager.MemoryInfo m = booster.mem();
        final boolean rootPresent = RootShell.present(), deep = booster.deepOn();
        final Set<String> never = booster.set("boostNever"), always = booster.set("boostAlways"),
                blocked = booster.set("boostBlocked");
        long lastAt = sp.getLong("lastAt", 0);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Clean now");
        acts.add(this::manualClean);
        labels.add("Automatic:  " + (booster.autoOn() ? "on — acts only when RAM is tight" : "off"));
        acts.add(() -> { sp.edit().putBoolean("boostAuto", !booster.autoOn()).apply(); showOptimizer(); });
        labels.add("Keep dock apps ready:  " + (booster.keepDock() ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("boostDock", !booster.keepDock()).apply(); showOptimizer(); });
        labels.add("Strength:  " + (booster.mode() == Policy.BALANCED
                ? "Balanced — acts below 30% free RAM" : "Gentle — acts below 20% free RAM (recommended)"));
        acts.add(() -> {
            sp.edit().putInt("boostMode", booster.mode() == Policy.BALANCED ? Policy.GENTLE : Policy.BALANCED).apply();
            showOptimizer();
        });
        labels.add("Protected car & phone-link apps…");
        acts.add(this::showCarApps);
        final boolean ua = booster.usageAccess();
        labels.add("Usage access:  " + (ua ? "granted — sees every app you open" : "off — tap to grant (smarter, optional)"));
        acts.add(() -> {
            if (ua || !tryStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                adbHint("Usage access", "adb shell appops set " + getPackageName() + " GET_USAGE_STATS allow");
        });
        labels.add("Never stop…  (" + never.size() + ")");
        acts.add(() -> pickAppSet("Never stop these apps", "boostNever", false));
        labels.add("Always stop in background…  (" + always.size() + ")");
        acts.add(() -> pickAppSet("Always stop when in background", "boostAlways", true));
        labels.add("Deep mode (root):  " + (deep ? "on" : rootPresent ? "off — tap to enable" : "needs a rooted unit"));
        acts.add(() -> {
            if (deep) { booster.disableDeep(); toast("Deep mode off"); showOptimizer(); }
            else if (rootPresent) { toast("Asking for root…"); booster.enableDeep(); }
            else toast("No root found on this head unit. Normal mode is active and safe.");
        });
        if (deep) {
            labels.add("Auto-block apps that keep restarting:  " + (booster.autoBlockOn() ? "on" : "off"));
            acts.add(() -> { sp.edit().putBoolean("boostAutoBlock", !booster.autoBlockOn()).apply(); showOptimizer(); });
            labels.add("Blocked from background…  (" + blocked.size() + ")");
            acts.add(this::showBlocked);
        }
        labels.add(lastAt == 0 ? "Last clean:  not yet"
                : "Last clean:  freed " + mb(sp.getLong("lastFreed", 0)) + ", "
                + android.text.format.DateUtils.getRelativeTimeSpanString(lastAt));
        acts.add(() -> { });
        new AlertDialog.Builder(this, dlg())
                .setTitle("RAM  ·  " + mb(m.availMem) + " free of " + mb(m.totalMem))
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    void adbHint(String what, String cmd) {
        new AlertDialog.Builder(this, dlg())
                .setTitle(what)
                .setMessage("If this head unit hides the setting, grant it once from a computer:\n\n" + cmd)
                .setPositiveButton("OK", (d, w) -> showOptimizer())
                .show();
    }

    /** Lists everything the optimiser will never stop because a car or phone-link feature depends on it. */
    void showCarApps() {
        final Map<String, String> visible = new HashMap<>(); // snapshot on the UI thread
        for (AppEntry e : apps) visible.put(e.pkg, e.label);
        booster.ex.execute(() -> {
            Set<String> s = booster.carApps();
            final List<String> first = new ArrayList<>(), rest = new ArrayList<>();
            for (String p : s) {
                String l = visible.get(p);
                if (l != null) { first.add(l); continue; }      // apps you can see in the drawer
                String bgLabel = p;                             // services / system parts
                try { bgLabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))); } catch (Exception ignored) { }
                rest.add(bgLabel + "   (background)");
            }
            Collections.sort(first, String.CASE_INSENSITIVE_ORDER);
            Collections.sort(rest, String.CASE_INSENSITIVE_ORDER);
            first.addAll(rest);
            ui.post(() -> new AlertDialog.Builder(this, dlg())
                    .setTitle("Never stopped (" + first.size() + "): phone links, CarPlay / Android Auto, Bluetooth, cameras, CAN…")
                    .setItems(first.toArray(new String[0]), null)
                    .setPositiveButton("OK", (d, w) -> showOptimizer())
                    .show());
        });
    }

    /** Multi-select over installed apps, saved as a string set. */
    void pickAppSet(final String title, final String key, final boolean warnSystem) {
        if (!appsLoaded) { loadApps(() -> pickAppSet(title, key, warnSystem)); return; }
        final Set<String> cur = booster.set(key);
        final String[] names = new String[apps.size()];
        final boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i);
            names[i] = e.label + (e.sys ? "   (system)" : "");
            on[i] = cur.contains(e.pkg);
        }
        new AlertDialog.Builder(this, dlg())
                .setTitle(title)
                .setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> out = new HashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) out.add(apps.get(i).pkg);
                    booster.putSet(key, out);
                    if (warnSystem) for (String p : out) {
                        AppEntry e = byPkg.get(p);
                        if (e != null && e.sys) { toast("Note: system apps in this list are stopped too"); break; }
                    }
                    showOptimizer();
                })
                .setNegativeButton("Cancel", (d, w) -> showOptimizer())
                .show();
    }

    void showBlocked() {
        final List<String> pk = booster.sorted(booster.set("boostBlocked"));
        final Set<String> auto = booster.set("boostAutoBlocked");
        final List<String> labels = new ArrayList<>();
        labels.add("+  Block an app…");
        for (String p : pk) labels.add(labelOf(p) + (auto.contains(p) ? "   (auto: kept restarting)" : "") + "   — tap to unblock");
        new AlertDialog.Builder(this, dlg())
                .setTitle("Blocked from running in the background")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        pickApp("Block from background", null, false, p -> {
                            if (p == null) return;
                            booster.blockAsync(p);
                            toast(labelOf(p) + " blocked from background");
                        });
                    } else {
                        String p = pk.get(w - 1);
                        booster.unblockAsync(p);
                        toast(labelOf(p) + " unblocked");
                    }
                })
                .setNegativeButton("Back", (d, w) -> showOptimizer())
                .show();
    }

    void pickAlert() {
        final String unit = mph() ? " mph" : " km/h";
        String[] names = new String[ALERTS.length];
        int cur = 0, a = sp.getInt("alert", 0);
        for (int i = 0; i < ALERTS.length; i++) {
            names[i] = ALERTS[i] == 0 ? "Off" : "Above " + ALERTS[i] + unit;
            if (ALERTS[i] == a) cur = i;
        }
        new AlertDialog.Builder(this, dlg())
                .setTitle("Speed alert (number turns red)")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    sp.edit().putInt("alert", ALERTS[w]).apply();
                    renderSpeed(lastSpeed);
                    d.dismiss();
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
