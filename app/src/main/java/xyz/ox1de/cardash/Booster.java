package xyz.ox1de.cardash;

import android.app.ActivityManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * RAM optimiser.
 *
 * Philosophy: Android already evicts cached apps when it needs memory, and killing apps that
 * would have been reused just makes them cold-start (slower). So this only acts when memory is
 * actually tight, protects anything the car or the driver is using, learns which apps you use,
 * and saves its heavy hammer (root) for apps that keep restarting themselves in the background.
 *
 *  Normal mode (no root): ActivityManager.killBackgroundProcesses(). By design this only removes
 *    *cached / background* processes - it can never kill playing music, running navigation or
 *    anything with a foreground service.
 *  Deep mode (root): sees which apps are really running, verifies what was freed, detects apps
 *    that respawn after being stopped, and blocks them from running in the background
 *    (appops RUN_ANY_IN_BACKGROUND ignore + force-stop). Fully reversible from the UI.
 */
final class Booster {

    interface Listener {
        void onCleaned(Result r);                    // UI thread
        void onAutoBlocked(String pkg);              // UI thread
        void onDeepResult(boolean ok);               // UI thread
    }

    static final class Result {
        long freed, avail, total;
        int stopped = -1; // -1 = unknown (normal mode cannot see other apps' processes)
        boolean manual;
    }

    static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    final Context ctx;
    final SharedPreferences sp, use;
    final ActivityManager am;
    final PackageManager pm;
    final MediaSessionManager msm;
    final ComponentName listenerCn;
    final Handler ui;
    final Listener listener;
    final ExecutorService ex;

    volatile boolean busy;
    long lastAuto;
    final Map<String, Long> killedAt = new HashMap<>(); // optimiser thread only
    RootShell root;                                      // optimiser thread only
    volatile Map<String, String> labels = new HashMap<>(); // launcher's app labels, for name matching
    volatile Set<String> carApps;                          // cached protected phone-connection / car apps
    long carAppsAt;

    Booster(Context c, SharedPreferences prefs, Handler h, ComponentName lcn, Listener l) {
        ctx = c.getApplicationContext();
        sp = prefs;
        use = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE);
        am = ctx.getSystemService(ActivityManager.class);
        pm = ctx.getPackageManager();
        msm = ctx.getSystemService(MediaSessionManager.class);
        listenerCn = lcn;
        ui = h;
        listener = l;
        ex = Executors.newSingleThreadExecutor(new LowPrio());
    }

    static final class LowPrio implements ThreadFactory {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ram-optimiser");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    }

    // ------------------------------------------------------------------ settings

    boolean autoOn() { return sp.getBoolean("boostAuto", true); }
    boolean keepDock() { return sp.getBoolean("boostDock", true); }
    boolean deepOn() { return sp.getBoolean("boostRoot", false); }
    boolean autoBlockOn() { return sp.getBoolean("boostAutoBlock", true); }
    int mode() { return sp.getInt("boostMode", Policy.GENTLE); }

    Set<String> set(String key) {
        Set<String> s = sp.getStringSet(key, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    void putSet(String key, Set<String> v) { sp.edit().putStringSet(key, new HashSet<>(v)).apply(); }

    // ------------------------------------------------------------------ learning

    /** Every launch from CarDash teaches the optimiser what you actually use. */
    void noteLaunch(String pkg) {
        if (pkg == null) return;
        use.edit().putInt("c:" + pkg, use.getInt("c:" + pkg, 0) + 1)
                .putLong("t:" + pkg, System.currentTimeMillis()).apply();
    }

    /** Recently in front, or a regular: keep it cached so reopening is instant. */
    boolean warm(String p, long now, int level, boolean manual, Map<String, Long> usage) {
        long t = use.getLong("t:" + p, 0);
        Long u = usage == null ? null : usage.get(p);
        if (u != null && u > t) t = u; // opened some other way (notification, CarPlay app, voice...)
        return Policy.keepWarm(t, use.getInt("c:" + p, 0), now, level, mode(), manual);
    }

    // ------------------------------------------------------------------ usage access (optional)

    boolean usageAccess() {
        try {
            android.app.AppOpsManager ao = ctx.getSystemService(android.app.AppOpsManager.class);
            int m = android.os.Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.getPackageName())
                    : ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.getPackageName());
            return m == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Last time each app was in front, from Android itself (only if Usage access is granted). */
    Map<String, Long> lastUsed() {
        if (!usageAccess()) return null;
        try {
            android.app.usage.UsageStatsManager usm = ctx.getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            Map<String, Long> m = new HashMap<>();
            for (android.app.usage.UsageStats u : usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 2 * DAY, now)) {
                Long cur = m.get(u.getPackageName());
                if (cur == null || u.getLastTimeUsed() > cur) m.put(u.getPackageName(), u.getLastTimeUsed());
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ memory

    ActivityManager.MemoryInfo mem() {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return m;
    }

    int level(ActivityManager.MemoryInfo m) {
        return Policy.level(m.availMem, m.totalMem, m.threshold, m.lowMemory, mode());
    }

    int usedPct() {
        ActivityManager.MemoryInfo m = mem();
        return Math.round(100f * (m.totalMem - m.availMem) / m.totalMem);
    }

    // ------------------------------------------------------------------ protection

    boolean hardProtected(String p) {
        return p.equals(ctx.getPackageName()) || Policy.carCritical(p, labels.get(p));
    }

    void invalidateCarApps() { carApps = null; }

    /**
     * Phone-connection and car apps, found three ways: package name, app label, and - the reliable
     * one - every app that handles a phone/USB device being plugged in (how wired CarPlay / Android
     * Auto receivers like ZLINK, AutoKit, TLink... start). Cached for 10 minutes.
     */
    Set<String> carApps() {
        Set<String> c = carApps;
        if (c != null && SystemClock.elapsedRealtime() - carAppsAt < 10 * MIN) return c;
        Set<String> s = new HashSet<>();
        for (String action : new String[]{"android.hardware.usb.action.USB_DEVICE_ATTACHED",
                "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"}) {
            android.content.Intent i = new android.content.Intent(action);
            try { for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) s.add(r.activityInfo.packageName); }
            catch (Exception ignored) { }
            try { for (android.content.pm.ResolveInfo r : pm.queryBroadcastReceivers(i, PackageManager.MATCH_ALL)) s.add(r.activityInfo.packageName); }
            catch (Exception ignored) { }
        }
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (Policy.carCritical(ai.packageName, labels.get(ai.packageName))) s.add(ai.packageName);
            }
        } catch (Exception ignored) { }
        s.remove(ctx.getPackageName());
        carApps = s;
        carAppsAt = SystemClock.elapsedRealtime();
        return s;
    }

    Set<String> protectedSet(Set<String> dock, String player) {
        Set<String> p = set("boostNever");
        p.addAll(carApps());
        if (keepDock() && dock != null) p.addAll(dock);
        if (player != null) p.add(player);
        try { for (MediaController c : msm.getActiveSessions(listenerCn)) p.add(c.getPackageName()); }
        catch (Exception ignored) { } // no media access yet
        ContentResolver cr = ctx.getContentResolver();
        components(p, Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD));
        components(p, Settings.Secure.getString(cr, "enabled_notification_listeners"));
        components(p, Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null && tm.getDefaultDialerPackage() != null) p.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) { }
        try { String s = Telephony.Sms.getDefaultSmsPackage(ctx); if (s != null) p.add(s); } catch (Exception ignored) { }
        try {
            WallpaperInfo wi = WallpaperManager.getInstance(ctx).getWallpaperInfo();
            if (wi != null) p.add(wi.getPackageName());
        } catch (Exception ignored) { }
        try {
            DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
            List<ComponentName> admins = dpm == null ? null : dpm.getActiveAdmins();
            if (admins != null) for (ComponentName cn : admins) p.add(cn.getPackageName());
        } catch (Exception ignored) { }
        return p;
    }

    static void components(Set<String> out, String s) {
        if (s == null) return;
        for (String part : s.split(":")) {
            int i = part.indexOf('/');
            if (i > 0) out.add(part.substring(0, i));
        }
    }

    // ------------------------------------------------------------------ triggers (UI thread)

    /** Home shown / periodic / boot. Throttled; does real work only when there is something to do. */
    void auto(Set<String> dock, String player) {
        if (!autoOn() || busy) return;
        long now = SystemClock.elapsedRealtime();
        if (lastAuto != 0 && now - lastAuto < 45_000) return;
        lastAuto = now;
        submit(false, dock, player);
    }

    void cleanNow(Set<String> dock, String player) {
        if (busy) return;
        submit(true, dock, player);
    }

    void submit(final boolean manual, Set<String> dock, final String player) {
        busy = true;
        final Set<String> d = dock == null ? new HashSet<String>() : new HashSet<>(dock);
        ex.execute(() -> {
            Result r = null;
            try { r = pass(manual, d, player); } catch (Throwable ignored) { }
            busy = false;
            final Result rr = r;
            if (rr != null) ui.post(() -> listener.onCleaned(rr));
        });
    }

    // ------------------------------------------------------------------ the pass (optimiser thread)

    Result pass(boolean manual, Set<String> dock, String player) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        ActivityManager.MemoryInfo before = mem();
        int level = manual ? 2 : level(before);
        Set<String> always = set("boostAlways"), blocked = set("boostBlocked");
        boolean deep = deepOn() && shell() != null;
        if (!deep && !manual && level == 0 && always.isEmpty()) return null; // relaxed: let Android do its job

        long now = System.currentTimeMillis();
        Set<String> running = deep ? running() : null;
        if (deep && running == null) deep = false; // ps failed: fall back to normal mode
        Set<String> prot = protectedSet(dock, player);
        Map<String, Long> usage = level > 0 ? lastUsed() : null;
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);

        if (deep) detectRespawns(running, installed, prot, now);
        blocked = set("boostBlocked"); // may have grown

        List<String> kill = new ArrayList<>(), force = new ArrayList<>();
        for (ApplicationInfo ai : installed) {
            String p = ai.packageName;
            if (running != null && !running.contains(p)) continue; // not running: nothing to free
            if (prot.contains(p) || hardProtected(p)) continue;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (deep && blocked.contains(p)) { force.add(p); continue; }
            if (always.contains(p)) { kill.add(p); continue; }
            if (sys || level == 0) continue;               // system apps only via your own lists
            if (warm(p, now, level, manual, usage)) continue; // keep what you use ready
            kill.add(p);
        }
        if (kill.isEmpty() && force.isEmpty()) {
            if (!manual) return null;
            return result(manual, before, before, deep ? 0 : -1);
        }

        for (String p : kill) {
            try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
        }
        if (!force.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String p : force) b.append("am force-stop ").append(p).append('\n');
            root.exec(b.toString(), 10000);
        }
        SystemClock.sleep(900); // let processes actually exit before measuring

        int stopped = -1;
        if (deep) {
            Set<String> after = running();
            stopped = 0;
            for (String p : kill) if (after == null || !after.contains(p)) { stopped++; killedAt.put(p, now); }
            for (String p : force) if (after == null || !after.contains(p)) { stopped++; killedAt.put(p, now); }
        }
        return result(manual, before, mem(), stopped);
    }

    Result result(boolean manual, ActivityManager.MemoryInfo before, ActivityManager.MemoryInfo after, int stopped) {
        Result r = new Result();
        r.manual = manual;
        r.freed = Math.max(0, after.availMem - before.availMem);
        r.avail = after.availMem;
        r.total = after.totalMem;
        r.stopped = stopped;
        sp.edit().putLong("lastFreed", r.freed).putLong("lastAt", System.currentTimeMillis()).apply();
        return r;
    }

    /**
     * Root only: an app we stopped that is running again within 10 minutes, without you opening
     * it, is restarting itself. Three strikes within an hour -> blocked from background running.
     */
    void detectRespawns(Set<String> running, List<ApplicationInfo> installed, Set<String> prot, long now) {
        Map<String, ApplicationInfo> info = new HashMap<>();
        for (ApplicationInfo ai : installed) info.put(ai.packageName, ai);
        Iterator<Map.Entry<String, Long>> it = killedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            String p = e.getKey();
            long at = e.getValue();
            if (now - at > 10 * MIN) { it.remove(); continue; }
            if (!running.contains(p)) continue;
            it.remove();
            if (use.getLong("t:" + p, 0) > at) continue;      // you opened it: not a respawn
            if (prot.contains(p) || hardProtected(p)) continue;
            long first = use.getLong("sf:" + p, 0);
            int n = use.getInt("s:" + p, 0);
            if (now - first > HOUR) { first = now; n = 0; }
            n++;
            use.edit().putLong("sf:" + p, first).putInt("s:" + p, n).apply();
            ApplicationInfo ai = info.get(p);
            boolean sys = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean usedThisWeek = now - use.getLong("t:" + p, 0) < 7 * DAY;
            if (n >= 3 && autoBlockOn() && !sys && !usedThisWeek) {
                block(p, true);
                final String pkg = p;
                ui.post(() -> listener.onAutoBlocked(pkg));
            }
        }
    }

    // ------------------------------------------------------------------ root

    RootShell shell() {
        if (root != null && root.alive()) return root;
        root = deepOn() ? RootShell.open() : null;
        return root;
    }

    /** Package names with at least one live process (root `ps`). */
    Set<String> running() {
        String o = root.exec("ps -A -o NAME 2>/dev/null", 8000);
        if (o == null || o.split("\n").length < 5) o = root.exec("ps -A 2>/dev/null || ps", 8000);
        if (o == null) return null;
        Set<String> s = new HashSet<>();
        for (String line : o.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            String name = t[t.length - 1];
            int c = name.indexOf(':');
            if (c > 0) name = name.substring(0, c);
            if (name.indexOf('.') > 0) s.add(name);
        }
        return s.isEmpty() ? null : s;
    }

    void block(String p, boolean auto) {
        if (shell() == null) return;
        root.exec("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore\nam force-stop " + p, 10000);
        Set<String> b = set("boostBlocked");
        b.add(p);
        putSet("boostBlocked", b);
        if (auto) {
            Set<String> a = set("boostAutoBlocked");
            a.add(p);
            putSet("boostAutoBlocked", a);
        }
    }

    void unblock(String p) {
        if (shell() != null) root.exec("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow", 10000);
        Set<String> b = set("boostBlocked");
        b.remove(p);
        putSet("boostBlocked", b);
        Set<String> a = set("boostAutoBlocked");
        a.remove(p);
        putSet("boostAutoBlocked", a);
        use.edit().remove("s:" + p).remove("sf:" + p).apply();
    }

    void blockAsync(final String p) { ex.execute(() -> block(p, false)); }

    void unblockAsync(final String p) { ex.execute(() -> unblock(p)); }

    /** Turn deep mode on: asks su once (Superuser prompt), reports success on the UI thread. */
    void enableDeep() {
        ex.execute(() -> {
            sp.edit().putBoolean("boostRoot", true).commit();
            RootShell s = RootShell.open();
            boolean ok = s != null;
            if (ok) root = s; else sp.edit().putBoolean("boostRoot", false).apply();
            ui.post(() -> listener.onDeepResult(ok));
        });
    }

    void disableDeep() {
        sp.edit().putBoolean("boostRoot", false).apply();
        ex.execute(() -> {
            if (root != null) { root.close(); root = null; }
            killedAt.clear();
        });
    }

    List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        return l;
    }
}
