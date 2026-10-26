package xyz.ox1de.cardash;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One long-lived `su` shell, reused for every command: a single Superuser grant (and toast)
 * instead of one per clean. Only ever used from the optimiser's background thread.
 */
final class RootShell {
    final Process proc;
    final OutputStream in;
    final BufferedReader out;
    volatile boolean dead;

    private RootShell(Process p) {
        proc = p;
        in = p.getOutputStream();
        out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Cheap check (no prompt): does an su binary exist anywhere usual? */
    static boolean present() {
        String[] dirs = {"/system/bin/", "/system/xbin/", "/sbin/", "/su/bin/", "/system/sbin/",
                "/vendor/bin/", "/odm/bin/", "/debug_ramdisk/", "/data/adb/ksu/bin/", "/data/local/bin/"};
        for (String d : dirs) if (new File(d + "su").exists()) return true;
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(":")) if (new File(d, "su").exists()) return true;
        return false;
    }

    /** Opens a root shell and proves it is uid 0, or returns null. May show the Superuser prompt. */
    static RootShell open() {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            RootShell s = new RootShell(p);
            String id = s.exec("id", 15000);
            if (id != null && id.contains("uid=0")) return s;
            s.close();
        } catch (Exception ignored) { }
        return null;
    }

    boolean alive() {
        if (dead) return false;
        try { proc.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    /** Runs commands, returns their combined output, or null on timeout / dead shell. */
    synchronized String exec(String cmds, long timeoutMs) {
        final String marker = "__cardash_" + System.nanoTime() + "__";
        final boolean[] done = {false};
        Thread watchdog = new Thread(new Watchdog(done, timeoutMs), "su-watchdog");
        watchdog.setDaemon(true);
        try {
            in.write((cmds + "\necho " + marker + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            watchdog.start();
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null) {
                if (line.equals(marker)) { done[0] = true; watchdog.interrupt(); return b.toString(); }
                b.append(line).append('\n');
            }
        } catch (Exception ignored) { }
        done[0] = true;
        dead = true; // stream ended or timed out: never reuse this shell
        proc.destroy();
        return null;
    }

    final class Watchdog implements Runnable {
        final boolean[] done;
        final long ms;
        Watchdog(boolean[] d, long t) { done = d; ms = t; }
        @Override public void run() {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
            if (!done[0]) { dead = true; proc.destroy(); } // unblocks readLine
        }
    }

    void close() {
        dead = true;
        try { in.write("exit\n".getBytes(StandardCharsets.UTF_8)); in.flush(); } catch (Exception ignored) { }
        proc.destroy();
    }
}
