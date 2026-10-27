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
    static final String[] CAR_COLOR_NAMES = {"Auto (white at night, black by day)", "White", "Silver", "Grey", "Black", "Red", "Blue", "Teal", "Orange", "Beige"};
    static final int[] CAR_COLORS = {0, 0xFFF4F5F7, 0xFFC4C8CE, 0xFF70757D, 0xFF2C2F35, 0xFFC2242C, 0xFF2E5BB8, 0xFF1E8C93, 0xFFE0702A, 0xFFCBB896};
    static final String[] CAR_STYLE_NAMES = {"Hatchback", "Sedan"};
    static final String[] THEME_NAMES = {"Auto: light 5 am – 6 pm", "Auto: real sunrise / sunset (GPS)", "Always dark", "Always light"};
    static final int[] ALERTS = {0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130};

    final Handler ui = new Handler(Looper.getMainLooper());
    /** Background work (app icons, badge import, …) at background priority: never steals CPU from an animation. */
    final ExecutorService bg = lowPriority("cardash-bg");

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
    Updater updater;
    TextView updChip;
    AlertDialog updDlg;
    boolean updManual, updInstallWhenReady;
    String updAutoTried;   // the release the parked auto-install already tried, so a cancel isn't retried every 30 s
    TextView ramTv;
    // optional: a music app's own widget in place of the music card
    android.appwidget.AppWidgetHost widgetHost;
    android.appwidget.AppWidgetManager awm;
    WidgetCard widgetCard;
    android.appwidget.AppWidgetHostView widgetView;
    int widgetW, widgetH;
    boolean widgetListening;

    void widgetListen(boolean on) {
        if (on == widgetListening) return;
        widgetListening = on;
        try { if (on) widgetHost.startListening(); else widgetHost.stopListening(); } catch (Exception ignored) { }
    }
    View greetBox;
    TextView greetLabel, greetName;
    boolean greetShown, greetWelcome, greetPendingWelcome;
    int greetMoving;
    float greetNamePx;
    long stoppedAt, saverAt, sleepGap = -1;
    ClockView saver;
    View mainView;
    boolean saverOn, swallowTouch;
    View camView, camBar, camBtn;
    AspectFrame camFrame;
    android.view.SurfaceView camSurface;
    TextView camStatus, camInfo, camFront, camRear, camLive;
    DashCam cam;
    boolean camOpen, camRearOn, camHasFailed;
    boolean lastPlaying, lastMuteIcon, iconsInit, lastSkipBack;
    String trackKey;
    SimpleDateFormat clockFmt, dateFmt;
    String clockKey;

    final List<AppEntry> apps = new ArrayList<>();
    final Map<String, AppEntry> byPkg = new HashMap<>();
    final List<Runnable> afterLoad = new ArrayList<>();
    boolean appsLoaded, appsLoading, appsDirty;
    AppAdapter gridAdapter;

    MediaController ctl;
    Bitmap artBmp;
    long artSig;   // fingerprint of the picture on screen: players re-send the same art several times per track
    int artGen;
    /** Album art is scaled to the card's size and colour-picked here, never on the UI thread. */
    final ExecutorService artEx = lowPriority("art");

    static ExecutorService lowPriority(final String name) {
        return Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, name));
    }
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
        drivePanel.addOnLayoutChangeListener((v, pl, pt, pr, pb, ol, ot, or, ob) -> {
            if (pr - pl != panelW || pb - pt != panelH) { panelW = pr - pl; panelH = pb - pt; ui.post(this::sizeDrivePanel); }
        });
        rightCol = findViewById(R.id.rightCol);
        dock = findViewById(R.id.dock);
        drawer = findViewById(R.id.drawer);
        dock.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> { if (r - l != or - ol && or - ol > 0) ui.post(this::buildDock); });
        grid = findViewById(R.id.grid);
        drive = findViewById(R.id.car);
        drive.setSky(findViewById(R.id.sky));
        volCard = findViewById(R.id.volCard);
        settingsBtn = findViewById(R.id.settings);
        speedBox = findViewById(R.id.speedBox);
        mediaCard = findViewById(R.id.media);
        prevBtn = findViewById(R.id.prev);
        nextBtn = findViewById(R.id.next);
        ramTv = findViewById(R.id.ram);
        saver = findViewById(R.id.saver);
        mainView = findViewById(R.id.main);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, in) -> { applyInsets(in); return in; });
        camBtn = findViewById(R.id.camBtn);
        greetBox = findViewById(R.id.greet);
        greetLabel = findViewById(R.id.greetLabel);
        greetName = findViewById(R.id.greetName);
        widgetCard = findViewById(R.id.widgetCard);
        refreshTheme(true); // before the first frame, so there is never a wrong-theme flash
        drive.welcome();    // car fades in, lamps flash once
        booster = new Booster(this, sp, ui, listenerCn, new BoostL());
        updChip = findViewById(R.id.updChip);
        updater = new Updater(this, sp, ui, this::onUpdateState);
        InstallReceiver.active = updater;
        updChip.setOnClickListener(v -> showUpdateDialog());
        styleUpdChip();
        Anim.press(updChip, 0.9f);
        String ver = updater.installedName(), lastVer = sp.getString("lastVer", null);
        if (lastVer != null && !lastVer.equals(ver)) ui.postDelayed(() -> toast("Updated to CarDash " + ver), 1500);
        if (!ver.equals(lastVer)) sp.edit().putString("lastVer", ver).apply();
        setupCam();
        greetBox.setOnClickListener(v -> { if (greetUser().isEmpty()) editGreetName(); else hideGreeting(true); });
        greetBox.setOnLongClickListener(v -> { editGreetName(); return true; });
        Anim.press(greetBox, 0.96f);
        // First start after install, or the first start since the head unit booted: "Welcome"
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (!sp.contains("greetBoot") || Math.abs(sp.getLong("greetBoot", 0) - bootAt) > 120_000) {
            sp.edit().putLong("greetBoot", bootAt).apply();
            queueGreeting(true, b == null ? 650 : 200); // after the cards have risen in
        }
        ramTv.setOnClickListener(v -> manualClean());
        ramTv.setOnLongClickListener(v -> { showOptimizer(); return true; });
        clockTv.setOnLongClickListener(v -> { showSaver(); return true; });
        ui.postDelayed(bootClean, 30_000); // after boot, once the head unit's own services have started
        maybeResumeMusic();
        speedTv.setFontFeatureSettings("tnum"); // fixed-width digits: no jitter as speed changes

        artIv.setClipToOutline(true);
        mediaCard.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) ui.post(this::sizeMediaButtons);
        });

        // touch feedback: everything you can press gives a little (GPU scale, no redraw)
        for (View v : new View[]{playBtn, prevBtn, nextBtn, muteBtn, settingsBtn, camBtn, ramTv}) if (v != null) Anim.press(v, 0.88f);
        mediaCard.setOnTouchListener(new Swipe(artIv, getResources().getDisplayMetrics().density, this::skip)); // swipe = next / previous
        widgetCard.onLongPress = this::showWidgetMenu;
        widgetCard.setClipToOutline(true); // the widget gets the card's rounded corners
        widgetCard.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> sizeWidget());
        awm = android.appwidget.AppWidgetManager.getInstance(this);
        widgetHost = new android.appwidget.AppWidgetHost(getApplicationContext(), WIDGET_HOST_ID);
        restoreWidget();
        Anim.press(speedBox, 0.95f);
        if (b == null) { // cold start: the dashboard rises in, card by card
            float d = dp(26);
            Anim.rise(drivePanel, d, 0);
            Anim.rise(rightCol, d, 80);
            Anim.rise(dock, d, 160);
        }

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
        updateCamSession(); // Dash cam layout: bring the tile back
        if (dashLayout()) ui.postDelayed(linkCheck, 5000);
        updateRam();
        ui.postDelayed(ramTick, 15_000);
        ui.postDelayed(homeClean, 1500);   // you just came back home: tidy up after the app you left
        ui.postDelayed(periodicClean, 3 * 60_000);
        if (updater.dueForAutoCheck()) ui.postDelayed(updCheckR, 25_000); // give the unit's network time to come up
        if (updater.state == Updater.State.READY) ui.postDelayed(updInstallR, 30_000);
        resetIdle();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // widget updates only while the home screen is on show (they queue up and arrive here otherwise)
        if (widgetView != null) widgetListen(true);
        // Back after 10+ minutes away (ignition off, screen off, a long time in another app): new drive, new greeting
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= NEW_SESSION_MS) queueGreeting(false, 700);
        stoppedAt = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        widgetListen(false);
        stoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        drive.setActive(false);
        hideSaver();
        closeCam(); // leaving home: give the Wi-Fi back
        ui.removeCallbacks(linkCheck);
        updateCamSession();
        ui.removeCallbacks(ramTick);
        ui.removeCallbacks(homeClean);
        ui.removeCallbacks(periodicClean);
        ui.removeCallbacks(updCheckR);
        ui.removeCallbacks(updInstallR);
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
        artEx.shutdownNow();
        updater.ex.shutdown();
        if (InstallReceiver.active == updater) InstallReceiver.active = null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        hideDrawer(); // Home pressed while we're already on top
        hideSaver();
        closeCam();
    }

    @Override
    public void onBackPressed() {
        if (camOpen) { closeCam(); return; }
        if (drawerOpen) hideDrawer();
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
            artSig = 0;
            artIv.setImageDrawable(null); // re-bound on resume
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 2) { // camera, for the rear view
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) startRear(); else if (camOpen && camRearOn) setCamStatus("Camera access was refused, so the rear view can't be shown.");
            return;
        }
        if (resumed) startLocation();
    }

    // ------------------------------------------------------------------ layout

    void applyOrientation(Configuration c) {
        boolean port = c.orientation == Configuration.ORIENTATION_PORTRAIT;
        content.setOrientation(port ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        drivePanel.setLayoutParams(port
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 42f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, dashLayout() ? 34f : 38f));
        rightCol.setLayoutParams(port
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 58f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, dashLayout() ? 66f : 62f));
    }

    static final String[] BAR_NAMES = {"Show status bar + nav bar", "Hide status bar (more room, swipe down to peek)", "Hide status bar + nav bar"};

    /** 0 = show both, 1 = hide the status bar (default: most head units leave it empty), 2 = hide both. */
    int barsMode() {
        if (!sp.contains("bars")) return sp.getBoolean("immersive", false) ? 2 : 1; // older setting
        return Math.max(0, Math.min(2, sp.getInt("bars", 1)));
    }

    /**
     * Edge to edge: the window always covers the whole screen and CarDash pads itself only for the
     * bars that are really there. Head units often keep reporting the space of a status bar that was
     * hidden, which left an empty strip at the top; a hidden bar now gets no room at all (if you swipe
     * it down it slides over the dashboard for a moment).
     */
    @SuppressWarnings("deprecation")
    void applyInsets(WindowInsets in) {
        int mode = barsMode();
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            if (mode == 0 && in.isVisible(WindowInsets.Type.statusBars())) t = in.getInsets(WindowInsets.Type.statusBars()).top;
            if (mode < 2 && in.isVisible(WindowInsets.Type.navigationBars())) {
                android.graphics.Insets n = in.getInsets(WindowInsets.Type.navigationBars());
                l = n.left; r = n.right; b = n.bottom;
                if (mode == 0) t = Math.max(t, n.top);
            }
            android.graphics.Insets cut = in.getInsets(WindowInsets.Type.displayCutout());
            l = Math.max(l, cut.left); r = Math.max(r, cut.right); b = Math.max(b, cut.bottom);
            if (mode == 0) t = Math.max(t, cut.top);
        } else {
            if (mode == 0) t = in.getSystemWindowInsetTop();
            if (mode < 2) { l = in.getSystemWindowInsetLeft(); r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom(); }
        }
        if (mainView.getPaddingTop() != t || mainView.getPaddingBottom() != b || mainView.getPaddingLeft() != l || mainView.getPaddingRight() != r) {
            mainView.setPadding(l, t, r, b);
            drawer.setPadding(l, t, r, 0);   // it ends above the dock, which already clears the nav bar
        }
    }

    @SuppressWarnings("deprecation")
    void applyImmersive() {
        int mode = barsMode();
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (mode == 0) {
                c.show(WindowInsets.Type.systemBars());
            } else if (mode == 1) {
                c.hide(WindowInsets.Type.statusBars());
                c.show(WindowInsets.Type.navigationBars());
            } else {
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            int lightBars = look != null && look.light
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
            int hide = mode == 0 ? 0 : mode == 1 ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    : View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            int edge = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            w.getDecorView().setSystemUiVisibility(lightBars | hide | edge);
        }
        View root = findViewById(R.id.root);
        if (root != null) root.requestApplyInsets();
    }

    void pickBars() {
        new AlertDialog.Builder(this, dlg())
                .setTitle("System bars on the home screen")
                .setSingleChoiceItems(BAR_NAMES, barsMode(), (d, w) -> {
                    sp.edit().putInt("bars", w).remove("immersive").apply();
                    d.dismiss();
                    applyImmersive();
                })
                .show();
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

    // ------------------------------------------------------------------ responsive drive panel

    int panelW, panelH;

    /**
     * Speed and clock are sized from the panel's real size (not fixed sp), so they're big on a
     * 10" 1280x720 unit and still fit side by side on 1024x600 or in the Dash cam layout.
     * Runs only when the panel size changes.
     */
    void sizeDrivePanel() {
        int w = panelW - drivePanel.getPaddingLeft() - drivePanel.getPaddingRight();
        int h = panelH - drivePanel.getPaddingTop() - drivePanel.getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float speed = clamp(h * 0.19f, dp(56), dp(132));
        float clock = clamp(h * 0.135f, dp(40), dp(100));
        // both must fit on one row with room to spare: measure real glyph widths
        android.text.TextPaint sp1 = new android.text.TextPaint(speedTv.getPaint()), cp = new android.text.TextPaint(clockTv.getPaint());
        sp1.setTextSize(speed);
        cp.setTextSize(clock);
        float need = sp1.measureText("188") + cp.measureText("88:88") + dp(18);
        if (need > w) { float k = w / need; speed *= k; clock *= k; }
        float small = clamp(h * 0.03f, dp(12), dp(18)), chip = clamp(h * 0.024f, dp(11), dp(15));
        speedTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, speed);
        clockTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock);
        unitTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, small);
        headingTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, small);
        dateTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, small);
        gpsTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, chip);
        ramTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, chip);
        greetLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, small * 0.92f);
        greetNamePx = clamp(speed * 0.46f, dp(26), dp(64));
        if (greetShown) fitGreetName();
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

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
        // Head units often just suspend on ignition off and keep this screen "open". Suspended time counts
        // in elapsedRealtime but not in uptimeMillis, so a jump in the gap means the unit slept: new drive.
        long gap = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
        if (sleepGap >= 0 && gap - sleepGap >= NEW_SESSION_MS) queueGreeting(false, 700);
        sleepGap = gap;
        if (greetShown && !greetWelcome && !dayGreeting().equals(greetPhrase)) setGreetingText(false, true); // 11:59 -> 12:00
        refreshTheme(false);
        updateCamInfo();
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
        if (greetShown) { // the car moved off: the speed takes the greeting's place (two fixes, so GPS jitter can't do it)
            if (s >= 2f) { if (++greetMoving >= 2 || s >= 4f) hideGreeting(true); }
            else greetMoving = 0;
        }
        setT(gpsTv, l.hasAccuracy() ? "GPS · ±" + Math.round(l.getAccuracy()) + " M" : "GPS · LOCKED");
        if (l.hasBearing() && s > 1.5f) setT(headingTv, "\u00B7  " + compass(l.getBearing()));
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
        updateCamInfo();
        if (ms < 0) {
            if (speedAnim != null) speedAnim.cancel();
            shownV = -1;
            setT(speedTv, "--");
            speedTv.setTextColor(look.text);
            if (saverOn) saver.setSpeed("");
            return;
        }
        int v = Math.round(ms * (mph() ? 2.23694f : 3.6f));
        showSpeedNumber(v);
        if (saverOn) saver.setSpeed(v >= 5 ? v + (mph() ? " MPH" : " KM/H") : "");
        int alert = sp.getInt("alert", 0);
        int col = alert > 0 && v > alert ? look.alert : look.text;
        if (speedTv.getCurrentTextColor() != col) speedTv.setTextColor(col);
    }

    android.animation.ValueAnimator speedAnim;
    int shownV = -1, fromV, toV;

    /**
     * The speed number rolls through the values in between (48, 49, 50, 51) instead of jumping once
     * a second. One reusable animator; the text is only touched when the shown integer changes, and
     * the view's size never changes (fixed-width digits), so it's an invalidate, never a re-layout.
     */
    void showSpeedNumber(int v) {
        if (shownV < 0 || !resumed || saverOn || Math.abs(v - shownV) > 60) {
            if (speedAnim != null) speedAnim.cancel();
            shownV = v;
            setT(speedTv, Integer.toString(v));
            return;
        }
        if (v == toV && speedAnim != null && speedAnim.isRunning()) return;
        if (v == shownV) { if (speedAnim != null) speedAnim.cancel(); toV = v; return; }
        if (speedAnim == null) {
            speedAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            speedAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
            speedAnim.addUpdateListener(a -> {
                int cur = Math.round(fromV + (toV - fromV) * a.getAnimatedFraction());
                if (cur != shownV) { shownV = cur; setT(speedTv, Integer.toString(cur)); }
            });
        }
        speedAnim.cancel();
        fromV = shownV;
        toV = v;
        speedAnim.setDuration(Math.min(800, 160 + 40L * Math.abs(v - fromV)));
        speedAnim.start();
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
        if (c != null) {
            c.registerCallback(mcb, ui);
            String pk = c.getPackageName();
            if (!pk.equals(sp.getString("lastSession", null))) sp.edit().putString("lastSession", pk).apply();
        }
        renderMeta();
        renderState();
    }

    void renderMeta() {
        if (saverOn) saver.setNowPlaying(nowPlayingLine());   // the screensaver follows the track in every mode
        if (widgetView != null) return; // the app's widget is showing the track: nothing to draw here
        if (needAccess) { loadArt(null, false); return; }
        MediaMetadata m = ctl == null ? null : ctl.getMetadata();
        setT(appTv, ctl == null ? playerLabel() : labelOf(ctl.getPackageName()));
        if (m == null) {
            setT(titleTv, ctl == null ? "Nothing playing" : "—");
            setT(artistTv, ctl == null ? "Press play to start " + playerLabel() : "");
            loadArt(null, false);
            duration = 0;
            return;
        }
        String t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        String key = t + "\u0000" + a;
        boolean newTrack = trackKey != null && !key.equals(trackKey);
        trackKey = key;
        setT(titleTv, t == null ? "Unknown title" : t);
        setT(artistTv, a == null ? "" : a);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        loadArt(b, newTrack);
        if (resumed && !saverOn && newTrack) {
            // next track: title + artist slide in now; the art grows in when it's ready (GPU-only animations)
            float d = dp(lastSkipBack ? -18 : 18);
            Anim.slideIn(titleTv, d, 0);
            Anim.slideIn(artistTv, d, 50);
            lastSkipBack = false;
        }
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    /**
     * Players send big art (Spotify 640 px, some 1000 px+) and re-send it with every metadata update.
     * Off the UI thread: copy hardware bitmaps, fingerprint, scale to the size it's shown at, pick its
     * colour. Back on the UI thread only a new picture is applied: one small texture upload per track,
     * so the track-change animation never stutters.
     */
    void loadArt(final Bitmap b, final boolean newTrack) {
        final int g = ++artGen;
        if (b == null) {
            if (artBmp != null || artSig != -1) { artSig = -1; setArt(null); }
            return;
        }
        final int target = Math.max(dp(140), artIv.getHeight());
        artEx.execute(() -> {
            Bitmap small;
            long sig;
            int tint;
            try {
                Bitmap src = b;
                if (Build.VERSION.SDK_INT >= 26 && src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
                sig = artSignature(src);
                int w = src.getWidth(), h = src.getHeight(), m = Math.max(w, h);
                small = m > target * 1.2f ? Bitmap.createScaledBitmap(src, Math.max(1, w * target / m), Math.max(1, h * target / m), true) : src;
                tint = artColor(small);
            } catch (Throwable t) {
                small = b;
                sig = System.identityHashCode(b);
                tint = 0;
            }
            final Bitmap fs = small;
            final long fsig = sig;
            final int ftint = tint;
            ui.post(() -> {
                if (g != artGen) return;                       // a newer track got here first
                if (fsig == artSig && artBmp != null) return;  // the same picture again: nothing to redraw
                boolean arrived = artBmp == null;              // some players send the art a moment after the title
                artSig = fsig;
                showArt(fs, ftint);
                if (resumed && !saverOn && (newTrack || arrived)) Anim.growIn(artIv);
            });
        });
    }

    /** Size + 16 sampled pixels: tells "same picture re-sent" from "new picture" without comparing it all. */
    static long artSignature(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        long sig = w * 31L + h;
        for (int i = 1; i <= 4; i++) for (int j = 1; j <= 4; j++)
            sig = sig * 1_000_003L ^ b.getPixel(w * i / 5, h * j / 5);
        return sig == 0 || sig == -1 ? 1 : sig;
    }

    void setArt(Bitmap b) { showArt(b, b == null ? 0 : artColor(b)); }

    void showArt(Bitmap b, int tint) {
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
        artTint = tint;
        applyMediaTint();
    }

    int artTint; // most vivid colour of the current album art, 0 = none

    /** The art's most vivid colour: 8x8 downscale, pick by saturation x brightness. Runs once per track. */
    static int artColor(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            float[] hsv = new float[3];
            float best = 0;
            int pick = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int c = s.getPixel(x, y);
                android.graphics.Color.colorToHSV(c, hsv);
                float score = hsv[1] * (0.35f + hsv[2]);
                if (score > best) { best = score; pick = c; }
            }
            if (s != b) s.recycle();
            return best < 0.18f ? 0 : (pick | 0xFF000000); // greyscale art: no tint
        } catch (Exception e) {
            return 0;
        }
    }

    /** Media card picks up a soft wash of the album colour; the progress line takes the colour too. */
    void applyMediaTint() {
        if (look == null || mediaCard == null) return;
        int one = Math.max(1, dp(1));
        if (artTint == 0) {
            mediaCard.setBackground(look.card(dp(20), one));
            prog.setProgressTintList(look.csl(look.text));
            return;
        }
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(artTint, hsv);
        int wash = blend(look.card, artTint, look.light ? 0.16f : 0.24f);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{wash, look.card, look.card});
        g.setCornerRadius(dp(20));
        g.setStroke(one, look.line);
        mediaCard.setBackground(g);
        // readable accent: deep on the light theme, bright on the dark one
        float[] a = {hsv[0], Math.max(hsv[1], 0.45f), look.light ? Math.min(hsv[2], 0.55f) : Math.max(hsv[2], 0.85f)};
        if (!look.light) a[1] = Math.min(a[1], 0.65f);
        prog.setProgressTintList(look.csl(android.graphics.Color.HSVToColor(a)));
    }

    static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    void renderState() {
        boolean p = ctl != null && isPlaying(ctl);
        if (saver != null) saver.setMedia(ctl != null && !needAccess, p);
        if (widgetView != null) { ui.removeCallbacks(ticker); return; }
        if (p != lastPlaying || !iconsInit) {
            boolean change = iconsInit && p != lastPlaying;
            lastPlaying = p;
            if (change && resumed && !saverOn) {
                // the play triangle morphs into the pause bars (and back), drawn on the render thread
                Drawable d = getDrawable(p ? R.drawable.avd_play_to_pause : R.drawable.avd_pause_to_play);
                playBtn.setImageDrawable(d);
                if (d instanceof android.graphics.drawable.Animatable) ((android.graphics.drawable.Animatable) d).start();
                Anim.pop(playBtn);
            } else {
                playBtn.setImageResource(p ? R.drawable.ic_pause : R.drawable.ic_play);
            }
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
        int pv = (int) (pos * 1000 / duration);
        prog.setProgress(pv, Math.abs(pv - prog.getProgress()) > 25); // glide on seeks / new track, step otherwise
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
        // No live session. If your chosen player (Spotify, YouTube Music…) isn't the app Android would resume,
        // open it; otherwise poke the last player with a media key, and open the chosen player if nothing wakes up.
        final String p = player();
        String last = sp.getString("lastSession", null);
        if (p != null && sp.contains("player") && last != null && !p.equals(last)) { launch(p); return; }
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        ui.postDelayed(() -> { if (ctl == null && p != null && resumed) launch(p); }, 1200);
    }

    void skip(boolean next) {
        lastSkipBack = !next;
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

    /** Spotify, YouTube Music, AIMP, Poweramp… : every app that can be a car-audio source (what Android Auto looks for). */
    Set<String> musicApps() {
        Set<String> out = new HashSet<>();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0))
                out.add(ri.serviceInfo.packageName);
            for (ResolveInfo ri : pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0))
                out.add(ri.activityInfo.packageName);
        } catch (Exception ignored) { }
        for (String k : KNOWN_PLAYERS) out.add(k);
        out.remove(getPackageName());
        return out;
    }

    static final String[] KNOWN_PLAYERS = {AIMP, "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.aspiro.tidal", "deezer.android.app", "com.apple.android.music", "com.amazon.mp3",
            "com.soundcloud.android", "com.maxmpz.audioplayer", "in.krosbits.musicolet"};

    void pickPlayer() {
        pickApp("Music player  ·  music apps first", player(), false, musicApps(), pkg -> {
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
        if (!draggingVol && v != volBar.getProgress()) volBar.setProgress(v, iconsInit && resumed);
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
        noteRecent(pkg);
        startSafe(i);
    }

    // ------------------------------------------------------------------ system keys (dock + shortcuts panel)

    /** Apps opened from CarDash, newest first (for Back and the built-in recent-apps list). */
    java.util.List<String> recents() {
        java.util.List<String> out = new ArrayList<>();
        for (String p : sp.getString("mru", "").split(",")) if (!p.isEmpty() && installed(p)) out.add(p);
        return out;
    }

    void noteRecent(String pkg) {
        java.util.List<String> l = recents();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > 10) l.remove(l.size() - 1);
        sp.edit().putString("mru", TextUtils.join(",", l)).apply();
    }

    /** Back (on the dock while All apps is open): close it, back to the dashboard. */
    void navBack() {
        if (drawerOpen) { hideDrawer(); return; }
        java.util.List<String> r = recents();
        if (!r.isEmpty()) { launch(r.get(0)); return; }
        if (!KeysService.press(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) toast("Nothing to go back to yet");
    }

    /** Android's recent apps (with CarDash buttons on), else CarDash's own list of apps you opened. */
    void showRecents() {
        if (KeysService.press(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) return;
        final java.util.List<String> r = recents();
        if (!appsLoaded) { loadApps(this::showRecents); return; }
        final java.util.List<AppEntry> list = new ArrayList<>();
        for (String p : r) { AppEntry e = byPkg.get(p); if (e != null) list.add(e); }
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg()).setTitle("Recent apps")
                .setNeutralButton("Use Android's recent apps…", (d, w) -> showKeysSetup());
        if (list.isEmpty()) {
            b.setMessage("Apps you open from CarDash show up here.").setPositiveButton("OK", null).show();
            return;
        }
        b.setAdapter(appListAdapter(list, null), (d, w) -> launch(list.get(w).pkg)).show();
    }

    /** Presses one of Android's own keys; if CarDash buttons isn't switched on, explains how (once). */
    boolean pressKey(int action) {
        if (KeysService.press(action)) return true;
        showKeysSetup();
        return false;
    }

    void showKeysSetup() {
        boolean root = rootUpdates() || RootShell.present();
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg())
                .setTitle("Turn on CarDash buttons")
                .setMessage("Back, Recent apps, Notifications, Quick settings, Screenshot, Lock and the Power menu are "
                        + "Android's own keys. Android lets an app press them only through an accessibility service "
                        + "you switch on once:\n\nSettings → Accessibility → CarDash buttons → On\n\n"
                        + "It only presses those keys. CarDash doesn't read the screen, what you type, or anything "
                        + "from other apps.\n\nSwitch greyed out (\"restricted setting\")? Settings → Apps → CarDash → "
                        + "⋮ → Allow restricted settings, then try again.\n\nNo Accessibility menu on this unit? Over ADB:\n"
                        + "adb shell settings put secure enabled_accessibility_services " + KeysService.component(this).flattenToString())
                .setPositiveButton("Open Accessibility", (d, w) -> {
                    if (!tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast("This head unit hides the Accessibility menu: use the ADB command");
                })
                .setNegativeButton("Cancel", null);
        if (root) b.setNeutralButton("Turn on with root", (d, w) -> enableKeysWithRoot());
        b.show();
    }

    void enableKeysWithRoot() {
        final String cn = KeysService.component(this).flattenToString();
        bg.execute(() -> {
            RootShell sh = RootShell.open();
            String out = null;
            if (sh != null) {
                out = sh.exec("cur=$(settings get secure enabled_accessibility_services); "
                        + "case \"$cur\" in *" + cn + "*) ;; ''|null) settings put secure enabled_accessibility_services " + cn + " ;; "
                        + "*) settings put secure enabled_accessibility_services \"$cur:" + cn + "\" ;; esac; "
                        + "settings put secure accessibility_enabled 1; echo done", 8000);
                sh.close();
            }
            final boolean ok = out != null && out.contains("done");
            ui.postDelayed(() -> toast(ok && KeysService.enabled(this) ? "CarDash buttons are on" : "Root couldn't turn it on: use Accessibility settings"), 900);
        });
    }

    /** The "⋯" shortcuts panel: Android's quick functions as big tiles. */
    void showKeysPanel() {
        final AlertDialog[] dlg = new AlertDialog[1];
        final int A_NOTIF = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        final int A_QS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
        Object[][] tiles = {
                {R.drawable.ic_bell, "Notifications", (Runnable) () -> { if (!KeysService.press(A_NOTIF) && !expandStatusBar(false)) showKeysSetup(); }},
                {R.drawable.ic_toggle, "Quick settings", (Runnable) () -> { if (!KeysService.press(A_QS) && !expandStatusBar(true)) showKeysSetup(); }},
                {R.drawable.ic_nav_recents, "Recent apps", (Runnable) this::showRecents},
                {R.drawable.ic_screenshot, "Screenshot", (Runnable) () -> {
                    if (Build.VERSION.SDK_INT < 28) { toast("Needs Android 9 or newer"); return; }
                    if (!KeysService.enabled(this)) { showKeysSetup(); return; }
                    ui.postDelayed(() -> KeysService.press(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 450);
                }},
                {R.drawable.ic_moon, "Screensaver", (Runnable) this::showSaver},
                {R.drawable.ic_volume, "Volume", (Runnable) () -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)},
                {R.drawable.ic_wifi, "Wi-Fi", (Runnable) () -> {
                    if (Build.VERSION.SDK_INT < 29 || !tryStart(new Intent(Settings.Panel.ACTION_WIFI))) startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }},
                {R.drawable.ic_bluetooth, "Bluetooth", (Runnable) () -> startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))},
                {R.drawable.ic_lock, "Screen off", (Runnable) () -> {
                    if (Build.VERSION.SDK_INT < 28) { toast("Needs Android 9 or newer"); return; }
                    pressKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }},
                {R.drawable.ic_power, "Power menu", (Runnable) () -> pressKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)},
                {R.drawable.ic_gear, "Android settings", (Runnable) () -> startSafe(new Intent(Settings.ACTION_SETTINGS))},
                {R.drawable.ic_tune, "CarDash settings", (Runnable) this::showSettings},
        };
        final int cols = 4;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(14), dp(12), dp(6));
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
            }
            final Runnable act = (Runnable) tiles[i][2];
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(12), dp(4), dp(10));
            t.setBackground(look.rippleRect(dp(14)));
            ImageView ic = new ImageView(this);
            ic.setImageResource((Integer) tiles[i][0]);
            ic.setImageTintList(look.csl(look.text));
            t.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView tv = new TextView(this);
            tv.setText((String) tiles[i][1]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(look.text2);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = dp(8);
            t.addView(tv, tl);
            t.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); act.run(); });
            Anim.press(t, 0.94f);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        boolean on = KeysService.enabled(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg()).setView(box).setNegativeButton("Close", null);
        if (!on) b.setNeutralButton("Turn on CarDash buttons…", (d, w) -> showKeysSetup());
        dlg[0] = b.show();
    }

    /** Fallback for the notification shade / quick settings without CarDash buttons (EXPAND_STATUS_BAR). */
    @SuppressWarnings({"JavaReflectionMemberAccess", "WrongConstant"})
    boolean expandStatusBar(boolean settings) {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod(settings ? "expandSettingsPanel" : "expandNotificationsPanel").invoke(sb);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A plain icon + name list for dialogs. */
    BaseAdapter appListAdapter(final java.util.List<AppEntry> list, final String current) {
        return new BaseAdapter() {
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
                tv.setTextColor(e.pkg != null && e.pkg.equals(current) ? look.accent : look.text);
                return v;
            }
        };
    }

    void openAppInfo(String pkg) {
        startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    boolean drawerOpen;

    void showDrawer() {
        if (!appsLoaded) loadApps(null);
        // the drawer stops above the dock: the dock (Back, Recent apps, close) stays on screen
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) drawer.getLayoutParams();
        int below = dock.getHeight() + mainView.getPaddingBottom();
        if (dl.bottomMargin != below) { dl.bottomMargin = below; drawer.setLayoutParams(dl); }
        drawerOpen = true;
        dockForDrawer(true);
        drawer.animate().cancel();
        drawer.setAlpha(0f);
        drawer.setScaleX(1.02f);
        drawer.setScaleY(1.02f);
        drawer.setVisibility(View.VISIBLE);
        drive.setActive(false); // hidden behind the drawer: don't animate it
        // settles in from slightly zoomed: one GPU layer, composited, nothing re-drawn per frame
        drawer.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(200)
                .setInterpolator(Anim.OUT).withLayer().start();
    }

    void hideDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        dockForDrawer(false);
        drawer.animate().cancel();
        drive.setActive(resumed && !saverOn);
        if (resumed) {
            drawer.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f).setStartDelay(0).setDuration(140)
                    .setInterpolator(Anim.OUT).withLayer()
                    .withEndAction(() -> { if (!drawerOpen) { drawer.setVisibility(View.GONE); grid.setSelection(0); } })
                    .start();
        } else {
            drawer.setVisibility(View.GONE);
            grid.setSelection(0);
        }
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

    int dockBtn;   // button size for the current dock width
    View keyBack, keyRecents;
    ImageButton allBtn;

    /** All apps open: Back + Recent apps fade in on the dock and the apps key becomes a close key. */
    void dockForDrawer(boolean open) {
        if (allBtn != null) allBtn.setImageResource(open ? R.drawable.ic_close : R.drawable.ic_apps);
        for (View k : new View[]{keyBack, keyRecents}) {
            if (k == null) continue;
            k.animate().cancel();
            if (open) {
                k.setVisibility(View.VISIBLE);
                k.setAlpha(0f);
                k.animate().alpha(1f).setStartDelay(60).setDuration(180).setInterpolator(Anim.OUT).withLayer().start();
            } else {
                k.animate().alpha(0f).setStartDelay(0).setDuration(120).withLayer()
                        .withEndAction(() -> { if (!drawerOpen) k.setVisibility(View.INVISIBLE); }).start();
            }
        }
    }

    void buildDock() {
        ensureDockDefaults();
        dock.removeAllViews();
        // 6 apps + all apps, Back + Recents on the left, screensaver + shortcuts on the right: shrink a
        // little if the dock is too narrow for all of them at full size
        int avail = (dock.getWidth() > 0 ? dock.getWidth() : getResources().getDisplayMetrics().widthPixels)
                - dock.getPaddingLeft() - dock.getPaddingRight() - dp(24);
        dockBtn = Math.max(dp(44), Math.min(dp(66), avail / (SLOTS + 5) - dp(14)));
        // Back + Recent apps: shown only while All apps is open (the dock stays visible under it);
        // on the dashboard they keep their space but are hidden, so nothing shifts when they appear
        keyBack = sysKey(R.drawable.ic_nav_back, "Back", v -> navBack(), v -> { showKeysPanel(); return true; });
        keyRecents = sysKey(R.drawable.ic_nav_recents, "Recent apps", v -> showRecents(), null);
        for (View k : new View[]{keyBack, keyRecents}) {
            k.setVisibility(drawerOpen ? View.VISIBLE : View.INVISIBLE);
            dock.addView(k);
        }
        dock.addView(dockGap());
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
                b.setPadding(dp(21), dp(21), dp(21), dp(21));
            }
            b.setOnClickListener(v -> {
                if (pkg == null || (appsLoaded && !byPkg.containsKey(pkg))) pickDock(slot);
                else { if (drawerOpen) hideDrawer(); launch(pkg); }
            });
            b.setOnLongClickListener(v -> { pickDock(slot); return true; });
            dock.addView(b);
        }
        ImageButton all = dockButton();
        all.setImageResource(drawerOpen ? R.drawable.ic_close : R.drawable.ic_apps);
        all.setColorFilter(look.text2);
        all.setPadding(dp(19), dp(19), dp(19), dp(19));
        all.setContentDescription("All apps");
        all.setOnClickListener(v -> { if (drawerOpen) hideDrawer(); else showDrawer(); });
        allBtn = all;
        dock.addView(all);
        dock.addView(dockGap());
        dock.addView(sysKey(R.drawable.ic_moon, "Screensaver now", v -> { if (drawerOpen) hideDrawer(); showSaver(); }, null));
        dock.addView(sysKey(R.drawable.ic_more, "Shortcuts: notifications, quick settings, screenshot…", v -> showKeysPanel(), null));
    }

    View dockGap() {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return g;
    }

    /** A flat dock key (Back, Recents, screensaver, shortcuts): icon only, the theme's secondary colour. */
    ImageButton sysKey(int icon, String label, View.OnClickListener click, View.OnLongClickListener longClick) {
        ImageButton b = dockButton();
        b.setBackground(look.rippleRect(dp(16)));
        b.setImageResource(icon);
        b.setImageTintList(look.csl(look.text2));
        int p = Math.round(dockBtn * 0.3f);
        b.setPadding(p, p, p, p);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        if (longClick != null) b.setOnLongClickListener(longClick);
        return b;
    }

    ImageButton dockButton() {
        ImageButton b = new ImageButton(this);
        b.setBackground(look.slot(dp(16)));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        int sz = dockBtn > 0 ? dockBtn : dp(66);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.leftMargin = lp.rightMargin = dp(7);
        b.setLayoutParams(lp);
        Anim.press(b, 0.88f);
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
        pickApp(title, current, allowClear, null, done);
    }

    /** @param top packages listed first (e.g. the music apps when choosing a player); the rest follow A-Z */
    void pickApp(String title, String current, boolean allowClear, Set<String> top, Consumer<String> done) {
        if (!appsLoaded) {
            toast("Loading apps…");
            loadApps(() -> pickApp(title, current, allowClear, top, done));
            return;
        }
        final List<AppEntry> list = new ArrayList<>();
        if (allowClear) {
            AppEntry none = new AppEntry();
            none.label = "— Empty slot —";
            list.add(none);
        }
        if (top != null && !top.isEmpty()) {
            for (AppEntry e : apps) if (top.contains(e.pkg)) list.add(e);
            for (AppEntry e : apps) if (!top.contains(e.pkg)) list.add(e);
        } else {
            list.addAll(apps);
        }
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
        final boolean locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        final int alert = sp.getInt("alert", 0);
        final String unit = mph() ? "mph" : "km/h";
        final int saverMin = saverMinutes();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Updates:  " + updateSummary());                                acts.add(this::showUpdates);
        String gu = greetUser();
        labels.add("Greeting:  " + (!greetOn() ? "off" : gu.isEmpty() ? "on  ·  add your name" : "on  ·  " + gu));
        acts.add(this::showGreetSettings);
        labels.add("Music card:  " + musicCardLabel());                          acts.add(this::showMusicCardMenu);
        labels.add("Music player:  " + playerLabel());                           acts.add(this::pickPlayer);
        labels.add("Theme:  " + THEME_NAMES[themeMode()]);                       acts.add(this::pickTheme);
        labels.add("Car colour:  " + CAR_COLOR_NAMES[carColorIndex()]);          acts.add(this::pickCarColor);
        labels.add("Car style:  " + (carStyle() == CarArt.SEDAN ? "Sedan" : "Hatchback")); acts.add(this::pickCarStyle);
        labels.add("Car view:  " + (carView() == DriveView.REAR ? "behind the car" : "top-down")); acts.add(this::pickCarView);
        final String pl = plateText();
        labels.add("Number plate:  " + (pl.isEmpty() ? "blank" : pl));            acts.add(this::pickPlate);
        labels.add("Rear badge:  " + badgeLabel());                               acts.add(this::showBadgeMenu);
        labels.add("Speed units:  " + unit);                                     acts.add(this::toggleUnits);
        labels.add("Speed alert:  " + (alert == 0 ? "off" : alert + " " + unit)); acts.add(this::pickAlert);
        labels.add("Screensaver:  " + (saverMin == 0 ? "off" : "after " + saverMin + " min idle")); acts.add(this::pickSaver);
        final boolean saverCar = sp.getBoolean("saverCar", true);
        labels.add("Parked car on screensaver:  " + (saverCar ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("saverCar", !saverCar).apply(); applyCar(); toast("Parked car on screensaver: " + (!saverCar ? "on" : "off")); });
        final boolean saverCat = sp.getBoolean("saverCat", true);
        labels.add("Sleepy cat on the parked car:  " + (saverCat ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("saverCat", !saverCat).apply(); applyCar(); toast("Sleepy cat: " + (!saverCat ? "on" : "off")); });
        labels.add("RAM optimiser…");                                            acts.add(this::showOptimizer);
        labels.add("Layout:  " + (dashLayout() ? "Dash cam" : "Standard"));       acts.add(this::pickLayout);
        labels.add("Dash cam…");                                                 acts.add(this::showCamSettings);
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
        labels.add("System bars:  " + new String[]{"shown", "status bar hidden", "all hidden"}[barsMode()]); acts.add(this::pickBars);
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
        labels.add("About CarDash  ·  v" + updater.installedName());              acts.add(this::showAbout);
        new AlertDialog.Builder(this, dlg())
                .setTitle("CarDash")
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Close", null)
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
                    applyCar();
                    d.dismiss();
                })
                .show();
    }

    int carStyle() { return sp.getInt("carStyle", CarArt.HATCH) == CarArt.SEDAN ? CarArt.SEDAN : CarArt.HATCH; }

    void pickCarStyle() {
        new AlertDialog.Builder(this, dlg())
                .setTitle("Car style")
                .setSingleChoiceItems(CAR_STYLE_NAMES, carStyle(), (d, w) -> {
                    sp.edit().putInt("carStyle", w).apply();
                    applyCar();
                    d.dismiss();
                })
                .show();
    }

    /** Push style + colour + theme to the dashboard car (cross-fades) and the screensaver car. */
    int carView() { return sp.getInt("carView", DriveView.TOP) == DriveView.REAR ? DriveView.REAR : DriveView.TOP; }

    String plateText() { return sp.getString("plate", "CARDASH"); }

    void pickCarView() {
        String[] names = {"Top-down (bird's-eye)", "Behind the car (chase cam, number plate)"};
        new AlertDialog.Builder(this, dlg())
                .setTitle("Car view")
                .setSingleChoiceItems(names, carView(), (d, w) -> {
                    sp.edit().putInt("carView", w).apply();
                    applyCar();
                    d.dismiss();
                })
                .show();
    }

    /** Number plate text for the chase-cam view: letters, digits, space, dash, dot; up to 10. */
    void pickPlate() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setText(plateText());
        et.setSelectAllOnFocus(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        et.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        et.setLetterSpacing(0.08f);
        et.setTextColor(look.text);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps(), new android.text.InputFilter.LengthFilter(10),
                (src, st, en, dst, ds, de) -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = st; i < en; i++) {
                        char ch = src.charAt(i);
                        if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '.') b.append(ch);
                    }
                    return b.length() == en - st ? null : b;
                }});
        FrameLayout box = new FrameLayout(this);
        int p = dp(22);
        box.setPadding(p, dp(8), p, 0);
        box.addView(et);
        AlertDialog dl = new AlertDialog.Builder(this, dlg())
                .setTitle("Number plate (chase-cam view)")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    sp.edit().putString("plate", et.getText().toString().trim().replaceAll("\\s+", " ")).apply();
                    applyCar();
                })
                .setNeutralButton("Clear", (d, w) -> { sp.edit().putString("plate", "").apply(); applyCar(); })
                .setNegativeButton("Cancel", null)
                .create();
        if (dl.getWindow() != null) dl.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dl.show();
        et.requestFocus();
    }

    void applyCar() {
        applyBadge();
        drive.setLook(carView(), carStyle(), carColor(), look.light, plateText(), look.card);
        saver.setCar(sp.getBoolean("saverCar", true) ? carStyle() : -1, sp.getInt("carColor", 0));
        saver.setCat(sp.getBoolean("saverCat", true));
    }

    // ------------------------------------------------------------------ boot-lid badge (owner's own)

    static final int REQ_BADGE = 43;
    Bitmap badgeImg;
    int badgeImgVer = -1;

    int badgeMode() { return sp.getInt("badgeMode", CarArt.Badge.NONE); }

    String badgeLabel() {
        switch (badgeMode()) {
            case CarArt.Badge.TEXT: return sp.getString("badgeText", "");
            case CarArt.Badge.IMAGE: return "your image" + (sp.getBoolean("badgeChrome", true) ? ", chrome" : "");
            default: return "none";
        }
    }

    /** Hands the car view the current badge; the image is read from disk only when it changed. */
    void applyBadge() {
        final int mode = badgeMode(), imgVer = sp.getInt("badgeImgVer", 0);
        final String text = sp.getString("badgeText", "");
        final boolean chrome = sp.getBoolean("badgeChrome", true);
        final int ver = java.util.Arrays.hashCode(new Object[]{mode, text, chrome, imgVer});
        if (mode != CarArt.Badge.IMAGE) { drive.setBadge(new CarArt.Badge(mode, text, null, chrome, ver)); return; }
        if (badgeImgVer == imgVer && badgeImg != null) { drive.setBadge(new CarArt.Badge(mode, text, badgeImg, chrome, ver)); return; }
        // a small PNG (max 256 px): read it right here so the car is rendered once, with the badge on
        badgeImg = Badges.load(this);
        badgeImgVer = imgVer;
        drive.setBadge(new CarArt.Badge(badgeImg == null ? CarArt.Badge.NONE : mode, text, badgeImg, chrome, ver));
    }

    void showBadgeMenu() {
        final int mode = badgeMode();
        final boolean chrome = sp.getBoolean("badgeChrome", true);
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<Runnable> acts = new ArrayList<>();
        labels.add("None" + (mode == CarArt.Badge.NONE ? "   \u2713" : ""));
        acts.add(() -> setBadgeMode(CarArt.Badge.NONE));
        labels.add("Chrome lettering…" + (mode == CarArt.Badge.TEXT ? "   \u2713  " + sp.getString("badgeText", "") : "")
                + "   (e.g. your model name)");
        acts.add(() -> editText("Badge lettering (up to 12 characters)", sp.getString("badgeText", ""), v -> {
            String t = v.trim();
            if (t.length() > 12) t = t.substring(0, 12);
            sp.edit().putString("badgeText", t).apply();
            setBadgeMode(t.isEmpty() ? CarArt.Badge.NONE : CarArt.Badge.TEXT);
        }));
        labels.add("Your own badge image…" + (mode == CarArt.Badge.IMAGE ? "   \u2713" : "") + "   (PNG or JPG from this head unit)");
        acts.add(this::pickBadgeImage);
        if (mode == CarArt.Badge.IMAGE) {
            labels.add("Image finish:  " + (chrome ? "chrome" : "original colours"));
            acts.add(() -> { sp.edit().putBoolean("badgeChrome", !chrome).apply(); applyBadge(); });
        }
        new AlertDialog.Builder(this, dlg())
                .setTitle("Badge on the back of the car  ·  shows in the \"behind the car\" view")
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Close", null)
                .show();
    }

    void setBadgeMode(int m) {
        sp.edit().putInt("badgeMode", m).apply();
        applyBadge();
        if (m != CarArt.Badge.NONE && carView() != DriveView.REAR) toast("Set Car view to \"behind the car\" to see the badge");
    }

    /** Any image the owner has on the unit: a file manager or gallery hands it over, CarDash cuts it out. */
    void pickBadgeImage() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        Intent get = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        for (Intent i : new Intent[]{open, get}) {
            try { startActivityForResult(i, REQ_BADGE); return; } catch (Exception ignored) { }
        }
        new AlertDialog.Builder(this, dlg()).setTitle("No file picker")
                .setMessage("This head unit has no app for choosing a file. Install a file manager (or a gallery app), "
                        + "copy your badge image (PNG with a transparent background is best) to the unit, and try again.")
                .setPositiveButton("OK", null).show();
    }

    void onBadgePicked(final Uri uri) {
        toast("Preparing your badge…");
        bg.execute(() -> {
            final String err = Badges.importImage(this, uri);
            ui.post(() -> {
                if (err != null) { toast(err); return; }
                sp.edit().putInt("badgeImgVer", sp.getInt("badgeImgVer", 0) + 1).apply();
                setBadgeMode(CarArt.Badge.IMAGE);
                toast("Badge added");
            });
        });
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
        if (want == look && !force) return;
        if (!force && look != null && resumed && !saverOn) crossfadeTheme(want);
        else applyTheme(want);
    }

    boolean themeFading;

    /**
     * 6 pm / 5 am (or a theme change in settings): instead of an instant colour swap, the old screen
     * fades away over the new one. One GPU read-back of the window (PixelCopy), a full-screen image on
     * top for 0.6 s, then it's freed. Falls back to the instant swap if anything is unavailable.
     */
    void crossfadeTheme(final Look want) {
        final ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        final int w = decor.getWidth(), h = decor.getHeight();
        if (themeFading || w == 0 || h == 0) { applyTheme(want); return; }
        final Bitmap shot;
        try { shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); }
        catch (Throwable t) { applyTheme(want); return; }
        themeFading = true;
        try {
            android.view.PixelCopy.request(getWindow(), shot, result -> {
                if (result != android.view.PixelCopy.SUCCESS || isDestroyed()) {
                    themeFading = false;
                    shot.recycle();
                    applyTheme(want);
                    return;
                }
                final ImageView cover = new ImageView(this);
                cover.setImageBitmap(shot);
                cover.setScaleType(ImageView.ScaleType.FIT_XY);
                decor.addView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                applyTheme(want);
                cover.animate().alpha(0f).setStartDelay(80).setDuration(620).setInterpolator(Anim.OUT).withEndAction(() -> {
                    decor.removeView(cover);
                    cover.setImageDrawable(null);
                    shot.recycle();
                    themeFading = false;
                }).start();
            }, ui);
        } catch (Throwable t) {
            themeFading = false;
            shot.recycle();
            applyTheme(want);
        }
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
        widgetCard.setBackground(L.card(dp(20), one));
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
        greetName.setTextColor(L.text);
        greetLabel.setTextColor(greetUser().isEmpty() ? L.accent : L.text2);
        posTv.setTextColor(L.text3);
        durTv.setTextColor(L.text3);
        appTv.setTextColor(L.accent);

        ColorStateList t1 = L.csl(L.text), t2 = L.csl(L.text2), ln = L.csl(L.line);
        wifiIv.setImageTintList(t2);
        if (camBtn != null) { ((ImageButton) camBtn).setImageTintList(t2); camBtn.setBackground(L.rippleRect(dp(8))); }
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
        prog.setProgressBackgroundTintList(L.csl(L.track));
        volBar.setProgressTintList(t1);
        volBar.setProgressBackgroundTintList(L.csl(L.track));
        volBar.setThumbTintList(t1);

        applyCar();
        if (artBmp == null) setArt(null);
        renderSpeed(lastSpeed);
        applyMediaTint();
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

    // ------------------------------------------------------------------ dash cam
    //
    // One DashCam session is shared by the home-screen tile (Dash cam layout) and the full-screen
    // view: opening/closing full screen just moves the decoder's output surface, no reconnect.
    // Rear = the head unit's own camera input (RearCam), or an app you pick.

    View camTile;
    AspectFrame tileFrame, rearAspect;
    android.view.SurfaceView tileSurface, rearSurface;
    TextView tileStatus;
    boolean fullReady, tileReady, camHalted, closingToTile, camPlayingNow;
    RearCam rear;
    String rearPendingId;
    int rearW, rearH;

    boolean dashLayout() { return sp.getInt("layout", 0) == 1; }

    static final long WAKE_GRACE = 60_000; // after boot / ignition, give wireless Android Auto the Wi-Fi first
    long lastWake;
    boolean camLinkOk;

    /** Why the camera should keep off the Wi-Fi right now (wireless phone link), or null. */
    String linkBusy() {
        if (!sp.getBoolean("camAaGuard", true)) return null;
        String h = PhoneLink.hosted();
        if (h != null) return "wireless link on " + h;
        String p = PhoneLink.playing(msm, listenerCn, booster == null ? null : booster.labels);
        return p == null ? null : labelOf(p) + " is playing";
    }

    /** While the home screen shows the Dash cam layout, re-check every 5 s (cheap: no I/O). */
    final Runnable linkCheck = new LinkCheck();
    class LinkCheck implements Runnable {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!resumed || !dashLayout()) return;
            updateCamSession();
            ui.postDelayed(this, 5000);
        }
    }

    final BroadcastReceiver wakeRx = new WakeRx();
    class WakeRx extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { lastWake = SystemClock.elapsedRealtime(); } // ignition on (sleep-mode units)
    }

    int rearMode() { return sp.getInt("rearMode", 1); } // 0 off, 1 head unit camera, 2 an app

    void setupCam() {
        camView = findViewById(R.id.cam);
        camBar = findViewById(R.id.camBar);
        camFrame = findViewById(R.id.camFrame);
        camSurface = findViewById(R.id.camSurface);
        camStatus = findViewById(R.id.camStatus);
        camInfo = findViewById(R.id.camInfo);
        camFront = findViewById(R.id.camFront);
        camRear = findViewById(R.id.camRear);
        camLive = findViewById(R.id.camLive);
        camTile = findViewById(R.id.camTile);
        tileFrame = findViewById(R.id.tileFrame);
        tileSurface = findViewById(R.id.tileSurface);
        tileStatus = findViewById(R.id.tileStatus);
        rearAspect = findViewById(R.id.rearFrame);
        rearSurface = findViewById(R.id.rearSurface);

        android.graphics.drawable.GradientDrawable screen = new android.graphics.drawable.GradientDrawable();
        screen.setColor(0xFF000000);
        screen.setCornerRadius(dp(20));
        screen.setStroke(Math.max(1, dp(1)), 0xFF23262C);
        camTile.setBackground(screen);

        camBtn.setOnClickListener(v -> openCam());
        drive.setOnClickListener(v -> { if (sp.getBoolean("camTapCar", true)) openCam(); });
        camTile.setOnClickListener(v -> openCam());
        reg(wakeRx, new IntentFilter(Intent.ACTION_SCREEN_ON));
        camTile.setOnLongClickListener(v -> { showCamSettings(); return true; });
        findViewById(R.id.camClose).setOnClickListener(v -> closeCam());
        camFront.setOnClickListener(v -> switchCam(false));
        camRear.setOnClickListener(v -> switchCam(true));
        camView.setOnClickListener(v -> toggleCamBar());
        camStatus.setOnClickListener(v -> { if (camHasFailed) showCamLog(); else toggleCamBar(); });
        camSurface.getHolder().addCallback(new SurfaceCb(0));
        tileSurface.getHolder().addCallback(new SurfaceCb(1));
        rearSurface.getHolder().addCallback(new SurfaceCb(2));
        applyLayout();
    }

    class SurfaceCb implements android.view.SurfaceHolder.Callback {
        final int which; // 0 full screen, 1 home tile, 2 rear
        SurfaceCb(int w) { which = w; }

        @Override public void surfaceCreated(android.view.SurfaceHolder h) {
            if (which == 0) {
                fullReady = true;
                updateCamSession();                                         // video moves here
                if (camOpen && !camRearOn) mainView.setVisibility(View.INVISIBLE); // now the tile can go
            } else if (which == 1) {
                tileReady = true;
                updateCamSession();
                if (closingToTile) finishClose();
            } else {
                startRear();
            }
        }

        @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) {
            if (which == 2) rearSurfaceChanged(w, hh);
        }

        @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
            if (which == 2) { stopRear(); return; }
            if (which == 0) fullReady = false; else tileReady = false;
            updateCamSession(); // synchronously move the decoder off this surface before it dies
        }
    }

    /** Start / stop / retarget the shared camera session to match what is on screen. */
    void updateCamSession() {
        if (camView == null) return;
        boolean want = resumed && !saverOn && !camHalted
                && (camOpen ? (!camRearOn || dashLayout()) : dashLayout());
        if (want && !camOpen) { // home tile: never fight a wireless Android Auto / CarPlay session
            long sinceWake = SystemClock.elapsedRealtime() - Math.max(0, lastWake);
            if (sinceWake < WAKE_GRACE) {
                want = false;
                setTileStatus("Letting your phone connect first…");
                ui.removeCallbacks(linkCheck);
                ui.postDelayed(linkCheck, WAKE_GRACE - sinceWake + 500);
            } else {
                String l = linkBusy();
                if (l != null) { want = false; setTileStatus("Paused while Android Auto / CarPlay is connected · tap to view"); }
            }
        }
        if (!want) { stopCam(); return; }
        android.view.Surface full = fullReady ? camSurface.getHolder().getSurface() : null;
        android.view.Surface tile = tileReady && camTile.getVisibility() == View.VISIBLE ? tileSurface.getHolder().getSurface() : null;
        android.view.Surface target = camOpen ? (camRearOn ? null : (full != null ? full : tile)) : (tile != null ? tile : full);
        if (cam == null) startCam(target); else cam.setSurface(target);
    }

    void startCam(android.view.Surface target) {
        camPlayingNow = false;
        cam = new DashCam(this, sp, ui, new DashCam.Ui() {
            @Override public void camStatus(String s) {
                camPlayingNow = false;
                setTileStatus(s);
                if (camOpen && !camRearOn) { camHasFailed = false; camLive.setVisibility(View.GONE); setCamStatus(s); }
            }
            @Override public void camPlaying() {
                camPlayingNow = true;
                tileStatus.setVisibility(View.GONE);
                if (camOpen && !camRearOn) {
                    camStatus.setVisibility(View.GONE);
                    camLive.setVisibility(View.VISIBLE);
                    ui.removeCallbacks(hideCamBar);
                    ui.postDelayed(hideCamBar, 4000);
                }
            }
            @Override public void camFailed(String s) {
                camPlayingNow = false;
                camHalted = true;              // stop trying until retried (tile: automatically in a minute)
                stopCam();                     // and give the Wi-Fi back meanwhile
                setTileStatus("Dash cam offline\nRetrying in a minute · tap to open");
                ui.removeCallbacks(camRetry);
                ui.postDelayed(camRetry, 60_000);
                if (camOpen && !camRearOn) {
                    camHasFailed = true;
                    camLive.setVisibility(View.GONE);
                    camBar.animate().cancel();
                    camBar.setAlpha(1f);
                    camBar.setVisibility(View.VISIBLE);
                    setCamStatus(s + "\nTap FRONT to retry.");
                }
            }
            @Override public void camSize(int w, int h) {
                camFrame.setAspect(w / (float) h);
                tileFrame.setAspect(w / (float) h);
            }
        });
        cam.start(target, false);
    }

    final Runnable camRetry = new CamRetry();
    class CamRetry implements Runnable {
        @Override public void run() { camHalted = false; updateCamSession(); }
    }

    void stopCam() {
        if (cam != null) { cam.stop(); cam = null; }
        camPlayingNow = false;
    }

    void setTileStatus(String s) {
        tileStatus.setText(s.split("\n")[0]);
        tileStatus.setVisibility(View.VISIBLE);
    }

    void openCam() {
        if (camOpen) return;
        if (!sp.getBoolean("camIntro", false)) { showCamIntro(this::openCam); return; }
        if (!camLinkOk && cam == null) {
            final String l = linkBusy();
            if (l != null) {
                new AlertDialog.Builder(this, dlg())
                        .setTitle("Android Auto / CarPlay is connected")
                        .setMessage("Your phone looks connected wirelessly (" + l + "). The DDPAI needs this head unit's "
                                + "Wi-Fi too, and on many units sharing it makes Android Auto stutter or drop for as long "
                                + "as the camera is on screen. It recovers when you close the camera.")
                        .setPositiveButton("View anyway", (d, w) -> { camLinkOk = true; openCam(); })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
        }
        camOpen = true;
        camRearOn = false;
        camHalted = false;
        ui.removeCallbacks(camRetry);
        hideDrawer();
        ui.removeCallbacks(idleR);
        drive.setActive(false);
        camHasFailed = false;
        camLive.setVisibility(camPlayingNow ? View.VISIBLE : View.GONE);
        if (camPlayingNow) camStatus.setVisibility(View.GONE); else setCamStatus("Connecting…");
        camRear.setVisibility(rearMode() != 0 ? View.VISIBLE : View.GONE);
        camFrame.setVisibility(View.VISIBLE);
        rearAspect.setVisibility(View.GONE);
        paintCamChips();
        camBar.animate().cancel();
        camBar.setAlpha(1f);
        camBar.setVisibility(View.VISIBLE);
        if (camPlayingNow) { ui.removeCallbacks(hideCamBar); ui.postDelayed(hideCamBar, 4000); }
        camView.setVisibility(View.VISIBLE);
        // with a live tile, keep the dashboard until the video has moved to the full-screen surface
        if (!(dashLayout() && tileReady && cam != null)) mainView.setVisibility(View.INVISIBLE);
        updateCamInfo();
        updateCamSession();
    }

    void closeCam() {
        if (!camOpen) return;
        camOpen = false;
        camLinkOk = false; // the override was for this viewing only
        camRearOn = false;
        ui.removeCallbacks(hideCamBar);
        stopRear();
        mainView.setVisibility(View.VISIBLE);
        drive.setActive(resumed && !saverOn);
        if (resumed && dashLayout() && cam != null && !tileReady) {
            closingToTile = true;           // keep showing video full screen until the tile surface exists
            ui.postDelayed(finishCloseR, 800);
        } else {
            finishClose();
        }
        resetIdle();
    }

    final Runnable finishCloseR = new FinishClose();
    class FinishClose implements Runnable { @Override public void run() { finishClose(); } }

    void finishClose() {
        ui.removeCallbacks(finishCloseR);
        closingToTile = false;
        if (camOpen) return;
        camView.setVisibility(View.GONE);
        updateCamSession();
    }

    void switchCam(boolean toRear) {
        if (toRear && rearMode() == 2) {
            String app = sp.getString("rearApp", null);
            if (app != null) { closeCam(); launch(app); }
            else toast("Choose the rear camera app in Settings → Dash cam");
            return;
        }
        if (toRear == camRearOn) {
            if (!toRear && camHasFailed) { // retry the dash cam
                camHasFailed = false;
                camHalted = false;
                ui.removeCallbacks(camRetry);
                setCamStatus("Connecting…");
                stopCam();
                updateCamSession();
            }
            return;
        }
        camRearOn = toRear;
        paintCamChips();
        camLive.setVisibility(View.GONE);
        camHasFailed = false;
        if (toRear) {
            setCamStatus("Opening the rear camera…");
            camFrame.setVisibility(View.GONE);      // dash cam keeps streaming (Dash cam layout) or stops
            rearAspect.setVisibility(View.VISIBLE);  // surfaceCreated -> startRear()
        } else {
            stopRear();
            rearAspect.setVisibility(View.GONE);
            camFrame.setVisibility(View.VISIBLE);
            if (camPlayingNow) camStatus.setVisibility(View.GONE); else setCamStatus("Connecting…");
            updateCamSession();
        }
    }

    // ---- rear: head unit camera

    String rearCamId() {
        List<String[]> l = RearCam.list(this);
        if (l.isEmpty()) return null;
        String id = sp.getString("rearCamId", null);
        if (id != null) for (String[] c : l) if (c[0].equals(id)) return id;
        return l.get(0)[0];
    }

    void startRear() {
        if (!camOpen || !camRearOn || rear != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setCamStatus("Allow camera access to see the rear camera.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
            return;
        }
        final String id = rearCamId();
        if (id == null) {
            setCamStatus("This head unit doesn't share its camera with apps.\nPick its camera app instead: Settings → Dash cam → Rear camera.");
            return;
        }
        rear = new RearCam(this, ui, new RearCam.Cb() {
            @Override public void rearStatus(String s) { if (camOpen && camRearOn) setCamStatus(s); }
            @Override public void rearPlaying() {
                if (!camOpen || !camRearOn) return;
                camStatus.setVisibility(View.GONE);
                camLive.setVisibility(View.VISIBLE);
                ui.removeCallbacks(hideCamBar);
                ui.postDelayed(hideCamBar, 4000);
            }
            @Override public void rearSize(int w, int h) { rearAspect.setAspect(w / (float) h); }
        });
        android.util.Size sz = rear.sizeFor(id);
        android.graphics.Rect cur = rearSurface.getHolder().getSurfaceFrame();
        if (sz == null || (cur.width() == sz.getWidth() && cur.height() == sz.getHeight())) {
            if (sz != null) rearAspect.setAspect(sz.getWidth() / (float) sz.getHeight());
            rear.start(id, rearSurface.getHolder().getSurface());
            return;
        }
        // camera buffers must match the surface size: resize it, then start in surfaceChanged()
        rearAspect.setAspect(sz.getWidth() / (float) sz.getHeight());
        rearW = sz.getWidth();
        rearH = sz.getHeight();
        rearPendingId = id;
        rearSurface.getHolder().setFixedSize(rearW, rearH);
        ui.postDelayed(rearStartFallback, 800);
    }

    void rearSurfaceChanged(int w, int h) {
        if (rear != null && rearPendingId != null && w == rearW && h == rearH) startPendingRear();
    }

    final Runnable rearStartFallback = new RearFallback();
    class RearFallback implements Runnable { @Override public void run() { startPendingRear(); } }

    void startPendingRear() {
        ui.removeCallbacks(rearStartFallback);
        String id = rearPendingId;
        rearPendingId = null;
        if (rear != null && id != null && camOpen && camRearOn) rear.start(id, rearSurface.getHolder().getSurface());
    }

    void stopRear() {
        ui.removeCallbacks(rearStartFallback);
        rearPendingId = null;
        if (rear != null) { rear.stop(); rear = null; }
    }

    // ---- view chrome

    void paintCamChips() {
        chip(camFront, !camRearOn);
        chip(camRear, camRearOn);
    }

    void chip(TextView t, boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(on ? 0xFFF2F3F5 : 0x33FFFFFF);
        t.setBackground(g);
        t.setTextColor(on ? 0xFF07080A : 0xFFF2F3F5);
    }

    void setCamStatus(String s) {
        camStatus.setText(s);
        camStatus.setVisibility(View.VISIBLE);
    }

    final Runnable hideCamBar = new HideCamBar();
    class HideCamBar implements Runnable {
        @Override public void run() { if (camOpen && !camHasFailed) camBar.animate().alpha(0f).setDuration(250).withEndAction(() -> camBar.setVisibility(View.INVISIBLE)).start(); }
    }

    void toggleCamBar() {
        ui.removeCallbacks(hideCamBar);
        if (camBar.getVisibility() != View.VISIBLE) {
            camBar.animate().cancel();
            camBar.setAlpha(1f);
            camBar.setVisibility(View.VISIBLE);
            ui.postDelayed(hideCamBar, 4000);
        } else {
            hideCamBar.run();
        }
    }

    void updateCamInfo() {
        if (!camOpen || camInfo == null) return;
        String spd = lastSpeed < 0 ? "" : Math.round(lastSpeed * (mph() ? 2.23694f : 3.6f)) + (mph() ? " MPH" : " KM/H") + "   ·   ";
        setT(camInfo, spd + clockTv.getText());
    }

    // ---- layout: Standard (default) / Dash cam

    void applyLayout() {
        boolean dash = dashLayout();
        camTile.setVisibility(dash ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) mediaCard.getLayoutParams();
        mlp.weight = dash ? 36 : 1;
        mediaCard.setLayoutParams(mlp);
        LinearLayout.LayoutParams wlp = (LinearLayout.LayoutParams) widgetCard.getLayoutParams();
        wlp.weight = mlp.weight;
        widgetCard.setLayoutParams(wlp);
        // compact player under the camera tile
        int pad = dp(dash ? 12 : 16);
        mediaCard.setPadding(pad, pad, pad, pad);
        appTv.setVisibility(dash ? View.GONE : View.VISIBLE);
        ((View) posTv.getParent()).setVisibility(dash ? View.GONE : View.VISIBLE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, dash ? 19 : 26);
        artistTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, dash ? 14 : 17);
        ((LinearLayout.LayoutParams) prog.getLayoutParams()).topMargin = dp(dash ? 8 : 16);
        ctlW = -1;
        sizeMediaButtons();
        ((LinearLayout.LayoutParams) ((View) playBtn.getParent()).getLayoutParams()).topMargin = dp(dash ? 2 : 10);
        mediaCard.requestLayout();
        applyOrientation(getResources().getConfiguration());
        updateCamSession();
        if (dash && resumed) { ui.removeCallbacks(linkCheck); ui.postDelayed(linkCheck, 5000); }
    }

    int ctlW = -1;

    /**
     * Big, well-spaced media buttons (pressed while driving): previous / next 92 dp, play 96 dp
     * (Dash cam layout 78 / 82 dp), shrunk only if the row is too narrow to hold them.
     * Re-run when the controls row changes width.
     */
    void sizeMediaButtons() {
        View row = (View) playBtn.getParent();
        int avail = row.getWidth();
        int key = avail * 4096 + mediaCard.getHeight();
        if (avail > 0 && key == ctlW) return;
        ctlW = key;
        boolean dash = dashLayout();
        float side = dash ? 78 : 92, play = dash ? 82 : 96, gap = dash ? 22 : 26;
        float need = 2 * side + play + 2 * gap;
        float k = avail > 0 ? Math.min(1f, avail / (float) dp(need)) : 1f;
        // and short enough to fit under the title / artist / progress in this card
        int cardH = mediaCard.getHeight() - mediaCard.getPaddingTop() - mediaCard.getPaddingBottom();
        if (cardH > 0) k = Math.min(k, Math.max(0.55f, (cardH - dp(dash ? 64 : 150)) / (float) dp(play)));
        sizeBtn(prevBtn, Math.round(side * k), 0, Math.round(side * k * 0.2f));
        sizeBtn(nextBtn, Math.round(side * k), 0, Math.round(side * k * 0.2f));
        sizeBtn(playBtn, Math.round(play * k), Math.round(gap * k), Math.round(play * k * 0.27f));
    }

    void sizeBtn(View b, int sizeDp, int sideMarginDp, int padDp) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = lp.height = dp(sizeDp);
        if (sideMarginDp > 0) { lp.leftMargin = lp.rightMargin = dp(sideMarginDp); lp.setMarginStart(dp(sideMarginDp)); lp.setMarginEnd(dp(sideMarginDp)); }
        b.setLayoutParams(lp);
        int p = dp(padDp);
        b.setPadding(p, p, p, p);
    }

    void setLayoutMode(int l) {
        if (l == 1 && !sp.getBoolean("camIntro", false)) { showCamIntro(() -> setLayoutMode(1)); return; }
        sp.edit().putInt("layout", l).apply();
        camHalted = false;
        applyLayout();
    }

    void pickLayout() {
        String[] names = {"Standard  (car · music)", "Dash cam  (car · live front camera · music)"};
        new AlertDialog.Builder(this, dlg())
                .setTitle("Home screen layout")
                .setSingleChoiceItems(names, dashLayout() ? 1 : 0, (d, w) -> { d.dismiss(); setLayoutMode(w); })
                .show();
    }

    void showCamIntro(final Runnable then) {
        boolean both = DashCam.staConcurrency(getSystemService(android.net.wifi.WifiManager.class));
        new AlertDialog.Builder(this, dlg())
                .setTitle("Dash cam live view")
                .setMessage("CarDash joins the DDPAI's own Wi-Fi while its picture is on screen (the full view, or the "
                        + "home screen in Dash cam layout), the same way the DDPAI phone app does. "
                        + (both ? "This head unit can stay on your normal Wi-Fi at the same time."
                                : "On this head unit, other Wi-Fi (like a phone hotspot) pauses meanwhile, and wireless CarPlay may drop.")
                        + " It's released whenever you leave the home screen.\n\nThe first time, Android asks you to allow the "
                        + "connection. Make sure your phone isn't connected to the camera at the same time.")
                .setPositiveButton("Continue", (d, w) -> { sp.edit().putBoolean("camIntro", true).apply(); then.run(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- settings

    void showCamLog() {
        final String log = sp.getString("camLog", "No connection attempts yet.");
        new AlertDialog.Builder(this, dlg())
                .setTitle("Dash cam connection log")
                .setMessage(log)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cbm = getSystemService(android.content.ClipboardManager.class);
                    if (cbm != null) cbm.setPrimaryClip(android.content.ClipData.newPlainText("CarDash dash cam log", log));
                    toast("Log copied");
                })
                .show();
    }

    String rearLabel() {
        int m = rearMode();
        if (m == 0) return "off";
        if (m == 2) { String a = sp.getString("rearApp", null); return a == null ? "an app (not chosen)" : "open " + labelOf(a); }
        String id = rearCamId();
        return id == null ? "head unit camera (none found)" : "head unit camera " + id;
    }

    void pickRearSource() {
        final List<String[]> cams = RearCam.list(this);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        String cur = rearMode() == 1 ? rearCamId() : null;
        for (final String[] c : cams) {
            labels.add("Head unit " + c[1] + (c[0].equals(cur) ? "   ✓" : ""));
            acts.add(() -> { sp.edit().putInt("rearMode", 1).putString("rearCamId", c[0]).apply(); showCamSettings(); });
        }
        if (cams.isEmpty()) { labels.add("(this head unit doesn't share a camera with apps)"); acts.add(this::pickRearSource); }
        labels.add("Open the head unit's camera app instead…" + (rearMode() == 2 ? "   ✓" : ""));
        acts.add(() -> pickApp("Rear camera app", sp.getString("rearApp", null), false, p -> {
            if (p != null) sp.edit().putInt("rearMode", 2).putString("rearApp", p).apply();
            showCamSettings();
        }));
        labels.add("Off" + (rearMode() == 0 ? "   ✓" : ""));
        acts.add(() -> { sp.edit().putInt("rearMode", 0).apply(); showCamSettings(); });
        new AlertDialog.Builder(this, dlg())
                .setTitle("Rear camera (wired to the head unit)")
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Close", null)
                .show();
    }

    void showCamSettings() {
        final String ssid = sp.getString("camSsid", ""), seen = sp.getString("camSsidSeen", ""), model = sp.getString("camModel", "");
        final boolean smooth = sp.getBoolean("camSmooth", true), tapCar = sp.getBoolean("camTapCar", true);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Open live view");
        acts.add(this::openCam);
        labels.add("Home screen layout:  " + (dashLayout() ? "Dash cam" : "Standard"));
        acts.add(this::pickLayout);
        labels.add("Rear camera:  " + rearLabel());
        acts.add(this::pickRearSource);
        labels.add("DDPAI Wi-Fi name:  " + (!ssid.isEmpty() ? ssid : !seen.isEmpty() ? "auto (" + seen + ")" : "auto (any name containing DDPAI)"));
        acts.add(() -> editText("DDPAI Wi-Fi name (leave empty for automatic)", ssid, v -> { sp.edit().putString("camSsid", v.trim()).apply(); showCamSettings(); }));
        labels.add("DDPAI Wi-Fi password:  " + sp.getString("camPass", "1234567890"));
        acts.add(() -> editText("DDPAI Wi-Fi password (default 1234567890)", sp.getString("camPass", "1234567890"),
                v -> { sp.edit().putString("camPass", v.trim()).apply(); showCamSettings(); }));
        labels.add("Quality:  " + (smooth ? "Smooth (camera's preview stream)" : "Full resolution"));
        acts.add(() -> { sp.edit().putBoolean("camSmooth", !smooth).apply(); showCamSettings(); });
        final boolean guard = sp.getBoolean("camAaGuard", true);
        labels.add("Wireless Android Auto / CarPlay:  " + (guard ? "protect it (camera pauses while connected)" : "camera connects anyway"));
        acts.add(() -> { sp.edit().putBoolean("camAaGuard", !guard).apply(); showCamSettings(); });
        final String hostedNow = PhoneLink.hosted();
        labels.add("Phone link right now:  " + (hostedNow != null ? "detected (" + hostedNow + ")" : "not detected"));
        acts.add(() -> new AlertDialog.Builder(this, dlg())
                .setTitle("Phone link detection")
                .setMessage("Wireless Android Auto and CarPlay run over a Wi-Fi network this head unit hosts for your phone. "
                        + "CarDash looks for that network (and for a phone-link app playing audio) and keeps the dash cam off "
                        + "the Wi-Fi while it's there.\n\nRight now: " + (hostedNow != null ? hostedNow : "nothing hosted")
                        + "\nWi-Fi chip: " + PhoneLink.concurrency(getSystemService(android.net.wifi.WifiManager.class))
                        + "\n\nIf this says \"detected\" even with your phone disconnected, your phone-link app keeps its "
                        + "network up all the time. Then set the option above to \"connect anyway\" and test whether Android Auto stays smooth.")
                .setPositiveButton("OK", (d, w) -> showCamSettings())
                .show());
        labels.add("Tap the car to open:  " + (tapCar ? "on" : "off"));
        acts.add(() -> { sp.edit().putBoolean("camTapCar", !tapCar).apply(); showCamSettings(); });
        labels.add("Last connection log…" + (model.isEmpty() ? "" : "   (last camera: " + model + ")"));
        acts.add(this::showCamLog);
        new AlertDialog.Builder(this, dlg())
                .setTitle("Dash cam  ·  DDPAI front + head unit rear")
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Close", null)
                .show();
    }

    void editText(String title, String value, java.util.function.Consumer<String> done) {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setSelection(e.getText().length());
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(e);
        new AlertDialog.Builder(this, dlg())
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> done.accept(e.getText().toString()))
                .setNegativeButton("Cancel", null)
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
            if (!resumed || saverOn || camOpen) return;
            if (drawerOpen || lastSpeed > 2.8f) { // drawer open / driving: wait
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

    int saverMedia = -1;

    void saverMediaPress(int i) {
        if (i == 0) skip(false);
        else if (i == 2) skip(true);
        else playPause();
    }

    /** While the saver is up, a touch only wakes the dashboard; it never presses what is underneath. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (saverMedia >= 0) {                  // a finger on the screensaver's media controls
            if (a == MotionEvent.ACTION_UP) {
                if (saver.mediaHit(e.getX(), e.getY()) == saverMedia) saverMediaPress(saverMedia);
                saver.setMediaPressed(-1);
                saverMedia = -1;
            } else if (a == MotionEvent.ACTION_CANCEL) {
                saver.setMediaPressed(-1);
                saverMedia = -1;
            }
            return true;
        }
        if (saverOn && a == MotionEvent.ACTION_DOWN) {
            int hit = saver.mediaHit(e.getX(), e.getY());
            if (hit >= 0) {                      // media controls: work them, the screensaver stays
                saverMedia = hit;
                saver.setMediaPressed(hit);
                return true;
            }
            // anywhere else: the cat (if it's asleep on the roof) runs off first, then the dashboard comes back
            if (!saver.catTap(this::hideSaver)) hideSaver();
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
        saverAt = SystemClock.elapsedRealtime();
        ui.removeCallbacks(idleR);
        ui.removeCallbacks(ticker);
        saver.setNowPlaying(nowPlayingLine());
        int v = lastSpeed < 0 ? 0 : Math.round(lastSpeed * (mph() ? 2.23694f : 3.6f));
        saver.setSpeed(v >= 5 ? v + (mph() ? " MPH" : " KM/H") : "");
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.start();
        updateCamSession(); // no camera streaming behind the screensaver
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
        if (resumed) {
            // quick fade back to the dashboard (GPU layer: one texture, no redraws)
            saver.animate().alpha(0f).setDuration(220).withLayer().withEndAction(() -> {
                if (!saverOn) { saver.stop(); saver.setVisibility(View.GONE); }
            }).start();
        } else {
            saver.stop();
            saver.setVisibility(View.GONE);
        }
        drive.setActive(resumed);
        if (resumed) renderState(); // restart the progress ticker
        updateCamSession();
        resetIdle();
        if (resumed && SystemClock.elapsedRealtime() - saverAt >= NEW_SESSION_MS) queueGreeting(false, 260);
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
                .setTitle("Screensaver  ·  the moon on the dock starts it now")
                .setPositiveButton("Start now", (d, w) -> showSaver())
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
                .setNegativeButton("Close", null)
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

    // ------------------------------------------------------------------ music card: app widget (optional)
    //
    // Android can't show another app's screen inside ours, but it can host the app's own home-screen
    // widget: Spotify, YouTube Music, AIMP… draw it, Android hands us the result (RemoteViews), and
    // taps go back to the app. It costs a few MB; the music app itself is running anyway when it plays.

    static final int WIDGET_HOST_ID = 0xCA2D, REQ_WIDGET_BIND = 41, REQ_WIDGET_CONFIG = 42;

    String musicCardLabel() {
        if (widgetView == null) return "CarDash player";
        android.appwidget.AppWidgetProviderInfo i = widgetView.getAppWidgetInfo();
        return i == null ? "app widget" : labelOf(i.provider.getPackageName()) + " widget";
    }

    void showMusicCardMenu() {
        String[] items = {
                "CarDash player" + (widgetView == null ? "   \u2713" : "") + "   (lightest: built in)",
                "A music app's own widget…" + (widgetView != null ? "   \u2713  " + musicCardLabel() : "")};
        new AlertDialog.Builder(this, dlg())
                .setTitle("Music card")
                .setItems(items, (d, w) -> { if (w == 0) forgetWidget(); else pickWidget(); })
                .setNegativeButton("Close", null)
                .show();
    }

    void showWidgetMenu() {
        android.appwidget.AppWidgetProviderInfo i = widgetView == null ? null : widgetView.getAppWidgetInfo();
        final String pkg = i == null ? null : i.provider.getPackageName();
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<Runnable> acts = new ArrayList<>();
        if (pkg != null) { labels.add("Open " + labelOf(pkg)); acts.add(() -> launch(pkg)); }
        labels.add("Change widget…"); acts.add(this::pickWidget);
        labels.add("Back to the CarDash player"); acts.add(this::forgetWidget);
        new AlertDialog.Builder(this, dlg())
                .setTitle("Music card  ·  " + musicCardLabel())
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Close", null)
                .show();
    }

    void pickWidget() {
        if (!appsLoaded) { loadApps(this::pickWidget); return; } // for the app icons
        final java.util.List<android.appwidget.AppWidgetProviderInfo> all = new ArrayList<>();
        try { all.addAll(awm.getInstalledProviders()); } catch (Exception ignored) { }
        if (all.isEmpty()) { toast("No app on this head unit offers a widget"); return; }
        final Set<String> music = musicApps();
        final Collator col = Collator.getInstance();
        final Map<String, String> label = new HashMap<>();
        for (android.appwidget.AppWidgetProviderInfo i : all) {
            String app = labelOf(i.provider.getPackageName());
            String w = String.valueOf(i.loadLabel(pm));
            label.put(i.provider.flattenToString(), w.equalsIgnoreCase(app) ? app : app + "  \u00B7  " + w);
        }
        Collections.sort(all, (a, b) -> {
            boolean ma = music.contains(a.provider.getPackageName()), mb = music.contains(b.provider.getPackageName());
            if (ma != mb) return ma ? -1 : 1;
            return col.compare(label.get(a.provider.flattenToString()), label.get(b.provider.flattenToString()));
        });
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return all.size(); }
            @Override public Object getItem(int p) { return all.get(p); }
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
                    tv.setTextColor(look.text);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.leftMargin = dp(16);
                    l.addView(tv, lp);
                    v = l;
                }
                android.appwidget.AppWidgetProviderInfo i = all.get(p);
                LinearLayout l = (LinearLayout) v;
                AppEntry e = byPkg.get(i.provider.getPackageName());
                ((ImageView) l.getChildAt(0)).setImageBitmap(e != null ? e.icon : null);
                ((TextView) l.getChildAt(1)).setText(label.get(i.provider.flattenToString()));
                return v;
            }
        };
        new AlertDialog.Builder(this, dlg())
                .setTitle("Widget for the music card  ·  music apps first")
                .setAdapter(ad, (d, w) -> bindWidget(all.get(w)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Android asks once ("Allow CarDash to create widgets…? Always allow") unless that was already granted. */
    void bindWidget(android.appwidget.AppWidgetProviderInfo info) {
        cancelPendingWidget();
        int id = widgetHost.allocateAppWidgetId();
        sp.edit().putInt("widgetPending", id).apply();
        boolean ok;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null); }
        catch (Exception e) { ok = false; }
        if (ok) { configureWidget(id); return; }
        Intent i = new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivityForResult(i, REQ_WIDGET_BIND);
        } catch (Exception e) {
            cancelPendingWidget();
            new AlertDialog.Builder(this, dlg()).setTitle("Widgets not available")
                    .setMessage("This head unit's Android doesn't let apps host widgets (the permission screen is missing). "
                            + "The CarDash player stays on.")
                    .setPositiveButton("OK", null).show();
        }
    }

    void configureWidget(int id) {
        android.appwidget.AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { cancelPendingWidget(); toast("That widget isn't available any more"); return; }
        boolean optional = Build.VERSION.SDK_INT >= 31
                && (info.widgetFeatures & android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info.configure != null && !optional) {
            try { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_CONFIG, null); return; }
            catch (Exception ignored) { } // not startable: use the widget as it is
        }
        commitWidget(id);
    }

    void commitWidget(int id) {
        sp.edit().remove("widgetPending").apply();
        android.appwidget.AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { widgetHost.deleteAppWidgetId(id); toast("That widget isn't available any more"); return; }
        int old = sp.getInt("widgetId", -1);
        if (old >= 0 && old != id) try { widgetHost.deleteAppWidgetId(old); } catch (Exception ignored) { }
        sp.edit().putInt("widgetId", id).apply();
        showWidget(id, info);
    }

    void cancelPendingWidget() {
        int p = sp.getInt("widgetPending", -1);
        if (p >= 0) {
            try { widgetHost.deleteAppWidgetId(p); } catch (Exception ignored) { }
            sp.edit().remove("widgetPending").apply();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BADGE) {
            if (res == RESULT_OK && data != null && data.getData() != null) onBadgePicked(data.getData());
            return;
        }
        if (req != REQ_WIDGET_BIND && req != REQ_WIDGET_CONFIG) return;
        int id = sp.getInt("widgetPending", -1);
        if (id < 0) return;
        if (res != RESULT_OK) { cancelPendingWidget(); return; }
        if (req == REQ_WIDGET_BIND) configureWidget(id); else commitWidget(id);
    }

    /** At start-up: put the chosen widget back, and drop any widget ids an interrupted setup left behind. */
    void restoreWidget() {
        int id = sp.getInt("widgetId", -1);
        sp.edit().remove("widgetPending").apply();
        try {
            for (int x : widgetHost.getAppWidgetIds()) if (x != id) widgetHost.deleteAppWidgetId(x);
        } catch (Exception ignored) { }
        if (id < 0) return;
        android.appwidget.AppWidgetProviderInfo info = null;
        try { info = awm.getAppWidgetInfo(id); } catch (Exception ignored) { }
        if (info == null) { forgetWidget(); return; } // the app was uninstalled
        showWidget(id, info);
    }

    void showWidget(int id, android.appwidget.AppWidgetProviderInfo info) {
        try {
            android.appwidget.AppWidgetHostView v = widgetHost.createView(this, id, info);
            if (widgetView != null) widgetCard.removeView(widgetView);
            widgetView = v;
            widgetW = widgetH = 0;
            widgetCard.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception e) {
            toast("Couldn't show the widget: " + e.getMessage());
            forgetWidget();
            return;
        }
        ui.removeCallbacks(ticker);
        mediaCard.setVisibility(View.GONE);
        widgetCard.setVisibility(View.VISIBLE);
        widgetListen(true);
    }

    void forgetWidget() {
        int id = sp.getInt("widgetId", -1);
        sp.edit().remove("widgetId").apply();
        if (id >= 0) try { widgetHost.deleteAppWidgetId(id); } catch (Exception ignored) { }
        boolean was = widgetView != null;
        if (widgetView != null) { widgetCard.removeView(widgetView); widgetView = null; }
        widgetCard.setVisibility(View.GONE);
        mediaCard.setVisibility(View.VISIBLE);
        if (was) {
            widgetListen(false);
            trackKey = null;
            renderMeta();
            renderState();
        }
    }

    /** Tell the widget how much room it has, so responsive widgets (Android 12+) pick their best layout. */
    void sizeWidget() {
        if (widgetView == null) return;
        float d = getResources().getDisplayMetrics().density;
        int w = Math.round((widgetCard.getWidth() - widgetCard.getPaddingLeft() - widgetCard.getPaddingRight()) / d);
        int h = Math.round((widgetCard.getHeight() - widgetCard.getPaddingTop() - widgetCard.getPaddingBottom()) / d);
        if (w <= 0 || h <= 0 || (w == widgetW && h == widgetH)) return;
        widgetW = w;
        widgetH = h;
        final android.appwidget.AppWidgetHostView v = widgetView;
        ui.post(() -> {
            if (v != widgetView) return;
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    v.updateAppWidgetSize(new Bundle(), Collections.singletonList(new android.util.SizeF(widgetW, widgetH)));
                } else {
                    //noinspection deprecation
                    v.updateAppWidgetSize(null, widgetW, widgetH, widgetW, widgetH);
                }
            } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------------ greeting

    static final long NEW_SESSION_MS = 10 * 60_000L;
    String greetPhrase = "";

    boolean greetOn() { return sp.getBoolean("greetOn", true); }

    String greetUser() { return sp.getString("greetName", "").trim(); }

    /** 5:00-11:59 morning, 12:00-16:59 afternoon, 17:00-4:59 evening. */
    static String dayGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
    }

    final Runnable greetR = this::runGreeting;

    /** @param welcome first start after install / boot: "Welcome", turning into the time-of-day greeting. */
    void queueGreeting(boolean welcome, long delayMs) {
        if (!greetOn()) return;
        greetPendingWelcome |= welcome;
        ui.removeCallbacks(greetR);
        ui.postDelayed(greetR, delayMs);
    }

    void runGreeting() {
        boolean welcome = greetPendingWelcome;
        greetPendingWelcome = false;
        boolean moving = lastSpeed >= 2f && SystemClock.elapsedRealtime() - lastFix < 5000;
        if (moving || !greetOn()) return; // already driving: no greeting to dismiss
        showGreeting(welcome);
    }

    final Runnable greetToDayR = () -> { if (greetShown && greetWelcome) setGreetingText(false, true); };

    void showGreeting(boolean welcome) {
        greetMoving = 0;
        ui.removeCallbacks(greetToDayR);
        if (welcome) ui.postDelayed(greetToDayR, 4500); // "Welcome, Mahabub" -> "Good evening, Mahabub"
        if (greetShown) { setGreetingText(welcome, true); return; }
        setGreetingText(welcome, false);
        greetShown = true;
        // speed out (it's 0 while parked anyway), greeting in: GPU alpha / translation only
        speedBox.animate().cancel();
        speedBox.animate().alpha(0f).translationY(-dp(6)).setStartDelay(0).setDuration(200)
                .setInterpolator(Anim.OUT).withLayer()
                .withEndAction(() -> { if (greetShown) speedBox.setVisibility(View.INVISIBLE); }).start();
        greetBox.animate().cancel();
        greetBox.setAlpha(1f);
        greetBox.setTranslationY(0f);
        greetBox.setVisibility(View.VISIBLE);
        rise(greetLabel, 140);
        rise(greetName, 230);
    }

    void rise(View v, long delay) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(560).setInterpolator(Anim.OUT).start();
    }

    /** @param morph cross-fade from the text that's showing now */
    void setGreetingText(final boolean welcome, boolean morph) {
        final String user = greetUser();
        final String phrase = welcome ? "Welcome" : dayGreeting();
        if (morph && greetShown) {
            greetWelcome = welcome;
            greetPhrase = phrase;
            greetLabel.animate().cancel();
            greetLabel.animate().alpha(0f).translationY(-dp(6)).setStartDelay(0).setDuration(200).setInterpolator(Anim.OUT).start();
            greetName.animate().cancel();
            greetName.animate().alpha(0f).translationY(-dp(6)).setStartDelay(30).setDuration(200).setInterpolator(Anim.OUT)
                    .withEndAction(() -> {   // both are faded out by now: swap the words, rise back in
                        if (!greetShown) return;
                        applyGreetingText(welcome, phrase, user);
                        rise(greetLabel, 0);
                        rise(greetName, 80);
                    }).start();
            return;
        }
        applyGreetingText(welcome, phrase, user);
    }

    void applyGreetingText(boolean welcome, String phrase, String user) {
        greetWelcome = welcome;
        greetPhrase = phrase;
        if (user.isEmpty()) {   // no name yet: the small line invites you to add one
            greetLabel.setText("TAP TO ADD YOUR NAME");
            greetLabel.setTextColor(look.accent);
            greetName.setText(phrase);
        } else {
            greetLabel.setText(phrase.toUpperCase(Locale.getDefault()));
            greetLabel.setTextColor(look.text2);
            greetName.setText(user);
        }
        fitGreetName();
    }

    /** Largest size up to greetNamePx that fits the slot on one line (long names shrink, then ellipsize). */
    void fitGreetName() {
        float px = greetNamePx > 0 ? greetNamePx : dp(40);
        int avail = (speedBox.getWidth() > 0 ? speedBox.getWidth() : drivePanel.getWidth() / 2) - dp(16);
        android.text.TextPaint tp = new android.text.TextPaint(greetName.getPaint());
        tp.setTextSize(px);
        float w = tp.measureText(greetName.getText().toString());
        if (avail > 0 && w > avail) px = Math.max(dp(22), px * avail / w);
        if (Math.abs(greetName.getTextSize() - px) > 0.5f) greetName.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    void hideGreeting(boolean animate) {
        if (!greetShown) return;
        greetShown = false;
        ui.removeCallbacks(greetToDayR);
        greetBox.animate().cancel();
        speedBox.animate().cancel();
        speedBox.setVisibility(View.VISIBLE);
        if (!animate) {
            greetBox.setVisibility(View.GONE);
            speedBox.setAlpha(1f);
            speedBox.setTranslationY(0f);
            return;
        }
        greetBox.animate().alpha(0f).translationY(-dp(8)).setStartDelay(0).setDuration(240).setInterpolator(Anim.OUT).withLayer()
                .withEndAction(() -> { if (!greetShown) greetBox.setVisibility(View.GONE); }).start();
        speedBox.setAlpha(0f);
        speedBox.setTranslationY(dp(10));
        speedBox.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(420).setInterpolator(Anim.OUT).withLayer().start();
    }

    void editGreetName() {
        editText("Your name  ·  for the greeting", greetUser(), v -> {
            String n = v.trim().replaceAll("\\s+", " ");
            if (n.length() > 20) n = n.substring(0, 20).trim();
            sp.edit().putString("greetName", n).putBoolean("greetOn", true).apply();
            if (greetShown) setGreetingText(greetWelcome, true);
            else queueGreeting(false, 150);
        });
    }

    void showGreetSettings() {
        final boolean on = greetOn();
        final String u = greetUser();
        String[] items = {
                "Your name:  " + (u.isEmpty() ? "not set" : u),
                "Greeting:  " + (on ? "on" : "off"),
                "Show it now"};
        new AlertDialog.Builder(this, dlg())
                .setTitle("Greeting  ·  Welcome after start-up, then good morning / afternoon / evening until you drive off")
                .setNegativeButton("Close", null)
                .setItems(items, (d, w) -> {
                    if (w == 0) editGreetName();
                    else if (w == 1) {
                        sp.edit().putBoolean("greetOn", !on).apply();
                        toast("Greeting: " + (!on ? "on" : "off"));
                        if (on) hideGreeting(true);
                    } else {
                        sp.edit().putBoolean("greetOn", true).apply();
                        ui.removeCallbacks(greetR);
                        showGreeting(false);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ updates (GitHub Releases)

    static final String[] UPD_ACTIONS = {
            "Show the yellow update icon (tap it to download & install)",
            "Download in the background, then ask",
            "Install automatically when parked"};
    static final int UPD_YELLOW = 0xFFFFC21A, UPD_INK = 0xFF231A00;
    static final String DEV_NAME = "Mahabub Subhany", DEV_HANDLE = "ox1d3x3";
    static final String REPO_URL = "https://github.com/" + Updater.REPO, DEV_URL = "https://github.com/" + DEV_HANDLE;

    final Runnable updCheckR = new UpdCheck();
    class UpdCheck implements Runnable {
        @Override public void run() { if (resumed) updater.check(updater.action() != Updater.ACT_NOTIFY, null); }
    }

    /** "Install automatically when parked": installs once the car is parked and nothing is in the way. */
    final Runnable updInstallR = new UpdInstall();
    class UpdInstall implements Runnable {
        @Override public void run() {
            if (!resumed || updater.state != Updater.State.READY || updater.action() != Updater.ACT_AUTO) return;
            Updater.Release r = updater.release;
            if (r == null || r.tag.equals(updAutoTried)) return;
            if (!rootUpdates() && !updater.canInstallWithoutRoot()) return;   // needs the one-time permission: the icon waits for a tap
            boolean parked = lastSpeed < 0.5f && !camOpen && !drawerOpen && !isFinishing();
            if (!parked) { ui.postDelayed(this, 30_000); return; }
            updAutoTried = r.tag;
            toast("Installing CarDash " + r.tag + "…");
            updater.install(rootUpdates());
        }
    }

    boolean rootUpdates() { return booster.deepOn() && RootShell.present(); }

    String updateSummary() {
        String v = "v" + updater.installedName();
        Updater.Release r = updater.release;
        switch (updater.state) {
            case READY: return v + "  ·  " + r.tag + " ready to install";
            case AVAILABLE: return v + "  ·  " + r.tag + " available";
            case DOWNLOADING: return v + "  ·  downloading " + updater.progress + "%";
            default: return v + "  ·  " + (updater.autoCheck() ? "checks automatically" : "automatic check off");
        }
    }

    /** The yellow update icon: amber pill, dark ink, the same in both themes so it always stands out. */
    void styleUpdChip() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(UPD_YELLOW);
        updChip.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), g, null));
        updChip.setTextColor(UPD_INK);
        Drawable ic = getDrawable(R.drawable.ic_update).mutate();
        ic.setTint(UPD_INK);
        int sz = dp(14);
        ic.setBounds(0, 0, sz, sz);
        updChip.setCompoundDrawablesRelative(ic, null, null, null);
        updChip.setCompoundDrawablePadding(dp(5));
    }

    void onUpdateState() {
        Updater.Release r = updater.release;
        String chip = null;
        switch (updater.state) {
            case AVAILABLE: chip = "UPDATE  " + r.tag; break;
            case DOWNLOADING: chip = r.tag + "  " + updater.progress + "%"; break;
            case READY: chip = "INSTALL  " + r.tag; break;
            case INSTALLING: chip = "INSTALLING…"; break;
            case FAILED: if (r != null) chip = "UPDATE  " + r.tag; break; // a failed download: tap to retry
            default: break;
        }
        if (chip == null) {
            if (updChip.getVisibility() == View.VISIBLE) updChip.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(0)
                    .setDuration(200).withLayer().withEndAction(() -> updChip.setVisibility(View.GONE)).start();
        } else {
            setT(updChip, chip);
            if (updChip.getVisibility() != View.VISIBLE) {
                updChip.setAlpha(0f);
                updChip.setScaleX(0.6f);
                updChip.setScaleY(0.6f);
                updChip.setVisibility(View.VISIBLE);
                // pops in, then two soft pulses to catch the eye, then sits still (no endless animation)
                updChip.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380).setInterpolator(Anim.POP).withLayer()
                        .withEndAction(() -> pulse(updChip, 2)).start();
            }
            // tell once per release when it was found in the background
            if (r != null && !updManual && (updater.state == Updater.State.AVAILABLE || updater.state == Updater.State.READY)
                    && !r.tag.equals(sp.getString("updToldTag", ""))) {
                sp.edit().putString("updToldTag", r.tag).apply();
                toast("CarDash " + r.tag + " is available: tap the yellow icon to update");
            }
        }
        if (updater.state == Updater.State.READY) {
            if (updInstallWhenReady) { updInstallWhenReady = false; installUpdate(); }
            else if (updater.action() == Updater.ACT_AUTO) { ui.removeCallbacks(updInstallR); ui.postDelayed(updInstallR, 20_000); }
        }
        if (updManual && checkDlg != null && checkDlg.isShowing() && updater.state != Updater.State.CHECKING) {
            checkDlg.dismiss();
            updManual = false;
            showCheckResult();
        }
        refreshUpdateDialog();
    }

    void pulse(final View v, final int times) {
        if (times <= 0 || v.getVisibility() != View.VISIBLE) return;
        v.animate().scaleX(1.12f).scaleY(1.12f).setStartDelay(260).setDuration(220).setInterpolator(Anim.OUT)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(320).setInterpolator(Anim.SOFT)
                        .withEndAction(() -> pulse(v, times - 1)).start()).start();
    }

    void showCheckResult() {
        switch (updater.state) {
            case UP_TO_DATE: toast("You're on the latest version (v" + updater.installedName() + ")"); break;
            case FAILED:
                if (updater.release == null) {
                    new AlertDialog.Builder(this, dlg()).setTitle("Update check failed").setMessage(updater.error)
                            .setPositiveButton("OK", null).show();
                    break;
                }
                // fall through: a release is known, show it
            default: showUpdateDialog();
        }
    }

    // ---- the update dialog: notes, a progress bar while downloading, one button that does the next step

    AlertDialog checkDlg;
    ProgressBar updBar;
    TextView updStatus;

    void showUpdateDialog() {
        final Updater.Release r = updater.release;
        if (r == null || updater.state == Updater.State.UP_TO_DATE || updater.state == Updater.State.IDLE
                || updater.state == Updater.State.CHECKING) { showUpdates(); return; }
        if (updDlg != null && updDlg.isShowing()) updDlg.dismiss();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        updStatus = new TextView(this);
        updStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        updStatus.setTextColor(look.text2);
        box.addView(updStatus);
        updBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updBar.setMax(100);
        updBar.setProgressTintList(android.content.res.ColorStateList.valueOf(UPD_YELLOW));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        bl.topMargin = dp(10);
        box.addView(updBar, bl);
        if (!r.notes.isEmpty()) {
            TextView notes = new TextView(this);
            notes.setText(r.notes);
            notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            notes.setTextColor(look.text);
            notes.setLineSpacing(0, 1.15f);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(notes);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(dp(300), Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f)));
            nl.topMargin = dp(12);
            box.addView(sv, nl);
        }
        updDlg = new AlertDialog.Builder(this, dlg())
                .setTitle("CarDash " + r.tag + (r.pre ? "  (beta)" : ""))
                .setView(box)
                .setPositiveButton("Download & install", null)   // wired below: the dialog stays open while it downloads
                .setNeutralButton("Later", null)
                .create();
        updDlg.setOnShowListener(d -> {
            updDlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> updatePrimary());
            refreshUpdateDialog();
        });
        final TextView mine = updStatus;
        updDlg.setOnDismissListener(d -> { if (updStatus == mine) { updBar = null; updStatus = null; } });
        updDlg.show();
    }

    /** The dialog's main button: download, then install, or retry. */
    void updatePrimary() {
        switch (updater.state) {
            case READY:
                installUpdate();
                break;
            case AVAILABLE:
            case FAILED:
                updInstallWhenReady = true;   // install as soon as the download is verified
                updater.download();
                break;
            default:
                break;
        }
    }

    void refreshUpdateDialog() {
        if (updDlg == null || !updDlg.isShowing() || updStatus == null) return;
        Updater.Release r = updater.release;
        android.widget.Button b = updDlg.getButton(AlertDialog.BUTTON_POSITIVE);
        String mine = "v" + updater.installedName();
        String status;
        String btn = null;
        boolean bar = false;
        switch (updater.state) {
            case DOWNLOADING: status = "Downloading " + (r != null ? r.tag : "") + "…  " + updater.progress + "%"; bar = true; break;
            case READY: status = mine + " installed  ·  " + (r != null ? r.tag : "update") + " downloaded and verified"; btn = "Install now"; break;
            case INSTALLING: status = "Installing…"; bar = true; break;
            case FAILED: status = updater.error; btn = "Try again"; break;
            case AVAILABLE: status = mine + " installed  ·  " + (r != null ? r.tag : "a new version") + " is available"; btn = "Download & install"; break;
            default: status = mine; break;
        }
        updStatus.setText(status);
        updBar.setVisibility(bar ? View.VISIBLE : View.GONE);
        updBar.setIndeterminate(updater.state == Updater.State.INSTALLING);
        if (bar) updBar.setProgress(updater.progress);
        if (b != null) {
            b.setVisibility(btn == null ? View.GONE : View.VISIBLE);
            if (btn != null) b.setText(btn);
        }
    }

    void installUpdate() {
        if (!rootUpdates() && !updater.canInstallWithoutRoot()) {
            new AlertDialog.Builder(this, dlg())
                    .setTitle("Allow CarDash to install updates")
                    .setMessage("Android asks for this once: switch on \"Allow from this source\" for CarDash, then come back "
                            + "and tap the yellow update icon again.")
                    .setPositiveButton("Open setting", (d, w) -> {
                        if (!tryStart(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                            startSafe(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        Updater.Release r = updater.release;
        toast("Installing CarDash " + (r != null ? r.tag : "update") + "…");
        updater.install(rootUpdates());
    }

    void checkUpdatesNow() {
        updManual = true;
        checkDlg = new AlertDialog.Builder(this, dlg()).setTitle("Updates").setMessage("Checking GitHub for a newer version…")
                .setNegativeButton("Cancel", (d, w) -> updManual = false).show();
        updater.check(false, () -> {
            if (updManual && checkDlg != null && checkDlg.isShowing() && updater.state != Updater.State.CHECKING) {
                checkDlg.dismiss();
                updManual = false;
                showCheckResult();
            }
        });
    }

    void showUpdates() {
        final boolean auto = updater.autoCheck();
        final int act = updater.action();
        final boolean beta = updater.beta();
        String[] items = {
                "Check for updates now",
                "Check automatically (every 6 hours):  " + (auto ? "on" : "off"),
                "When there's an update:  " + UPD_ACTIONS[act],
                "Beta versions (pre-releases):  " + (beta ? "on" : "off"),
                "All releases on GitHub"};
        new AlertDialog.Builder(this, dlg())
                .setTitle("CarDash v" + updater.installedName() + "  ·  updates from GitHub")
                .setNegativeButton("Close", null)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: checkUpdatesNow(); break;
                        case 1:
                            sp.edit().putBoolean("updCheck", !auto).apply();
                            toast("Automatic update check: " + (!auto ? "on" : "off"));
                            if (!auto) updater.check(updater.action() != Updater.ACT_NOTIFY, null);
                            break;
                        case 2:
                            new AlertDialog.Builder(this, dlg()).setTitle("When a new version is found")
                                    .setSingleChoiceItems(UPD_ACTIONS, act, (dd, ww) -> {
                                        sp.edit().putInt("updAction", ww).putBoolean("updCheck", true).apply();
                                        dd.dismiss();
                                        if (ww != Updater.ACT_NOTIFY) updater.check(true, null);
                                    }).show();
                            break;
                        case 3:
                            sp.edit().putBoolean("updBeta", !beta).apply();
                            toast("Beta versions: " + (!beta ? "on" : "off"));
                            updater.check(updater.action() != Updater.ACT_NOTIFY, null);
                            break;
                        default:
                            startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)));
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ about

    void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(pm));
        head.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText("CarDash");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setTextColor(look.text);
        names.addView(name);
        TextView ver = new TextView(this);
        ver.setText("Version " + updater.installedName());
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ver.setTextColor(look.text2);
        names.addView(ver);
        head.addView(names);
        box.addView(head);

        TextView blurb = new TextView(this);
        blurb.setText("A lightweight home screen for Android car head units: fast, smooth and light on memory.");
        blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        blurb.setTextColor(look.text2);
        blurb.setPadding(0, dp(16), 0, dp(8));
        box.addView(blurb);

        box.addView(aboutRow("Dev", DEV_NAME + " (" + DEV_HANDLE + ")", DEV_URL));
        box.addView(aboutRow("Source code", "github.com/" + Updater.REPO, REPO_URL));
        final boolean auto = updater.autoCheck();
        box.addView(aboutRow("Check for updates", auto ? "On  ·  every 6 hours (tap to turn off)" : "Off  (tap to turn on)", null,
                v -> {
                    boolean now = !updater.autoCheck();
                    sp.edit().putBoolean("updCheck", now).apply();
                    ((TextView) ((ViewGroup) v).getChildAt(1)).setText(now ? "On  ·  every 6 hours (tap to turn off)" : "Off  (tap to turn on)");
                    if (now) updater.check(updater.action() != Updater.ACT_NOTIFY, null);
                }));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, dlg())
                .setView(sv)
                .setPositiveButton("Check for updates", (d, w) -> checkUpdatesNow())
                .setNeutralButton("What's new", (d, w) -> startSafe(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(REPO_URL + "/releases/tag/v" + updater.installedName()))))
                .setNegativeButton("Close", null)
                .show();
    }

    View aboutRow(String label, String value, String url) {
        return aboutRow(label, value, url, url == null ? null
                : v -> startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    View aboutRow(String label, String value, String url, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView l = new TextView(this);
        l.setText(label.toUpperCase(Locale.ROOT));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.setLetterSpacing(0.12f);
        l.setTextColor(look.text3);
        row.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.setTextColor(url != null ? look.accent : look.text);
        v.setPadding(0, dp(2), 0, 0);
        row.addView(v);
        if (click != null) {
            row.setBackground(look.rippleRect(dp(10)));
            row.setOnClickListener(click);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = -dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** setText only when it actually changed: avoids needless relayouts every tick. */
    static void setT(TextView tv, CharSequence s) {
        if (!TextUtils.equals(tv.getText(), s)) tv.setText(s);
    }
}
