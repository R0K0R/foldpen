package dev.foldpen;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP link to the foldpen daemon. Input goes out through a dedicated sender
 * thread (no Nagle, one write per message); frames and state come back on the
 * reader. Hosts tried, in order: a host set over adb, 127.0.0.1 (adb reverse
 * over USB), and whatever PC last sent a LAN beacon.
 *
 * Over the LAN, pen samples go over UDP once the daemon confirms it hears
 * them (see the daemon's UDP_KEEPALIVE comment for the format): over TCP one
 * lost Wi-Fi packet stalls every sample behind it until the retransmit. Each
 * datagram repeats the last UDP_HISTORY samples, and the last one is resent
 * UDP_REPEATS times when no new sample follows, so a loss costs nothing.
 */
final class Link {
    interface Listener {
        void onConnected(boolean connected);
        void onState(JSONObject state);
        void onFrame(byte[] jpeg);
        void onPairNeeded();
    }

    static final int PORT = 7392, BEACON_PORT = 7393;
    static final int T_HELLO = 1, T_PEN = 2, T_TOUCH = 3, T_CMD = 4, T_VIEW = 5;
    static final int T_STATE = 10, T_FRAME = 11;

    private final Context ctx;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile String beaconHost;
    private volatile int beaconPort = PORT;
    private volatile String pairCode;
    private volatile boolean needPair;
    private Thread connector, beacon, sender;
    private DatagramSocket beaconSocket;
    private WifiManager.MulticastLock lock;
    private WifiManager.WifiLock lowLatency;
    volatile int viewW, viewH;

    // pen over UDP
    static final int UDP_HISTORY = 4, UDP_REPEATS = 2;
    static final long UDP_REPEAT_MS = 12, UDP_PROBE_MS = 300, UDP_STALE_MS = 6000;
    private final LinkedBlockingQueue<byte[]> penQueue = new LinkedBlockingQueue<>();
    private volatile DatagramSocket udp;
    private volatile byte[] udpKey;
    private volatile boolean udpReady;
    private volatile long udpAckT;

    Link(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.prefs = ctx.getSharedPreferences("foldpen", Context.MODE_PRIVATE);
    }

    boolean connected() { return out != null; }

    void setPairCode(String code) { pairCode = code; needPair = false; }

    void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("foldpen");
            lock.acquire();
            // Wi-Fi power save let the radio doze between packets: samples
            // queued and arrived in bursts with stalls of up to ~300 ms.
            // Held only while the app is in the foreground (start/stop).
            lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "foldpen");
            lowLatency.acquire();
        } catch (Exception ignored) { }
        connector = new Thread(this::connectLoop, "foldpen-connect");
        beacon = new Thread(this::beaconLoop, "foldpen-beacon");
        sender = new Thread(this::sendLoop, "foldpen-send");
        sender.setPriority(Thread.MAX_PRIORITY);
        connector.start();
        beacon.start();
        sender.start();
    }

    void stop() {
        running = false;
        closeSocket();
        DatagramSocket b = beaconSocket;
        if (b != null) b.close();
        for (Thread t : new Thread[]{connector, beacon, sender}) if (t != null) t.interrupt();
        if (lock != null && lock.isHeld()) lock.release();
        if (lowLatency != null && lowLatency.isHeld()) lowLatency.release();
    }

    /** Drop the current connection; the connector immediately tries again. */
    void reconnect() { closeSocket(); }

    // ---------------------------------------------------------------- sending

    void send(int type, byte[] payload) {
        if (out == null) return;   // never replay stale input after a reconnect
        if (type == T_PEN && udpLive()) { penQueue.offer(payload); return; }
        queue.offer(frame(type, payload));
    }

    private boolean udpLive() {
        return udpReady && udp != null && System.currentTimeMillis() - udpAckT < UDP_STALE_MS;
    }

    void sendJson(int type, JSONObject o) { send(type, o.toString().getBytes(StandardCharsets.UTF_8)); }

    JSONObject viewJson() {
        JSONObject v = new JSONObject();
        try { v.put("w", viewW).put("h", viewH); } catch (Exception ignored) { }
        return v;
    }

    static byte[] frame(int type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(5 + payload.length);
        b.put((byte) type).putInt(payload.length).put(payload);
        return b.array();
    }

    private void sendLoop() {
        while (running) {
            byte[] m;
            try { m = queue.take(); } catch (InterruptedException e) { continue; }
            OutputStream o = out;
            if (o == null) continue;
            try { o.write(m); } catch (IOException e) { Log.i("foldpen", "send " + e); closeSocket(); }
        }
    }

    // ---------------------------------------------------------------- connecting

    private void connectLoop() {
        while (running) {
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            String manual = prefs.getString("host", null);
            if (manual != null && !manual.isEmpty()) hosts.add(manual);
            hosts.add("127.0.0.1");
            if (beaconHost != null) hosts.add(beaconHost);
            for (String h : hosts) {
                if (!running) return;
                int port = h.equals(beaconHost) ? beaconPort : prefs.getInt("port", PORT);
                try { session(h, port); } catch (IOException e) { Log.i("foldpen", h + ":" + port + " " + e); }
                closeSocket();
            }
            sleep(needPair ? 2500 : 800);
        }
    }

    private void session(String host, int port) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), 600);
        socket = s;
        OutputStream o = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        JSONObject hello = new JSONObject();
        try {
            String token = prefs.getString("token", null);
            if (token != null) hello.put("token", token);
            String code = pairCode;
            if (code != null) hello.put("code", code);
            hello.put("w", viewW).put("h", viewH).put("model", Build.MODEL);
        } catch (Exception ignored) { }
        o.write(frame(T_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8)));

        s.setSoTimeout(4000);
        int type = in.readUnsignedByte();
        byte[] first = new byte[in.readInt()];
        in.readFully(first);
        JSONObject st;
        try { st = new JSONObject(new String(first, StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException(e); }
        if (type != T_STATE || st.has("error")) {
            pairCode = null;
            needPair = prefs.getString("token", null) == null || "pair".equals(st.optString("error"));
            if (needPair) listener.onPairNeeded();
            throw new IOException("refused");
        }
        String token = st.optString("token", "");
        if (!token.isEmpty()) prefs.edit().putString("token", token).apply();
        pairCode = null;
        needPair = false;
        s.setSoTimeout(0);
        queue.clear();
        out = o;
        // the view may have been measured after HELLO went out
        if (viewW > 0) sendJson(T_VIEW, viewJson());
        Log.i("foldpen", "connected " + host + ":" + port);
        String key = st.optString("udp", "");
        if (!key.isEmpty() && !host.equals("127.0.0.1")) startUdp(host, port, key);
        listener.onConnected(true);
        listener.onState(st);
        try {
            while (running) {
                type = in.readUnsignedByte();
                int n = in.readInt();
                if (n < 0 || n > (32 << 20)) throw new IOException("bad length");
                byte[] p = new byte[n];
                in.readFully(p);
                if (type == T_FRAME) listener.onFrame(p);
                else if (type == T_STATE) {
                    try { listener.onState(new JSONObject(new String(p, StandardCharsets.UTF_8))); }
                    catch (Exception ignored) { }
                }
            }
        } catch (IOException e) {
            Log.i("foldpen", "session ended: " + e + " running=" + running);
            throw e;
        } finally {
            stopUdp();
            boolean was = out != null;
            out = null;
            if (was) listener.onConnected(false);
        }
    }

    // ---------------------------------------------------------------- UDP

    private void startUdp(String host, int port, String keyHex) {
        try {
            byte[] key = new byte[8];
            for (int i = 0; i < 8; i++) key[i] = (byte) Integer.parseInt(keyHex.substring(2 * i, 2 * i + 2), 16);
            DatagramSocket ds = new DatagramSocket();
            ds.connect(new InetSocketAddress(host, port));
            udpKey = key;
            udpReady = false;
            penQueue.clear();
            udp = ds;
            Thread rx = new Thread(() -> udpReceive(ds, key), "foldpen-udp-rx");
            Thread tx = new Thread(() -> udpSend(ds, key), "foldpen-udp-tx");
            tx.setPriority(Thread.MAX_PRIORITY);
            rx.start();
            tx.start();
            // the daemon writes first, to this port: our replies are then an
            // established flow through the PC's firewall
            JSONObject m = new JSONObject().put("cmd", "udp").put("port", ds.getLocalPort());
            queue.offer(frame(T_CMD, m.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.i("foldpen", "udp off: " + e);
        }
    }

    private void stopUdp() {
        udpReady = false;
        DatagramSocket ds = udp;
        udp = null;
        if (ds != null) ds.close();   // ends both threads
        penQueue.clear();
    }

    /** The daemon's keepalives; heard = 1 once our packets reach it. */
    private void udpReceive(DatagramSocket ds, byte[] key) {
        byte[] buf = new byte[32];
        while (udp == ds) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                if (p.getLength() < 10 || buf[0] != 'A') continue;
                boolean ok = true;
                for (int i = 0; i < 8; i++) ok &= buf[1 + i] == key[i];
                if (!ok) continue;
                udpAckT = System.currentTimeMillis();
                if (buf[9] == 1 && !udpReady) { udpReady = true; Log.i("foldpen", "pen over UDP"); }
            } catch (IOException e) {
                if (udp != ds) return;
            }
        }
    }

    private void udpSend(DatagramSocket ds, byte[] key) {
        ArrayDeque<byte[]> hist = new ArrayDeque<>();   // seq:u32 + pen[25]
        int seq = 0, repeats = 0;
        byte[] last = null;
        long lastProbe = 0;
        while (udp == ds) {
            try {
                long wait = !udpReady ? UDP_PROBE_MS : repeats > 0 ? UDP_REPEAT_MS : 1000;
                byte[] pen = penQueue.poll(wait, TimeUnit.MILLISECONDS);
                if (pen != null) {
                    ByteBuffer s = ByteBuffer.allocate(29);
                    s.putInt(++seq).put(pen);
                    hist.addLast(s.array());
                    while (hist.size() > UDP_HISTORY) hist.removeFirst();
                    ByteBuffer d = ByteBuffer.allocate(10 + 29 * hist.size());
                    d.put((byte) 'P').put(key).put((byte) hist.size());
                    for (byte[] h : hist) d.put(h);
                    last = d.array();
                    ds.send(new DatagramPacket(last, last.length));
                    repeats = UDP_REPEATS;
                } else if (repeats > 0 && last != null) {
                    ds.send(new DatagramPacket(last, last.length));   // a lift must not be lost
                    repeats--;
                }
                long now = System.currentTimeMillis();
                if (!udpReady && now - lastProbe >= UDP_PROBE_MS) {
                    lastProbe = now;
                    byte[] h = ByteBuffer.allocate(9).put((byte) 'H').put(key).array();
                    ds.send(new DatagramPacket(h, h.length));
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                if (udp != ds) return;
            }
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private void beaconLoop() {
        try {
            DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(BEACON_PORT));
            beaconSocket = ds;
            byte[] buf = new byte[64];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                String[] msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim().split(" ");
                if (msg.length == 2 && msg[0].equals("FOLDPEN1")) {
                    try { beaconPort = Integer.parseInt(msg[1]); } catch (NumberFormatException e) { continue; }
                    beaconHost = p.getAddress().getHostAddress();
                }
            }
        } catch (IOException ignored) {
            // port taken or socket closed by stop(): adb reverse / manual host still work
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
