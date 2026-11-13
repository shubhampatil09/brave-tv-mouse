package com.tvmouse.receiver;

import android.util.Base64;
import android.util.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal adb client: connects to the TV's own adb daemon (127.0.0.1:5555), authenticates
 * with an RSA key kept in the app's files, and opens one service stream for writing.
 * Used to run the system "hid" tool with shell rights, which an app cannot do itself.
 */
final class AdbStream implements Closeable {
    private static final String TAG = "TvMouse";
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int LOCAL_ID = 1;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object lock = new Object();
    private int remoteId;
    private boolean canWrite;
    private volatile boolean closed;

    private AdbStream(Socket s) throws IOException {
        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Opens service (for example "exec:hid -"). The first connection makes the TV show its
     * "Allow USB debugging?" dialog; this call waits up to promptWaitMs for it to be accepted.
     */
    static AdbStream open(File keyDir, String service, int promptWaitMs) throws Exception {
        KeyPair keys = loadOrCreateKeys(keyDir);
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(5000);
        AdbStream a = new AdbStream(s);
        try {
            a.send(A_CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Msg m = a.read();
                if (m.cmd == A_CNXN) break;
                if (m.cmd != A_AUTH || m.arg0 != 1) throw new IOException("unexpected adb reply");
                if (!signed) {
                    signed = true;
                    a.send(A_AUTH, 2, 0, sign(keys.getPrivate(), m.data));
                } else {
                    Log.i(TAG, "adb: waiting for 'Allow USB debugging' to be accepted on the TV");
                    s.setSoTimeout(promptWaitMs);
                    a.send(A_AUTH, 3, 0, (publicKeyString((RSAPublicKey) keys.getPublic()) + "\0")
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            s.setSoTimeout(5000);
            a.send(A_OPEN, LOCAL_ID, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            Msg m = a.read();
            if (m.cmd != A_OKAY) throw new IOException("adb refused " + service);
            a.remoteId = m.arg0;
            a.canWrite = true;
            s.setSoTimeout(0);
            new Thread(a::readLoop, "tvmouse-adb").start();
            return a;
        } catch (Exception e) {
            a.close();
            throw e;
        }
    }

    /** Blocks until the daemon has acknowledged the previous write. */
    void write(byte[] data) throws IOException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!canWrite && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("adb write timed out");
                try { lock.wait(left); } catch (InterruptedException e) { throw new IOException(e); }
            }
            if (closed) throw new IOException("adb stream closed");
            canWrite = false;
            send(A_WRTE, LOCAL_ID, remoteId, data);
        }
    }

    boolean isClosed() { return closed; }

    @Override
    public void close() {
        closed = true;
        try { sock.close(); } catch (Exception ignored) { }
        synchronized (lock) { lock.notifyAll(); }
    }

    private void readLoop() {
        try {
            while (!closed) {
                Msg m = read();
                if (m.cmd == A_OKAY) {
                    synchronized (lock) { canWrite = true; lock.notifyAll(); }
                } else if (m.cmd == A_WRTE) {
                    String text = new String(m.data, StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) Log.i(TAG, "hid: " + text);
                    synchronized (lock) { send(A_OKAY, LOCAL_ID, remoteId, new byte[0]); }
                } else if (m.cmd == A_CLSE) {
                    break;
                }
            }
        } catch (Exception e) {
            if (!closed) Log.w(TAG, "adb stream ended: " + e);
        }
        close();
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > (1 << 20)) throw new IOException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        b.put(data);
        synchronized (out) {
            out.write(b.array());
            out.flush();
        }
    }

    // ---- keys ----

    private static KeyPair loadOrCreateKeys(File dir) throws Exception {
        File priv = new File(dir, "adbkey.pk8"), pub = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream i = new DataInputStream(new FileInputStream(f))) { i.readFully(b); }
        return b;
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** Android's own RSA public key layout (mincrypt), base64, as adb expects it. */
    private static String publicKeyString(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(key.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvmouse@tv";
    }
}
