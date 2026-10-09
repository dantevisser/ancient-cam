package za.web.visser.ancientcam;

import android.net.Uri;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds the camera even when its DHCP address changes.
 *
 * The RTSP URL in secrets.xml carries the camera's last-known IP. When that IP still works we use
 * it directly (fast path). When it stops working, scan() probes the whole /24 for hosts with the
 * RTSP port open and feeds them back as candidates; the reconnect loop then tries each until one
 * plays. The host that actually renders frames gets locked in, so a moved camera self-heals in a
 * few reconnect cycles with no app rebuild and no router config.
 */
final class CameraLocator {

    private static final String TAG = "ancient-cam";
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int SCAN_THREADS = 32;
    private static final int FAILS_BEFORE_SCAN = 2;

    private final String userInfo;        // "user:pass"
    private final int port;
    private final String path;            // "/stream2"
    private final String configuredHost;  // from secrets.xml

    private volatile String goodHost;                    // last host that rendered frames
    private volatile boolean scanning;
    private int failsSinceGood;
    private int cursor;
    private final List<String> candidates = new CopyOnWriteArrayList<>();

    CameraLocator(String rtspUrl) {
        Uri u = Uri.parse(rtspUrl);
        this.userInfo = u.getUserInfo();                 // may be null
        this.port = u.getPort() == -1 ? 554 : u.getPort();
        String p = u.getPath();
        this.path = (p == null || p.isEmpty()) ? "/stream2" : p;
        this.configuredHost = u.getHost();
        candidates.add(configuredHost);
    }

    /** The host to try on this (re)connect. */
    synchronized String pickHost() {
        if (goodHost != null) return goodHost;
        if (candidates.isEmpty()) candidates.add(configuredHost);
        String h = candidates.get(cursor % candidates.size());
        cursor++;
        return h;
    }

    String urlFor(String host) {
        return "rtsp://" + (userInfo != null ? userInfo + "@" : "") + host + ":" + port + path;
    }

    /** Frames are rendering from this host — it is the camera. Lock it. */
    void onFrames(String host) {
        goodHost = host;
        failsSinceGood = 0;
    }

    /** A connect/stall failure. After a couple, forget the locked host and rescan the LAN. */
    void onFailure() {
        failsSinceGood++;
        if (failsSinceGood >= FAILS_BEFORE_SCAN) {
            goodHost = null;
            startScan();
        }
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    scan();
                } catch (Throwable e) {
                    Log.w(TAG, "scan error: " + e);
                } finally {
                    scanning = false;
                }
            }
        }, "cam-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Probe every host on our /24 for the RTSP port; rebuild the candidate list. */
    private void scan() {
        String base = localSlash24Base();
        if (base == null) { Log.w(TAG, "scan: no local /24"); return; }
        Log.i(TAG, "scanning " + base + "0/24 for RTSP:" + port);

        final List<String> open = Collections.synchronizedList(new ArrayList<String>());
        final int[] next = {1};
        Thread[] workers = new Thread[SCAN_THREADS];
        for (int w = 0; w < SCAN_THREADS; w++) {
            workers[w] = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        int i;
                        synchronized (next) { i = next[0]++; }
                        if (i > 254) return;
                        String host = base + i;
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                            open.add(host);
                        } catch (Exception ignored) {
                        } finally {
                            try { s.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            });
            workers[w].start();
        }
        for (Thread worker : workers) {
            try { worker.join(); } catch (InterruptedException ignored) {}
        }

        // Rebuild candidates: configured host first (if still open), then the rest.
        List<String> fresh = new ArrayList<>();
        if (open.contains(configuredHost)) fresh.add(configuredHost);
        for (String h : open) if (!fresh.contains(h)) fresh.add(h);
        if (fresh.isEmpty()) fresh.add(configuredHost);

        synchronized (this) {
            candidates.clear();
            candidates.addAll(fresh);
            cursor = 0;
        }
        Log.i(TAG, "scan found RTSP hosts: " + fresh);
    }

    /** Our own IPv4 /24 prefix, e.g. "192.168.68." — null if we can't find a site-local IPv4. */
    private String localSlash24Base() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isSiteLocalAddress() && a.getAddress().length == 4) {
                        String ip = a.getHostAddress();
                        int dot = ip.lastIndexOf('.');
                        if (dot > 0) return ip.substring(0, dot + 1);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localSlash24Base: " + e);
        }
        return null;
    }
}
