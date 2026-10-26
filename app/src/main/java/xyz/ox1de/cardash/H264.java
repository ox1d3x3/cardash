package xyz.ox1de.cardash;

/**
 * H.264 plumbing in plain Java (unit-tested off-device):
 *  - Annex-B byte stream -> NAL units
 *  - RTP (RFC 6184: single NAL, STAP-A, FU-A) -> NAL units
 *  - NAL units -> access units (one picture each, with start codes) ready for MediaCodec
 *  - SPS -> picture width/height
 */
final class H264 {
    private H264() { }

    interface NalSink { void onNal(byte[] b, int off, int len); }

    interface AuSink { void onAu(byte[] au, int len, boolean key); }

    static final byte[] START = {0, 0, 0, 1};

    static int type(byte[] b, int off) { return b[off] & 0x1F; }

    // ------------------------------------------------------------------ Annex-B splitter

    /** Incremental splitter for a raw H.264 elementary stream (00 00 01 / 00 00 00 01 start codes). */
    static final class AnnexB {
        final NalSink sink;
        byte[] buf = new byte[256 * 1024];
        int len, nal = -1, scan;

        AnnexB(NalSink s) { sink = s; }

        void feed(byte[] d, int off, int n) {
            if (len + n > buf.length) {
                int cap = buf.length;
                while (cap < len + n) cap *= 2;
                if (cap > 16 << 20) { len = 0; nal = -1; scan = 0; return; } // garbage: resync
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(d, off, buf, len, n);
            len += n;
            int i = Math.max(scan, 0);
            while (i + 2 < len) {
                if ((buf[i + 2] & 0xFF) > 1) { i += 3; continue; } // fast skip: cannot be part of a start code here
                if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                    int end = (i > 0 && buf[i - 1] == 0) ? i - 1 : i;
                    if (nal >= 0 && end > nal) sink.onNal(buf, nal, end - nal);
                    nal = i + 3;
                    i += 3;
                } else {
                    i++;
                }
            }
            scan = Math.max(len - 3, 0);
            // compact: keep only the unfinished NAL (or the last few bytes while hunting a start code)
            int keep = nal >= 0 ? nal : Math.max(len - 3, 0);
            if (keep > 0) {
                System.arraycopy(buf, keep, buf, 0, len - keep);
                len -= keep;
                scan = Math.max(scan - keep, 0);
                if (nal >= 0) nal -= keep;
            }
        }
    }

    // ------------------------------------------------------------------ RTP depacketizer

    static final class Rtp {
        final NalSink sink;
        byte[] fu = new byte[512 * 1024];
        int fuLen, lastSeq = -1;
        boolean fuOn;
        int lost;

        Rtp(NalSink s) { sink = s; }

        /** @return true if the RTP marker bit is set (last packet of a picture). */
        boolean onPacket(byte[] p, int off, int len) {
            if (len < 12 || (p[off] & 0xC0) != 0x80) return false;
            int cc = p[off] & 0x0F;
            boolean ext = (p[off] & 0x10) != 0, pad = (p[off] & 0x20) != 0;
            boolean marker = (p[off + 1] & 0x80) != 0;
            int seq = ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
            if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xFFFF)) { lost++; fuOn = false; } // gap: drop partial NAL
            lastSeq = seq;
            int h = off + 12 + cc * 4;
            int end = off + len;
            if (pad) end -= p[end - 1] & 0xFF;
            if (ext) {
                if (h + 4 > end) return marker;
                h += 4 + (((p[h + 2] & 0xFF) << 8) | (p[h + 3] & 0xFF)) * 4;
            }
            if (h >= end) return marker;
            int t = p[h] & 0x1F;
            if (t >= 1 && t <= 23) {
                sink.onNal(p, h, end - h);
            } else if (t == 24) {                                   // STAP-A
                int i = h + 1;
                while (i + 2 <= end) {
                    int n = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                    i += 2;
                    if (n == 0 || i + n > end) break;
                    sink.onNal(p, i, n);
                    i += n;
                }
            } else if (t == 28 && h + 2 <= end) {                   // FU-A
                int fh = p[h + 1];
                boolean s = (fh & 0x80) != 0, e = (fh & 0x40) != 0;
                if (s) {
                    fuOn = true;
                    fuLen = 0;
                    put((byte) ((p[h] & 0xE0) | (fh & 0x1F)));
                }
                if (fuOn) {
                    for (int i = h + 2; i < end; i++) put(p[i]);
                    if (e) { fuOn = false; sink.onNal(fu, 0, fuLen); }
                }
            }
            return marker;
        }

        void put(byte b) {
            if (fuLen == fu.length) {
                if (fu.length >= 8 << 20) { fuOn = false; return; }
                byte[] n = new byte[fu.length * 2];
                System.arraycopy(fu, 0, n, 0, fuLen);
                fu = n;
            }
            fu[fuLen++] = b;
        }
    }

    // ------------------------------------------------------------------ access units

    /** Groups NAL units into pictures. Call {@link #flush()} on an RTP marker for one frame less latency. */
    static final class Assembler implements NalSink {
        final AuSink sink;
        byte[] au = new byte[512 * 1024];
        int len;
        boolean vcl, key, hasSps, hasPps;
        byte[] sps, pps;
        byte[] withPs = new byte[0];

        Assembler(AuSink s) { sink = s; }

        @Override public void onNal(byte[] b, int off, int n) {
            if (n <= 0) return;
            int t = b[off] & 0x1F;
            boolean slice = t == 1 || t == 5;
            boolean firstSlice = slice && n > 1 && (b[off + 1] & 0x80) != 0; // first_mb_in_slice == 0
            boolean startsNew = t == 9 || t == 7 || t == 8 || t == 6 || (t >= 14 && t <= 18) || firstSlice;
            if (vcl && startsNew) flush();
            if (t == 7) { sps = copy(b, off, n); hasSps = true; }
            if (t == 8) { pps = copy(b, off, n); hasPps = true; }
            if (t == 9) return; // access unit delimiters carry nothing the decoder needs
            ensure(n + 4);
            System.arraycopy(START, 0, au, len, 4);
            System.arraycopy(b, off, au, len + 4, n);
            len += n + 4;
            if (slice) vcl = true;
            if (t == 5) key = true;
        }

        /**
         * Many cameras send SPS/PPS only once (in the RTSP SDP, or at stream start). Re-attach the
         * latest ones to every keyframe that lacks them, so any keyframe can (re)start a decoder.
         */
        void flush() {
            if (vcl && len > 0) {
                if (key && (!hasSps || !hasPps) && sps != null && pps != null) {
                    int n = 8 + sps.length + pps.length + len;
                    if (withPs.length < n) withPs = new byte[n + 4096];
                    int p = 0;
                    System.arraycopy(START, 0, withPs, p, 4); p += 4;
                    System.arraycopy(sps, 0, withPs, p, sps.length); p += sps.length;
                    System.arraycopy(START, 0, withPs, p, 4); p += 4;
                    System.arraycopy(pps, 0, withPs, p, pps.length); p += pps.length;
                    System.arraycopy(au, 0, withPs, p, len);
                    sink.onAu(withPs, n, true);
                } else {
                    sink.onAu(au, len, key);
                }
            }
            len = 0;
            vcl = false;
            key = false;
            hasSps = false;
            hasPps = false;
        }

        void ensure(int extra) {
            if (len + extra <= au.length) return;
            int cap = au.length;
            while (cap < len + extra) cap *= 2;
            byte[] n = new byte[cap];
            System.arraycopy(au, 0, n, 0, len);
            au = n;
        }
    }

    static byte[] copy(byte[] b, int off, int n) {
        byte[] c = new byte[n];
        System.arraycopy(b, off, c, 0, n);
        return c;
    }

    // ------------------------------------------------------------------ SPS

    /** @return {width, height} of the picture, or null if the SPS can't be read. */
    static int[] spsSize(byte[] nal) {
        try {
            // strip emulation-prevention bytes (00 00 03 -> 00 00)
            byte[] r = new byte[nal.length];
            int n = 0, zeros = 0;
            for (int i = 1; i < nal.length; i++) { // skip NAL header
                int v = nal[i] & 0xFF;
                if (zeros >= 2 && v == 3) { zeros = 0; continue; }
                zeros = v == 0 ? zeros + 1 : 0;
                r[n++] = (byte) v;
            }
            Bits b = new Bits(r, n);
            int profile = b.u(8);
            b.u(8);
            b.u(8);
            b.ue();
            int chroma = 1;
            boolean sep = false;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                    || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139
                    || profile == 134 || profile == 135) {
                chroma = b.ue();
                if (chroma == 3) sep = b.u(1) == 1;
                b.ue();
                b.ue();
                b.u(1);
                if (b.u(1) == 1) {
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (b.u(1) == 1) {
                            int size = i < 6 ? 16 : 64, last = 8, next = 8;
                            for (int j = 0; j < size; j++) {
                                if (next != 0) next = (last + b.se() + 256) % 256;
                                last = next == 0 ? last : next;
                            }
                        }
                    }
                }
            }
            b.ue();
            int poc = b.ue();
            if (poc == 0) {
                b.ue();
            } else if (poc == 1) {
                b.u(1);
                b.se();
                b.se();
                int k = b.ue();
                for (int i = 0; i < k; i++) b.se();
            }
            b.ue();
            b.u(1);
            int wmb = b.ue() + 1, hmu = b.ue() + 1;
            int frameMbsOnly = b.u(1);
            if (frameMbsOnly == 0) b.u(1);
            b.u(1);
            int cl = 0, cr = 0, ct = 0, cb = 0;
            if (b.u(1) == 1) { cl = b.ue(); cr = b.ue(); ct = b.ue(); cb = b.ue(); }
            int cux, cuy;
            if (chroma == 0 || sep) { cux = 1; cuy = 2 - frameMbsOnly; }
            else { cux = chroma == 3 ? 1 : 2; cuy = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly); }
            int w = wmb * 16 - (cl + cr) * cux;
            int h = (2 - frameMbsOnly) * hmu * 16 - (ct + cb) * cuy;
            return (w > 0 && h > 0 && w <= 8192 && h <= 8192) ? new int[]{w, h} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final class Bits {
        final byte[] d;
        final int bits;
        int pos;

        Bits(byte[] d, int n) { this.d = d; bits = n * 8; }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= bits) throw new IllegalStateException("eof");
                v = (v << 1) | ((d[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        int ue() {
            int z = 0;
            while (u(1) == 0) if (++z > 31) throw new IllegalStateException("bad ue");
            return (1 << z) - 1 + (z == 0 ? 0 : u(z));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }
}
