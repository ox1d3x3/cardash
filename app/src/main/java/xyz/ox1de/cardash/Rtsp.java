package xyz.ox1de.cardash;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.SocketFactory;

/**
 * Minimal RTSP client for live H.264: RTP interleaved over the RTSP TCP connection (no UDP ports,
 * nothing for Wi-Fi to drop), Basic/Digest auth, keep-alive. Plain Java: unit-tested off-device.
 * Blocking; run on a worker thread and {@link #close()} from anywhere to stop.
 */
final class Rtsp implements Closeable {

    interface Listener {
        void onNal(byte[] b, int off, int len);
        void onPictureEnd();          // RTP marker: the current picture is complete
        void log(String s);
    }

    final SocketFactory sf;
    final String url, user, pass;
    final Listener l;
    volatile Socket sock;
    volatile boolean closed;
    OutputStream out;
    DataInputStream in;
    int cseq = 1;
    String session, authHeader, realm, nonce, base, opaque;
    boolean digest, qop;
    int nc;
    long lastKeepAlive;
    int videoCh = 0;

    Rtsp(SocketFactory sf, String url, String user, String pass, Listener l) {
        this.sf = sf;
        this.url = url;
        this.user = user;
        this.pass = pass;
        this.l = l;
    }

    static final class Resp {
        int code;
        final Map<String, String> h = new HashMap<>();
        String body = "";
    }

    /** Connects, negotiates and streams until closed or the connection drops (then throws). */
    void run(int connectTimeoutMs) throws IOException {
        URI u = URI.create(url);
        int port = u.getPort() > 0 ? u.getPort() : 554;
        Socket s = sf.createSocket();
        sock = s;
        if (closed) throw new IOException("closed");
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1 << 20);
        s.connect(new InetSocketAddress(u.getHost(), port), connectTimeoutMs);
        s.setSoTimeout(6000); // no data for 6 s = stream is dead
        out = s.getOutputStream();
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));

        request("OPTIONS", url, null);
        Resp d = request("DESCRIBE", url, "Accept: application/sdp\r\n");
        if (d.code != 200) throw new IOException("DESCRIBE " + d.code);
        base = d.h.containsKey("content-base") ? d.h.get("content-base")
                : d.h.containsKey("content-location") ? d.h.get("content-location") : url;

        // SDP: first video track, its control URL and in-band SPS/PPS (sprop-parameter-sets)
        String control = null, sprop = null;
        boolean video = false, h264 = false;
        for (String line : d.body.split("\r?\n")) {
            if (line.startsWith("m=")) video = line.startsWith("m=video");
            else if (video && control == null && line.startsWith("a=control:")) control = line.substring(10).trim();
            else if (video && line.startsWith("a=rtpmap:") && line.toUpperCase(Locale.US).contains("H264")) h264 = true;
            else if (video && line.startsWith("a=fmtp:") && line.contains("sprop-parameter-sets=")) {
                sprop = line.substring(line.indexOf("sprop-parameter-sets=") + 21).split("[;\\s]")[0];
            }
        }
        l.log("SDP: video=" + (control != null) + " h264=" + h264 + " sprop=" + (sprop != null));
        if (control == null) throw new IOException("no video track in SDP");
        String track = resolve(base, control);

        Resp st = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        if (st.code != 200) throw new IOException("SETUP " + st.code);
        String sess = st.h.get("session");
        if (sess != null) session = sess.split(";")[0].trim();
        String tr = st.h.get("transport");
        if (tr != null && tr.contains("interleaved=")) {
            try { videoCh = Integer.parseInt(tr.substring(tr.indexOf("interleaved=") + 12).split("[-;]")[0].trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (sprop != null) {
            for (String ps : sprop.split(",")) {
                try { byte[] n = Base64.getDecoder().decode(ps.trim()); if (n.length > 0) l.onNal(n, 0, n.length); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        Resp pl = request("PLAY", base, "Range: npt=0.000-\r\n");
        if (pl.code != 200) throw new IOException("PLAY " + pl.code);
        l.log("RTSP playing (session " + session + ", channel " + videoCh + ")");
        lastKeepAlive = System.currentTimeMillis();

        H264.Rtp rtp = new H264.Rtp(new H264.NalSink() {
            @Override public void onNal(byte[] b, int off, int len) { l.onNal(b, off, len); }
        });
        byte[] pkt = new byte[65536];
        while (!closed) {
            int c = in.read();
            if (c < 0) throw new IOException("stream ended");
            if (c == '$') {
                int ch = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                in.readFully(pkt, 0, len);
                if (ch == videoCh && rtp.onPacket(pkt, 0, len)) l.onPictureEnd();
            } else if (c == 'R') {
                readResponse("R"); // reply to a keep-alive
            }
            long now = System.currentTimeMillis();
            if (now - lastKeepAlive > 20000) {
                lastKeepAlive = now;
                send("GET_PARAMETER", base, null);
            }
        }
    }

    static String resolve(String base, String control) {
        if (control.startsWith("rtsp://") || control.startsWith("rtsps://")) return control;
        if (control.equals("*")) return base;
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    Resp request(String method, String uri, String extra) throws IOException {
        send(method, uri, extra);
        Resp r = readResponseSkippingData();
        if (r.code == 401 && user != null && authHeader == null) {
            String wa = r.h.get("www-authenticate");
            if (wa != null) {
                digest = wa.toLowerCase(Locale.US).startsWith("digest");
                realm = param(wa, "realm");
                nonce = param(wa, "nonce");
                opaque = param(wa, "opaque");
                qop = param(wa, "qop").contains("auth");
                authHeader = "set";
                send(method, uri, extra);
                r = readResponseSkippingData();
            }
        }
        l.log(method + " -> " + r.code);
        return r;
    }

    synchronized void send(String method, String uri, String extra) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        b.append("CSeq: ").append(cseq++).append("\r\n");
        b.append("User-Agent: CarDash\r\n");
        if (authHeader != null) b.append("Authorization: ").append(auth(method, uri)).append("\r\n");
        if (session != null) b.append("Session: ").append(session).append("\r\n");
        if (extra != null) b.append(extra);
        b.append("\r\n");
        out.write(b.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    String auth(String method, String uri) {
        if (!digest) {
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        }
        String ha1 = md5(user + ":" + realm + ":" + pass), ha2 = md5(method + ":" + uri);
        StringBuilder b = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append('"');
        if (qop) {
            String ncs = String.format(Locale.US, "%08x", ++nc), cnonce = md5(Long.toString(System.nanoTime())).substring(0, 16);
            b.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cnonce).append("\", response=\"")
                    .append(md5(ha1 + ":" + nonce + ":" + ncs + ":" + cnonce + ":auth:" + ha2)).append('"');
        } else {
            b.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null && !opaque.isEmpty()) b.append(", opaque=\"").append(opaque).append('"');
        return b.toString();
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String param(String h, String name) {
        int i = h.indexOf(name + "=\"");
        if (i < 0) return "";
        int s = i + name.length() + 2, e = h.indexOf('"', s);
        return e > s ? h.substring(s, e) : "";
    }

    /** During setup the server might already push interleaved packets: skip them. */
    Resp readResponseSkippingData() throws IOException {
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("connection closed");
            if (c == '$') {
                in.readUnsignedByte();
                int len = in.readUnsignedShort();
                in.skipBytes(len);
            } else if (c == 'R') {
                return readResponse("R");
            }
        }
    }

    Resp readResponse(String first) throws IOException {
        Resp r = new Resp();
        String status = first + line(in);
        String[] p = status.split(" ");
        try { r.code = Integer.parseInt(p[1]); } catch (Exception e) { r.code = -1; }
        String hl;
        while (!(hl = line(in)).isEmpty()) {
            int c = hl.indexOf(':');
            if (c > 0) r.h.put(hl.substring(0, c).trim().toLowerCase(Locale.US), hl.substring(c + 1).trim());
        }
        int n = 0;
        try { n = Integer.parseInt(r.h.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
        if (n > 0) {
            byte[] body = new byte[n];
            in.readFully(body);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
        return r;
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') b.write(c);
        if (c < 0 && b.size() == 0) throw new IOException("connection closed");
        return b.toString("UTF-8");
    }

    @Override public void close() {
        closed = true;
        try {
            if (session != null && out != null) send("TEARDOWN", base != null ? base : url, null);
        } catch (Exception ignored) { }
        try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) { }
    }
}
