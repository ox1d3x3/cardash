package xyz.ox1de.cardash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDPAI ("vYou") dash cam local API, as documented by the community (hackaday.io/project/167990,
 * github.com/awexandrr/ddpai-api): http://193.168.0.1/vcam/cmd.cgi?cmd=NAME, POST with JSON body,
 * responses {"errcode":0,"data":"..."}; a session id travels as header + cookie.
 * Plain Java (network access is injected) so it is tested against a mock camera off-device.
 */
final class Ddpai {

    interface Opener { URLConnection open(URL u) throws IOException; }

    interface Log { void log(String s); }

    static final String HOST = "193.168.0.1";

    final Opener op;
    final Log log;
    String session;
    String model = "";

    Ddpai(Opener op, Log log) {
        this.op = op;
        this.log = log;
    }

    static final class Res {
        int http = -1, err = -999;
        String body = "", cookie;
    }

    Res cmd(String name, String json, int timeoutMs) {
        Res r = new Res();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) op.open(new URL("http://" + HOST + "/vcam/cmd.cgi?cmd=" + name));
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Connection", "keep-alive");
            if (session != null) {
                c.setRequestProperty("sessionid", session);
                c.setRequestProperty("Cookie", "SessionID=" + session);
            }
            byte[] body = (json == null ? "" : json).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
            r.http = c.getResponseCode();
            r.cookie = c.getHeaderField("Set-Cookie");
            InputStream in = r.http < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 256 * 1024) b.write(buf, 0, n);
                in.close();
                r.body = b.toString("UTF-8");
            }
            r.err = errcode(r.body);
        } catch (Exception e) {
            r.body = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        log.log(name + " -> http " + r.http + ", errcode " + (r.err == -999 ? "?" : r.err));
        return r;
    }

    /**
     * Best-effort login + "start live" sequence, as the official app does. Every step after the
     * first is optional: firmware differs between models, and the stream may work without them.
     * @return true if the camera answered at all.
     */
    boolean handshake(String uid, boolean subStream) {
        Res s = cmd("API_RequestSessionID", "", 2500);
        if (s.http < 0) return false;
        session = extractSession(s.body, s.cookie);
        log.log("session " + (session == null ? "none" : "ok"));
        Res info = cmd("API_GetBaseInfo", "", 2500);
        model = field(info.body, "model");
        if (model.isEmpty()) model = field(info.body, "nickname");
        if (!model.isEmpty()) log.log("camera: " + model);
        cmd("API_RequestCertificate", "{\"user\":\"admin\",\"password\":\"admin\",\"level\":0,\"uid\":\"" + uid + "\"}", 2500);
        cmd("APP_AvCapSet", "{\"stream_type\":" + (subStream ? 1 : 0) + ",\"frmrate\":30}", 2500);
        cmd("APP_PlaybackLiveSwitch", "{\"switch\":\"live\",\"playtime\":\"\"}", 2500);
        return true;
    }

    /** Light periodic call so the camera keeps the app session (and the live stream) alive. */
    void heartbeat() { cmd("API_GetBaseInfo", "", 2500); }

    // ------------------------------------------------------------------ parsing (lenient: firmware varies)

    static final Pattern ERR = Pattern.compile("\"errcode\"\\s*:\\s*(-?\\d+)");
    static final Pattern SESS = Pattern.compile(
            "(?i)\\\\?\"[a-z_]*session[a-z_]*\\\\?\"\\s*:\\s*\\\\?\"([A-Za-z0-9_\\-]+)\\\\?\"");
    static final Pattern COOKIE = Pattern.compile("(?i)SessionID=([^;\\s]+)");

    static int errcode(String body) {
        Matcher m = ERR.matcher(body == null ? "" : body);
        return m.find() ? Integer.parseInt(m.group(1)) : -999;
    }

    static String extractSession(String body, String setCookie) {
        if (body != null) {
            Matcher m = SESS.matcher(body);
            if (m.find()) return m.group(1);
        }
        if (setCookie != null) {
            Matcher m = COOKIE.matcher(setCookie);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Reads "key":"value" even when the value sits inside the escaped JSON string in "data". */
    static String field(String body, String key) {
        if (body == null) return "";
        Matcher m = Pattern.compile("\\\\?\"" + Pattern.quote(key) + "\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]*)").matcher(body);
        return m.find() ? m.group(1) : "";
    }
}
