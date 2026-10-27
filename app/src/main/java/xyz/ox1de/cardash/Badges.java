package xyz.ox1de.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Turns an image the owner picks (their car's badge, a sticker, anything) into a clean boot-lid
 * badge: decoded small, a plain background (white, black, any flat colour) cut away, empty margins
 * trimmed, at most 256 px, saved privately as PNG. Runs on a background thread.
 */
final class Badges {

    static final int MAX = 256;

    static File file(Context c) { return new File(c.getFilesDir(), "badge.png"); }

    /** @return null on success, else why it didn't work */
    static String importImage(Context c, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return "That file isn't an image Android can open";
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX * 2) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { src = BitmapFactory.decodeStream(in, null, d); }
            if (src == null) return "That file isn't an image Android can open";
            Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
            if (b != src) src.recycle();
            cutBackground(b);
            Bitmap t = trim(b);
            if (t == null) return "The image is empty once its background is removed";
            if (t != b) b.recycle();
            int m = Math.max(t.getWidth(), t.getHeight());
            if (m > MAX) {
                Bitmap sc = Bitmap.createScaledBitmap(t, Math.max(1, t.getWidth() * MAX / m), Math.max(1, t.getHeight() * MAX / m), true);
                t.recycle();
                t = sc;
            }
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { t.compress(Bitmap.CompressFormat.PNG, 100, out); }
            t.recycle();
            if (!tmp.renameTo(f)) return "Couldn't save the badge";
            return null;
        } catch (SecurityException e) {
            return "Android didn't give CarDash access to that file";
        } catch (Exception | OutOfMemoryError e) {
            return "Couldn't read the image (" + e.getClass().getSimpleName() + ")";
        }
    }

    static Bitmap load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * Cut a plain background: if the four corners are opaque and the same colour, pixels close to
     * that colour become transparent, with a soft edge (no jaggies). Images that already have a
     * transparent background are left alone.
     */
    static void cutBackground(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] cs = {b.getPixel(0, 0), b.getPixel(w - 1, 0), b.getPixel(0, h - 1), b.getPixel(w - 1, h - 1)};
        for (int c : cs) if ((c >>> 24) < 250) return;      // already cut out
        for (int i = 1; i < 4; i++) if (dist(cs[0], cs[i]) > 36) return; // not a plain background
        int kr = 0, kg = 0, kb = 0;
        for (int c : cs) { kr += (c >> 16) & 255; kg += (c >> 8) & 255; kb += c & 255; }
        int key = 0xFF000000 | ((kr / 4) << 16) | ((kg / 4) << 8) | (kb / 4);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                float dd = dist(p, key);
                float a = Math.max(0f, Math.min(1f, (dd - 28f) / 46f));
                row[x] = (Math.round((p >>> 24) * a) << 24) | (p & 0xFFFFFF);
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    static float dist(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** Crop to what's visible, plus a 2 px margin. */
    static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, btm = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 16) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    btm = y;
                }
            }
        }
        if (r < 0) return null;
        l = Math.max(0, l - 2); t = Math.max(0, t - 2); r = Math.min(w - 1, r + 2); btm = Math.min(h - 1, btm + 2);
        if (l == 0 && t == 0 && r == w - 1 && btm == h - 1) return b;
        return Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1);
    }
}
