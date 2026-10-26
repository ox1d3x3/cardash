package xyz.ox1de.cardash;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/** Older DDPAI firmware: raw Annex-B H.264 on a plain TCP port (6200) after "live" is switched on. */
final class RawStream implements Closeable {
    final SocketFactory sf;
    final String host;
    final int port;
    volatile Socket sock;
    volatile boolean closed;

    RawStream(SocketFactory sf, String host, int port) {
        this.sf = sf;
        this.host = host;
        this.port = port;
    }

    void run(H264.NalSink sink, int connectTimeoutMs) throws IOException {
        Socket s = sf.createSocket();
        sock = s;
        if (closed) throw new IOException("closed");
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1 << 20);
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(6000);
        InputStream in = s.getInputStream();
        H264.AnnexB split = new H264.AnnexB(sink);
        byte[] buf = new byte[64 * 1024];
        while (!closed) {
            int n = in.read(buf);
            if (n < 0) throw new IOException("stream ended");
            split.feed(buf, 0, n);
        }
    }

    @Override public void close() {
        closed = true;
        try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) { }
    }
}
