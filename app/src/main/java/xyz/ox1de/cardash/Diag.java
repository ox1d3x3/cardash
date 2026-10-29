package xyz.ox1de.cardash;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Window;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic logs (Settings → Diagnostic logs, OFF by default). When on, CarDash writes plain-text
 * files into a "cardash" folder on internal storage that the owner can send in with a complaint:
 *
 *   cardash-<date>.txt  device + settings snapshot, then everything CarDash does (lifecycle,
 *                       screensaver, drawer, theme, GPS, media, updates, RAM optimiser, dash cam,
 *                       errors with stack traces) and every 30 s a performance line: frames drawn,
 *                       slow / janky frames and frame-time percentiles, CPU %, memory (PSS, Java and
 *                       native heap, free system RAM), threads, temperature where readable
 *   logcat-<date>.txt   Android's own log for CarDash (with root + Deep mode: the whole system log)
 *
 * Files rotate at 4 MB (logcat 8 MB) and only the newest 12 are kept. Writing happens on one
 * background thread in batches; when logs are off none of this exists (no thread, no listener).
 */
final class Diag {

    static volatile Diag on;                       // null = logging off
    static final long STATS_EVERY = 30_000, FLUSH_EVERY = 2_000;
    static final long MAX_FILE = 4L << 20, MAX_LOGCAT = 8L << 20;
    static final int KEEP_FILES = 12;

    final Context ctx;
    final File dir;
    final HandlerThread thread;
    final Handler h;
    final StringBuilder buf = new StringBuilder(16 * 1024);
    Writer out;
    File file;
    long written;
    java.lang.Process logcat;
    Window frameWindow;
    Window.OnFrameMetricsAvailableListener frameL;

    // frame stats for the current 30 s window (written on the diag thread only)
    final long[] frameNs = new long[4096];
    int frames, slow, janky;
    // CPU accounting
    long lastCpuTicks = -1, lastCpuAt;
    static final long CLK_TCK = clkTck();

    static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    static final SimpleDateFormat FN = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    private Diag(Context c, File d) {
        ctx = c.getApplicationContext();
        dir = d;
        thread = new HandlerThread("cardash-diag", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        h = new Handler(thread.getLooper());
    }

    // ------------------------------------------------------------------ switching on / off

    /** Where the logs go: Internal storage/cardash if CarDash may write there, else Documents/cardash, else app storage. */
    static File pickDir(Context c) {
        File root = Environment.getExternalStorageDirectory();
        File[] tries = {new File(root, "cardash"), new File(root, Environment.DIRECTORY_DOCUMENTS + "/cardash"),
                c.getExternalFilesDir("cardash")};
        for (File d : tries) if (d != null && writable(d)) return d;
        return new File(c.getFilesDir(), "cardash");
    }

    static boolean writable(File d) {
        try {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File t = new File(d, ".probe");
            try (FileOutputStream o = new FileOutputStream(t)) { o.write(1); }
            //noinspection ResultOfMethodCallIgnored
            t.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Can CarDash write Internal storage/cardash (All files access on Android 11+, storage permission before)? */
    static boolean topLevelAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static synchronized void start(Context c, SharedPreferences sp, boolean fullLogcat) {
        if (on != null) return;
        Diag d = new Diag(c, pickDir(c));
        on = d;
        d.h.post(() -> d.open(sp, fullLogcat));
        installCrashHandler();
    }

    static synchronized void stop() {
        Diag d = on;
        if (d == null) return;
        log("diag", "logging switched off");
        on = null;
        d.h.post(d::close);
        d.thread.quitSafely();
    }

    static File currentDir(Context c) { Diag d = on; return d != null ? d.dir : pickDir(c); }

    // ------------------------------------------------------------------ writing

    static void log(String tag, String msg) {
        Diag d = on;
        if (d == null) return;
        String ts;
        synchronized (TS) { ts = TS.format(new Date()); }   // SimpleDateFormat isn't thread-safe
        String line = ts + "  [" + tag + "] " + msg + "\n";
        synchronized (d.buf) { d.buf.append(line); }
    }

    static void error(String tag, String msg, Throwable t) {
        Diag d = on;
        if (d == null) return;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(tag, "ERROR " + msg + "\n" + sw);
    }

    void open(SharedPreferences sp, boolean fullLogcat) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prune();
        newFile();
        header(sp);
        h.postDelayed(flushR, FLUSH_EVERY);
        h.postDelayed(statsR, 5000);
        startLogcat(fullLogcat);
    }

    void newFile() {
        try {
            if (out != null) out.close();
            file = new File(dir, "cardash-" + FN.format(new Date()) + ".txt");
            out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            written = 0;
        } catch (Exception e) {
            out = null;
        }
    }

    final Runnable flushR = new Runnable() {
        @Override public void run() {
            flush();
            if (on == Diag.this) h.postDelayed(this, FLUSH_EVERY);
        }
    };

    void flush() {
        String s;
        synchronized (buf) {
            if (buf.length() == 0) return;
            s = buf.toString();
            buf.setLength(0);
        }
        try {
            if (out == null) return;
            out.write(s);
            out.flush();
            written += s.length();
            if (written > MAX_FILE) { newFile(); prune(); }
        } catch (Exception ignored) { }
    }

    void close() {
        h.removeCallbacksAndMessages(null);
        stopFrames();
        flush();
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;
        if (logcat != null) { logcat.destroy(); logcat = null; }
    }

    /** Keep only the newest files. */
    void prune() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (fs == null || fs.length <= KEEP_FILES) return;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_FILES; i < fs.length; i++) //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
    }

    void header(SharedPreferences sp) {
        StringBuilder b = new StringBuilder();
        b.append("==================== CarDash diagnostic log ====================\n");
        b.append("started    ").append(TS.format(new Date())).append('\n');
        String ver = "?";
        try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        b.append("app        CarDash ").append(ver).append('\n');
        b.append("device     ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("  (board ").append(Build.BOARD)
                .append(", hardware ").append(Build.HARDWARE).append(Build.VERSION.SDK_INT >= 31 ? ", soc " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "")
                .append(")\n");
        b.append("android    ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), build ").append(Build.DISPLAY).append('\n');
        b.append("cpu        ").append(Runtime.getRuntime().availableProcessors()).append(" cores, ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        b.append("memory     total ").append(mb(mi.totalMem)).append(", available ").append(mb(mi.availMem)).append(", low-RAM device ")
                .append(am.isLowRamDevice()).append(", app heap limit ").append(am.getMemoryClass()).append(" MB\n");
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        Display disp = ctx.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        b.append("display    ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" px, ").append(dm.densityDpi)
                .append(" dpi (density ").append(dm.density).append("), ")
                .append(disp != null ? String.format(Locale.US, "%.1f Hz", disp.getRefreshRate()) : "? Hz");
        if (disp != null) {
            android.graphics.Point real = new android.graphics.Point();
            disp.getRealSize(real);
            b.append(", panel ").append(real.x).append('x').append(real.y);
        }
        b.append('\n');
        b.append("storage    logs in ").append(dir.getAbsolutePath()).append('\n');
        b.append("settings   ");
        Map<String, ?> all = new TreeMap<>(sp.getAll());
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("mru") || k.startsWith("camLog") || k.startsWith("c:")) continue; // long / noisy
            b.append(k).append('=').append(e.getValue()).append("  ");
        }
        b.append("\n================================================================\n");
        synchronized (buf) { buf.insert(0, b); }
    }

    static String mb(long bytes) { return (bytes >> 20) + " MB"; }

    // ------------------------------------------------------------------ Android's log

    void startLogcat(boolean full) {
        if (full && !startLogcatCmd(true)) startLogcatCmd(false);   // no root after all: CarDash's own log
        else if (!full) startLogcatCmd(false);
    }

    boolean startLogcatCmd(boolean full) {
        try {
            String[] cmd = full ? new String[]{"su", "-c", "logcat -v threadtime"}
                    : new String[]{"logcat", "-v", "threadtime", "--pid=" + Process.myPid()};
            logcat = Runtime.getRuntime().exec(cmd);
            final InputStream in = logcat.getInputStream();
            final File lf = new File(dir, "logcat-" + FN.format(new Date()) + ".txt");
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                byte[] b = new byte[16384];
                File cur = lf;
                long size = 0;
                OutputStream o = null;
                try {
                    o = new FileOutputStream(cur, true);
                    o.write(("---- logcat (" + (full ? "whole system, root" : "CarDash only") + ") ----\n").getBytes(StandardCharsets.UTF_8));
                    int n;
                    while ((n = in.read(b)) > 0) {
                        o.write(b, 0, n);
                        size += n;
                        if (size > MAX_LOGCAT) {
                            o.close();
                            cur = new File(dir, "logcat-" + FN.format(new Date()) + ".txt");
                            o = new FileOutputStream(cur, true);
                            size = 0;
                            prune();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (o != null) o.close(); } catch (Exception ignored) { }
                }
            }, "cardash-logcat");
            t.setDaemon(true);
            t.start();
            log("diag", "logcat capture started (" + (full ? "whole system, root" : "CarDash's own log") + ")");
            return true;
        } catch (Exception e) {
            log("diag", "logcat capture unavailable" + (full ? " with root" : "") + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ frame timing (Android's own per-frame metrics)

    /** Measure every frame the dashboard window draws (only while logging). */
    static void watchFrames(Window w) {
        Diag d = on;
        if (d == null || Build.VERSION.SDK_INT < 24) return;
        d.h.post(() -> d.startFrames(w));
    }

    static void unwatchFrames() {
        Diag d = on;
        if (d != null) d.h.post(d::stopFrames);
    }

    void startFrames(Window w) {
        if (frameL != null) return;
        frameWindow = w;
        frameL = (window, m, dropped) -> {
            long ns = m.getMetric(FrameMetrics.TOTAL_DURATION);
            if (frames < frameNs.length) frameNs[frames] = ns;
            frames++;
            if (ns > 16_700_000L) slow++;
            if (ns > 33_400_000L) janky++;
        };
        try { w.addOnFrameMetricsAvailableListener(frameL, h); } catch (Exception e) { frameL = null; }
    }

    void stopFrames() {
        if (frameL == null || frameWindow == null) return;
        try { frameWindow.removeOnFrameMetricsAvailableListener(frameL); } catch (Exception ignored) { }
        frameL = null;
        frameWindow = null;
    }

    // ------------------------------------------------------------------ performance line every 30 s

    final Runnable statsR = new Runnable() {
        @Override public void run() {
            try { stats(); } catch (Throwable ignored) { }
            if (on == Diag.this) h.postDelayed(this, STATS_EVERY);
        }
    };

    void stats() {
        StringBuilder b = new StringBuilder("perf  ");
        int n = Math.min(frames, frameNs.length);
        if (frames > 0) {
            long[] f = Arrays.copyOf(frameNs, n);
            Arrays.sort(f);
            b.append(String.format(Locale.US, "frames %d (%.1f/s), >16ms %d, >33ms %d, p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, max %.1f ms",
                    frames, frames * 1000f / STATS_EVERY, slow, janky, f[n / 2] / 1e6, f[(int) (n * 0.9)] / 1e6, f[Math.min(n - 1, (int) (n * 0.99))] / 1e6, f[n - 1] / 1e6));
        } else {
            b.append("frames 0 (idle)");
        }
        frames = slow = janky = 0;
        // CPU of this process
        long ticks = cpuTicks(), now = SystemClock.elapsedRealtime();
        if (ticks >= 0 && lastCpuTicks >= 0 && now > lastCpuAt) {
            float pct = 100f * (ticks - lastCpuTicks) * 1000f / CLK_TCK / (now - lastCpuAt);
            b.append(String.format(Locale.US, " | cpu %.1f%%", pct));
        }
        lastCpuTicks = ticks;
        lastCpuAt = now;
        // memory
        Runtime rt = Runtime.getRuntime();
        b.append(" | pss ").append(Debug.getPss() / 1024).append(" MB, java ").append(mb(rt.totalMemory() - rt.freeMemory()))
                .append(" of ").append(mb(rt.maxMemory())).append(", native ").append(mb(Debug.getNativeHeapAllocatedSize()));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        b.append(" | system free ").append(mb(mi.availMem)).append(" of ").append(mb(mi.totalMem)).append(mi.lowMemory ? " LOW" : "");
        b.append(" | threads ").append(Thread.activeCount());
        String temp = temperature();
        if (temp != null) b.append(" | temp ").append(temp);
        log("perf", b.substring(6));
    }

    static long clkTck() {
        try { return android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK); } catch (Throwable t) { return 100; }
    }

    static long cpuTicks() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String s = r.readLine();
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);    // utime + stime
        } catch (Exception e) {
            return -1;
        }
    }

    /** Hottest readable thermal zone, if the unit exposes any. */
    static String temperature() {
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < 12; i++) {
            try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone" + i + "/temp"))) {
                int v = Integer.parseInt(r.readLine().trim());
                if (v > 1000) v /= 1000;
                if (v > 0 && v < 150 && v > best) best = v;
            } catch (Exception ignored) { }
        }
        return best == Integer.MIN_VALUE ? null : best + " °C";
    }

    // ------------------------------------------------------------------ crashes

    static boolean crashHook;

    static void installCrashHandler() {
        if (crashHook) return;
        crashHook = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Diag d = on;
            if (d != null) {
                error("crash", "uncaught in thread " + t.getName(), e);
                synchronized (d.buf) {                    // write it out right now: the process is about to die
                    try { if (d.out != null) { d.out.write(d.buf.toString()); d.out.flush(); } } catch (Exception ignored) { }
                    d.buf.setLength(0);
                }
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
