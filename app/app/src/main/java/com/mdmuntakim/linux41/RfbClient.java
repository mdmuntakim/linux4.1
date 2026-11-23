package com.mdmuntakim.linux41;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal RFB (VNC) 3.8 client: no authentication, 32-bit true colour, Raw
 * encoding.  It is deliberately free of Android imports so the same class is
 * used by the app and by the CI probe that captures screenshots and measures
 * frame rate and input latency against the pinned QEMU.
 */
public final class RfbClient implements Closeable {

    public interface Listener {
        void onConnected(int width, int height);
        void onFrame(int[] argb, int width, int height);
        void onError(Throwable error);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private byte[] raw = new byte[0];

    private volatile int width;
    private volatile int height;
    private int[] pixels;
    private volatile boolean running;
    private Thread reader;

    public RfbClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public int width() { return width; }
    public int height() { return height; }

    /** Handshake.  Call {@link #start()} afterwards to receive frames. */
    public void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1 << 20);
        s.setSendBufferSize(1 << 16);
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);

        byte[] version = new byte[12];
        in.readFully(version);
        String v = new String(version, "US-ASCII");
        if (!v.startsWith("RFB 003.")) throw new IOException("not an RFB server: " + v.trim());
        send("RFB 003.008\n".getBytes("US-ASCII"));

        int count = in.readUnsignedByte();
        if (count == 0) {
            int len = in.readInt();
            if (len > 0 && len < (1 << 20)) in.readFully(new byte[len]);
            throw new IOException("server offered no security type");
        }
        byte[] types = new byte[count];
        in.readFully(types);
        boolean none = false;
        for (byte t : types) if (t == 1) none = true;
        if (!none) throw new IOException("server requires VNC authentication");
        send(new byte[]{1});

        int result = in.readInt();
        if (result != 0) throw new IOException("VNC security result " + result);
        send(new byte[]{1}); // ClientInit: shared desktop

        width = in.readUnsignedShort();
        height = in.readUnsignedShort();
        in.readFully(new byte[16]); // server pixel format, replaced below
        int nameLength = in.readInt();
        if (nameLength < 0 || nameLength > (1 << 20)) throw new IOException("bad desktop name");
        if (nameLength > 0) in.readFully(new byte[nameLength]);
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            throw new IOException("bad framebuffer size " + width + "x" + height);
        }
        pixels = new int[width * height];
        raw = new byte[width * height * 4];

        byte[] spf = new byte[20];
        spf[4] = 32;                 // bits per pixel
        spf[5] = 24;                 // depth
        spf[6] = 0;                  // little endian
        spf[7] = 1;                  // true colour
        spf[9] = (byte) 0xff;        // red max
        spf[11] = (byte) 0xff;       // green max
        spf[13] = (byte) 0xff;       // blue max
        spf[14] = 16;                // red shift
        spf[15] = 8;                 // green shift
        spf[16] = 0;                 // blue shift
        send(spf);

        byte[] enc = new byte[8];
        enc[0] = 2;                  // SetEncodings
        enc[3] = 1;                  // one encoding: Raw (0)
        send(enc);

        requestUpdate(false);
    }

    public void start() {
        running = true;
        reader = new Thread(this::loop, "rfb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void loop() {
        try {
            listener.onConnected(width, height);
            while (running) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: readFramebufferUpdate(); break;
                    case 1: skipColourMap(); break;
                    case 2: break;                       // bell
                    case 3: skipCutText(); break;
                    default: throw new IOException("unknown RFB message " + type);
                }
            }
        } catch (EOFException e) {
            if (running) listener.onError(e);
        } catch (Throwable t) {
            if (running) listener.onError(t);
        }
    }

    private void readFramebufferUpdate() throws IOException {
        in.readUnsignedByte(); // padding
        int rects = in.readUnsignedShort();
        boolean changed = false;
        for (int i = 0; i < rects; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int encoding = in.readInt();
            if (encoding == 0) {
                int need = w * h * 4;
                if (raw.length < need) raw = new byte[need];
                in.readFully(raw, 0, need);
                int p = 0;
                for (int yy = 0; yy < h; yy++) {
                    int off = (y + yy) * width + x;
                    for (int xx = 0; xx < w; xx++) {
                        int b = raw[p] & 0xff;
                        int g = raw[p + 1] & 0xff;
                        int r = raw[p + 2] & 0xff;
                        p += 4;
                        pixels[off++] = 0xff000000 | (r << 16) | (g << 8) | b;
                    }
                }
                changed = true;
            } else if (encoding == -223) {           // DesktopSize
                width = w;
                height = h;
                pixels = new int[width * height];
                raw = new byte[width * height * 4];
                listener.onConnected(width, height);
                changed = true;
            } else if (encoding == -239) {           // Cursor pseudo-encoding
                skipFully(w * h * 4 + ((w * h + 7) / 8));
            } else {
                throw new IOException("unsupported RFB encoding " + encoding);
            }
        }
        if (changed) listener.onFrame(pixels, width, height);
        requestUpdate(true);
    }

    private void skipColourMap() throws IOException {
        in.readUnsignedByte();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        skipFully(count * 6);
    }

    private void skipCutText() throws IOException {
        in.readUnsignedByte();
        in.readUnsignedByte();
        in.readUnsignedByte();
        int len = in.readInt();
        if (len < 0) throw new IOException("bad cut text length");
        skipFully(len);
    }

    private void skipFully(int n) throws IOException {
        while (n > 0) {
            int skipped = in.skipBytes(n);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException();
                skipped = 1;
            }
            n -= skipped;
        }
    }

    public void pointer(int mask, int x, int y) {
        byte[] msg = new byte[6];
        msg[0] = 5;
        msg[1] = (byte) mask;
        put16(msg, 2, x);
        put16(msg, 4, y);
        try { send(msg); } catch (IOException ignored) { }
    }

    public void key(int keysym, boolean down) {
        byte[] msg = new byte[8];
        msg[0] = 4;
        msg[1] = (byte) (down ? 1 : 0);
        msg[4] = (byte) (keysym >>> 24);
        msg[5] = (byte) (keysym >>> 16);
        msg[6] = (byte) (keysym >>> 8);
        msg[7] = (byte) keysym;
        try { send(msg); } catch (IOException ignored) { }
    }

    private void requestUpdate(boolean incremental) {
        byte[] msg = new byte[10];
        msg[0] = 3;
        msg[1] = (byte) (incremental ? 1 : 0);
        put16(msg, 2, 0);
        put16(msg, 4, 0);
        put16(msg, 6, width);
        put16(msg, 8, height);
        try { send(msg); } catch (IOException ignored) { }
    }

    private void send(byte[] data) throws IOException {
        synchronized (writeLock) {
            if (out == null) throw new IOException("not connected");
            out.write(data);
            out.flush();
        }
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    @Override
    public void close() {
        running = false;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
