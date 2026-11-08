package tv.clean.companion;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Finds TVs on the phone's Wi-Fi: every host in the /24 (or smaller) subnet is probed on 5555 (network ADB,
 *  needed for setup) and 8765 (Tahoe already installed). ~254 hosts × 2 ports, 48 in parallel, ~3 s. */
final class Scan {
    static final class Found {
        final String ip; String name, version; boolean adb, tahoe;
        Found(String ip) { this.ip = ip; }
        String label() {
            String n = name == null || name.equals(ip) ? "Perangkat" : name.replaceAll("\\.(lan|local|home)$", "");
            return n + " · " + ip;
        }
        String status() {
            String t = "Tahoe " + (version == null ? "" : version);
            return tahoe && adb ? t + " · siap dikelola" : tahoe ? t + " · nyalakan debugging untuk mengelola" : "Debugging aktif · siap dipasang";
        }
    }

    /** Phone's IPv4 and prefix on the active network, e.g. {"192.168.18.20", "24"}. */
    static String[] localAddress(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp != null) for (LinkAddress la : lp.getLinkAddresses())
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress())
                return new String[]{la.getAddress().getHostAddress(), String.valueOf(la.getPrefixLength())};
        return null;
    }

    /** Runs off the main thread; `found` is called for each hit (on the scan thread), `done` at the end. */
    static void run(Context c, Consumer<Found> found, Runnable done) {
        new Thread(() -> {
            String[] me = localAddress(c);
            if (me == null) { done.run(); return; }
            String base = me[0].substring(0, me[0].lastIndexOf('.') + 1);
            ExecutorService pool = Executors.newFixedThreadPool(48);
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                String ip = base + i;
                if (ip.equals(me[0])) continue;
                jobs.add(pool.submit(() -> {
                    Found f = new Found(ip);
                    f.adb = open(ip, 5555, 400);
                    if (open(ip, 8765, 400)) info(f); // confirmed Tahoe TV: real model name + version
                    if (f.adb || f.tahoe) {
                        if (f.name == null) try { f.name = InetAddress.getByName(ip).getCanonicalHostName(); } catch (Exception ignored) {}
                        found.accept(f);
                    }
                }));
            }
            for (Future<?> j : jobs) try { j.get(); } catch (Exception ignored) {}
            pool.shutdown();
            done.run();
        }, "tv-scan").start();
    }

    static void info(Found f) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://" + f.ip + ":8765/info").openConnection();
            c.setConnectTimeout(800); c.setReadTimeout(1500);
            try (java.util.Scanner s = new java.util.Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A")) {
                org.json.JSONObject o = new org.json.JSONObject(s.hasNext() ? s.next() : "{}");
                if ("tahoe".equals(o.optString("app"))) { f.tahoe = true; f.name = o.optString("model"); f.version = o.optString("version"); }
            }
        } catch (Exception ignored) {}
    }

    static boolean open(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), timeoutMs); return true; }
        catch (Exception e) { return false; }
    }
}
