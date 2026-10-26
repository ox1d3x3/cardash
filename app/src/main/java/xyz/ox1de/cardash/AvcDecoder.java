package xyz.ox1de.cardash;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Hardware H.264 decoder straight onto a Surface, tuned for live view: frames are shown the
 * moment they are decoded, and if the decoder falls behind we skip to the next keyframe instead
 * of letting delay build up.
 */
final class AvcDecoder implements H264.AuSink {

    interface Listener {
        void onVideoSize(int w, int h);
        void onFirstFrame();
    }

    Surface surface;
    final Listener l;
    MediaCodec codec;
    Thread drain;
    volatile boolean running, released;
    boolean needKey = true, shown;
    int cw, ch;
    long pts;

    AvcDecoder(Surface s, Listener l) {
        surface = s;
        this.l = l;
    }

    @Override
    public synchronized void onAu(byte[] au, int len, boolean key) {
        if (released || surface == null) return; // nowhere to show it (view hidden): skip decoding entirely
        if (key) {
            byte[] sps = find(au, len, 7), pps = find(au, len, 8);
            int[] wh = sps == null ? null : H264.spsSize(sps);
            boolean resized = wh != null && codec != null && (wh[0] != cw || wh[1] != ch);
            if (codec == null || resized) {
                if (sps == null || pps == null) return; // can't start without the stream's parameter sets
                stopCodec();
                if (!configure(sps, pps, wh == null ? 1920 : wh[0], wh == null ? 1080 : wh[1])) return;
            }
            needKey = false;
        }
        if (codec == null || needKey) return;
        try {
            int idx = codec.dequeueInputBuffer(30_000);
            if (idx < 0) { needKey = true; return; } // behind: drop until the next keyframe (no growing lag)
            ByteBuffer ib = codec.getInputBuffer(idx);
            if (ib == null || ib.capacity() < len) {
                codec.queueInputBuffer(idx, 0, 0, pts, 0);
                needKey = true;
                return;
            }
            ib.clear();
            ib.put(au, 0, len);
            pts += 33_333;
            codec.queueInputBuffer(idx, 0, len, pts, 0);
        } catch (RuntimeException e) { // codec error: rebuild on the next keyframe
            stopCodec();
            needKey = true;
        }
    }

    boolean configure(byte[] sps, byte[] pps, int w, int h) {
        for (int attempt = 0; attempt < 2; attempt++) {
            MediaCodec c = null;
            try {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(withStart(sps)));
                f.setByteBuffer("csd-1", ByteBuffer.wrap(withStart(pps)));
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h, 1 << 20));
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0); // real-time
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                c.configure(f, surface, null, 0);
                c.start();
                codec = c;
                cw = w;
                ch = h;
                shown = false;
                running = true;
                final MediaCodec mc = c;
                drain = new Thread(() -> drainLoop(mc), "avc-out");
                drain.setPriority(Thread.MAX_PRIORITY - 1);
                drain.start();
                ui(w, h);
                return true;
            } catch (Exception e) {
                if (c != null) try { c.release(); } catch (Exception ignored) { }
                // retry once without the low-latency hints some vendor decoders reject
            }
        }
        return false;
    }

    void drainLoop(MediaCodec mc) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int o = mc.dequeueOutputBuffer(info, 20_000);
                if (o >= 0) {
                    mc.releaseOutputBuffer(o, true); // render now: lowest latency
                    if (!shown) { shown = true; l.onFirstFrame(); }
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = mc.getOutputFormat();
                    int w = of.getInteger(MediaFormat.KEY_WIDTH), h = of.getInteger(MediaFormat.KEY_HEIGHT);
                    if (of.containsKey("crop-right") && of.containsKey("crop-left")) w = of.getInteger("crop-right") - of.getInteger("crop-left") + 1;
                    if (of.containsKey("crop-bottom") && of.containsKey("crop-top")) h = of.getInteger("crop-bottom") - of.getInteger("crop-top") + 1;
                    ui(w, h);
                }
            } catch (RuntimeException e) {
                return; // codec stopped / released
            }
        }
    }

    void ui(int w, int h) { if (w > 0 && h > 0) l.onVideoSize(w, h); }

    void stopCodec() {
        running = false;
        Thread d = drain;
        drain = null;
        if (d != null) try { d.join(500); } catch (InterruptedException ignored) { }
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) { }
            try { c.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * Move the picture to another view (home tile <-> full screen) without reconnecting:
     * MediaCodec.setOutputSurface swaps the target live. null = nothing visible: the decoder is
     * stopped and restarts at the next keyframe when a surface comes back.
     */
    synchronized void setSurface(Surface s) {
        if (s == surface) return;
        surface = s;
        if (codec == null) { needKey = true; return; }
        if (s == null || !s.isValid()) { stopCodec(); needKey = true; return; }
        try {
            codec.setOutputSurface(s);
            shown = false; // report "first frame" again for the new view
        } catch (Exception e) {
            stopCodec();
            needKey = true;
        }
    }

    /** Must be called before the Surface goes away. */
    synchronized void release() {
        released = true;
        stopCodec();
    }

    /** Next pictures come from a different stream (front/rear switch): wait for its keyframe. */
    synchronized void expectNewStream() { needKey = true; }

    static byte[] withStart(byte[] nal) {
        byte[] b = new byte[nal.length + 4];
        System.arraycopy(H264.START, 0, b, 0, 4);
        System.arraycopy(nal, 0, b, 4, nal.length);
        return b;
    }

    /** First NAL of the given type inside an Annex-B access unit. */
    static byte[] find(byte[] au, int len, int type) {
        int i = 0;
        while (i + 4 < len) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                int s = i + 4, e = s;
                while (e + 3 < len && !(au[e] == 0 && au[e + 1] == 0 && au[e + 2] == 0 && au[e + 3] == 1)) e++;
                if (e + 3 >= len) e = len;
                if (s < len && (au[s] & 0x1F) == type) return H264.copy(au, s, e - s);
                i = e;
            } else {
                i++;
            }
        }
        return null;
    }
}
