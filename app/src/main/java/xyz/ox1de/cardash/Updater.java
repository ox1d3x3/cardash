package xyz.ox1de.cardash;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from GitHub Releases (github.com/ox1d3x3/cardash).
 *
 *  check     GET the releases list, pick the newest non-draft release (pre-releases only if "beta"
 *            is on) that has an .apk attached and a higher version than the one installed
 *  download  stream the APK into app storage, then verify it: same package, higher version code,
 *            signed with the same key as the installed app
 *  install   root (Deep mode on): silent `pm install -r`. Otherwise Android's PackageInstaller:
 *            the first time Android asks you to confirm; once CarDash has installed itself, Android 12+
 *            lets later updates go through without asking.
 *
 * Network work runs on one low-priority thread; results come back on the UI thread via Listener.
 * The GitHub API allows 60 unauthenticated requests an hour; automatic checks run every 6 hours.
 */
final class Updater {

    static final String REPO = "ox1d3x3/cardash";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=12";
    static final String PAGE = "https://github.com/" + REPO + "/releases";
    static final long AUTO_EVERY = 6 * 3600_000L;

    /** What happens when a check finds a newer release. */
    static final int ACT_NOTIFY = 0, ACT_DOWNLOAD = 1, ACT_AUTO = 2;

    static final class Release {
        String tag, name, notes, apkUrl, pageUrl;
        long apkSize;
        int code;
        boolean pre;
    }

    interface Listener { void onUpdateState(); }

    enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, FAILED }

    final Context ctx;
    final SharedPreferences sp;
    final Handler ui;
    final Listener listener;
    final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "updater");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    volatile State state = State.IDLE;
    volatile Release release;
    volatile File apk;
    volatile int progress;          // 0..100 while downloading
    volatile String error = "";

    Updater(Context c, SharedPreferences sp, Handler ui, Listener l) {
        ctx = c.getApplicationContext();
        this.sp = sp;
        this.ui = ui;
        listener = l;
    }

    // ------------------------------------------------------------------ settings

    /** Check GitHub automatically every 6 hours. On by default. */
    boolean autoCheck() { migrate(); return sp.getBoolean("updCheck", true); }

    /** ACT_NOTIFY (default): show the yellow update icon, download + install when it's tapped. */
    int action() { migrate(); return sp.getInt("updAction", ACT_NOTIFY); }

    /** v2.4 - v2.6 stored one "updMode" (0 off, 1 download + ask, 2 install when parked). */
    private void migrate() {
        if (!sp.contains("updMode")) return;
        int m = sp.getInt("updMode", 2);
        SharedPreferences.Editor e = sp.edit().remove("updMode");
        if (m == 0) e.putBoolean("updCheck", false);
        else e.putInt("updAction", m == 1 ? ACT_DOWNLOAD : ACT_AUTO);
        e.apply();
    }

    boolean beta() { return sp.getBoolean("updBeta", false); }

    boolean dueForAutoCheck() {
        return autoCheck() && System.currentTimeMillis() - sp.getLong("updLast", 0) > AUTO_EVERY;
    }

    String installedName() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    long installedCode() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /**
     * Release tag / versionName -> versionCode, the same formula CI uses (tools/version.sh):
     *   (major*10000 + minor*100 + patch) * 100 + stage
     * stage is 99 for a final release and the beta number (1..98) for a pre-release, so
     *   v2.4 -> 2040099,  v2.4.1 -> 2040199,  v2.5-beta2 -> 2050002  (< v2.5 -> 2050099).
     * "+build" metadata is ignored. -1 if it isn't a version.
     */
    static int code(String v) {
        if (v == null) return -1;
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int cut = v.length();
        for (char ch : new char[]{'-', '+', ' '}) { int i = v.indexOf(ch); if (i >= 0) cut = Math.min(cut, i); }
        String[] p = v.substring(0, cut).split("\\.");
        if (p.length == 0 || p.length > 3 || p[0].isEmpty()) return -1;
        int[] n = new int[3];
        try {
            for (int i = 0; i < p.length; i++) n[i] = Integer.parseInt(p[i]);
        } catch (NumberFormatException e) { return -1; }
        if (n[1] > 99 || n[2] > 99 || n[0] > 2000) return -1;
        int stage = 99;
        if (cut < v.length() && v.charAt(cut) == '-') {
            String suf = v.substring(cut + 1);
            int e = suf.length(), b = e;
            while (b > 0 && Character.isDigit(suf.charAt(b - 1))) b--;
            int k = 1;
            if (b < e) { try { k = Integer.parseInt(suf.substring(b, Math.min(e, b + 3))); } catch (NumberFormatException ignored) { } }
            int plus = suf.indexOf('+');
            if (plus >= 0) {   // "beta2+abc": number before the '+'
                String s2 = suf.substring(0, plus);
                int b2 = s2.length();
                while (b2 > 0 && Character.isDigit(s2.charAt(b2 - 1))) b2--;
                k = b2 < s2.length() ? Integer.parseInt(s2.substring(b2, Math.min(s2.length(), b2 + 3))) : 1;
            }
            stage = Math.max(1, Math.min(98, k));
        }
        return (n[0] * 10000 + n[1] * 100 + n[2]) * 100 + stage;
    }

    void set(State s) {
        state = s;
        ui.post(listener::onUpdateState);
    }

    // ------------------------------------------------------------------ check

    /** @param then optional: runs on the UI thread when the check (and an automatic download) is done */
    void check(final boolean autoDownload, final Runnable then) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING) return;
        set(State.CHECKING);
        ex.execute(() -> {
            try {
                Release r = latest();
                sp.edit().putLong("updLast", System.currentTimeMillis()).apply();
                int mine = code(installedName());
                if (r == null || r.code <= mine) {
                    release = null;
                    set(State.UP_TO_DATE);
                } else {
                    release = r;
                    File f = target(r);
                    if (f.exists() && f.length() == r.apkSize && verify(f) == null) { apk = f; set(State.READY); }
                    else { set(State.AVAILABLE); if (autoDownload) downloadNow(r); }
                }
            } catch (Exception e) {
                error = "Couldn't reach GitHub (" + e.getClass().getSimpleName() + ")";
                set(State.FAILED);
            }
            if (then != null) ui.post(then);
        });
    }

    Release latest() throws Exception {
        JSONArray arr = new JSONArray(get(API));
        boolean beta = beta();
        Release best = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.optBoolean("draft")) continue;
            boolean pre = o.optBoolean("prerelease");
            if (pre && !beta) continue;
            int c = code(o.optString("tag_name"));
            if (c < 0) continue;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) continue;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                String n = a.optString("name");
                if (!n.toLowerCase().endsWith(".apk")) continue;
                if (best == null || c > best.code) {
                    Release r = new Release();
                    r.tag = o.optString("tag_name");
                    r.name = o.optString("name", r.tag);
                    r.notes = cleanNotes(o.optString("body"));
                    r.pageUrl = o.optString("html_url", PAGE);
                    r.apkUrl = a.optString("browser_download_url");
                    r.apkSize = a.optLong("size");
                    r.code = c;
                    r.pre = pre;
                    best = r;
                }
                break;
            }
        }
        return best;
    }

    /** Release notes are markdown: keep the text, drop the markup, cap the length. */
    static String cleanNotes(String md) {
        if (md == null) return "";
        StringBuilder b = new StringBuilder();
        for (String line : md.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("<!--")) continue;
            if (t.equals("---")) break;                       // the download footer the release workflow adds
            while (t.startsWith("#")) t = t.substring(1);
            t = t.trim();
            if (t.startsWith("- ") || t.startsWith("* ")) t = (line.startsWith("  ") ? "    \u25E6  " : "\u2022  ") + t.substring(2);
            t = t.replace("*", "").replace("`", "");
            if (b.length() > 0 || !t.isEmpty()) b.append(t).append('\n');
        }
        String s = b.toString().trim();
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "CarDash/" + installedName());
        try {
            int rc = c.getResponseCode();
            if (rc != 200) throw new java.io.IOException("HTTP " + rc);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                return o.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ download + verify

    File target(Release r) {
        File dir = ctx.getExternalFilesDir("updates");
        if (dir == null) dir = new File(ctx.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "CarDash-" + r.tag.replaceAll("[^A-Za-z0-9._-]", "") + ".apk");
    }

    void download() {
        final Release r = release;
        if (r == null || state == State.DOWNLOADING) return;
        ex.execute(() -> downloadNow(r));
    }

    void downloadNow(Release r) {
        File f = target(r), part = new File(f.getPath() + ".part");
        progress = 0;
        set(State.DOWNLOADING);
        try {
            // tidy older downloads
            File[] old = f.getParentFile().listFiles();
            if (old != null) for (File o : old) if (!o.getName().equals(part.getName())) //noinspection ResultOfMethodCallIgnored
                o.delete();
            HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "CarDash/" + installedName());
            int rc = c.getResponseCode();
            if (rc != 200) throw new java.io.IOException("HTTP " + rc);
            long total = r.apkSize > 0 ? r.apkSize : c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32768];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int p = total > 0 ? (int) (got * 100 / total) : 0;
                    if (p != last && p % 5 == 0) { last = p; progress = p; ui.post(listener::onUpdateState); }
                }
            } finally {
                c.disconnect();
            }
            if (r.apkSize > 0 && part.length() != r.apkSize) throw new java.io.IOException("incomplete download");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!part.renameTo(f)) throw new java.io.IOException("can't save the update");
            String bad = verify(f);
            if (bad != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                error = bad;
                set(State.FAILED);
                return;
            }
            apk = f;
            set(State.READY);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            error = "Download failed (" + e.getMessage() + ")";
            set(State.FAILED);
        }
    }

    /** @return null if the APK is a proper update of this app, else why not. */
    @SuppressWarnings("deprecation")
    String verify(File f) {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo a = pm.getPackageArchiveInfo(f.getPath(), flags);
        if (a == null) return "The download isn't a valid APK";
        if (!ctx.getPackageName().equals(a.packageName)) return "The download is a different app";
        long ac = Build.VERSION.SDK_INT >= 28 ? a.getLongVersionCode() : a.versionCode;
        if (ac <= installedCode()) return "The download isn't newer than this version";
        try {
            PackageInfo me = pm.getPackageInfo(ctx.getPackageName(), flags);
            if (!Arrays.equals(certHash(me), certHash(a)))
                return "The update is signed with a different key: uninstall CarDash and install it manually once";
        } catch (Exception ignored) { }
        return null;
    }

    @SuppressWarnings("deprecation")
    static byte[] certHash(PackageInfo pi) throws Exception {
        Signature[] sig;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            sig = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
        } else {
            sig = pi.signatures;
        }
        if (sig == null || sig.length == 0) return new byte[0];
        return MessageDigest.getInstance("SHA-256").digest(sig[sig.length - 1].toByteArray());
    }

    // ------------------------------------------------------------------ install

    /** @return false if the user first has to allow "Install unknown apps" for CarDash. */
    boolean canInstallWithoutRoot() {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    void install(final boolean useRoot) {
        final File f = apk;
        if (f == null || !f.exists() || state == State.INSTALLING) return;
        set(State.INSTALLING);
        ex.execute(() -> {
            try {
                if (useRoot) {
                    RootShell sh = RootShell.open();
                    if (sh != null) {
                        String out = sh.exec("pm install -r \"" + f.getPath() + "\" 2>&1", 120000);
                        sh.close();
                        if (out != null && out.contains("Success")) return;   // this process is replaced right after
                        error = "Root install failed: " + (out == null ? "timeout" : out.trim());
                        // fall through to the normal installer
                    }
                }
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(ctx.getPackageName());
                p.setSize(f.length());
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent cb = PendingIntent.getBroadcast(ctx, 7, new Intent(ctx, InstallReceiver.class), fl);
                    s.commit(cb.getIntentSender());
                }
            } catch (Exception e) {
                error = "Install failed (" + e.getMessage() + ")";
                set(State.FAILED);
            }
        });
    }

    /** InstallReceiver reports back here when Android refuses or the user cancels. */
    void installFinished(boolean ok, String msg) {
        if (ok) return;
        error = msg == null || msg.isEmpty() ? "Install cancelled" : msg;
        set(apk != null && apk.exists() ? State.READY : State.FAILED);
    }
}
