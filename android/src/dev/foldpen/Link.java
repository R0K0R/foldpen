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
import java.util.LinkedHashSet;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TCP link to the foldpen daemon. Input goes out through a dedicated sender
 * thread (no Nagle, one write per message); frames and state come back on the
 * reader. Hosts tried, in order: a host set over adb, 127.0.0.1 (adb reverse
 * over USB), and whatever PC last sent a LAN beacon.
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
        queue.offer(frame(type, payload));
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
            boolean was = out != null;
            out = null;
            if (was) listener.onConnected(false);
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
