package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone ↔ TV link on the LAN: serves the web client, pairs phones with a 6-digit code shown on the TV,
 *  and carries remote keys, app launches, links, uploads, phone notifications and now-playing over a WebSocket.
 *  Every request except the page itself and /pair needs a token. */
final class Companion extends NanoWSD {
    static final int PORT = 8765;
    static final long MAX_UPLOAD = 25L * 1024 * 1024;

    interface Listener {
        void onKey(String key);
        void onLaunch(String pkg);
        void onUrl(String url);
        void onAsk(String text);
        void onVolume(int dir);
        void onPhoneNotification(String app, String title, String text);
        void onPhoneMedia(JSONObject media); // null = stopped
        void onUploaded(String kind);
        JSONArray apps();
    }

    final Context ctx;
    final Listener l;
    final Handler ui = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;
    final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
    final SecureRandom rnd = new SecureRandom();
    String code;
    NsdManager.RegistrationListener nsdReg;

    Companion(Context c, Listener l) {
        super(PORT);
        ctx = c.getApplicationContext(); this.l = l;
        prefs = c.getSharedPreferences("companion", Context.MODE_PRIVATE);
        newCode();
    }

    void newCode() { code = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000)); }

    void startAll() {
        try { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true); } catch (IOException e) { return; }
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName("Tahoe TV"); si.setServiceType("_tahoetv._tcp"); si.setPort(PORT);
        nsdReg = new NsdManager.RegistrationListener() {
            public void onRegistrationFailed(NsdServiceInfo s, int e) {}
            public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            public void onServiceRegistered(NsdServiceInfo s) {}
            public void onServiceUnregistered(NsdServiceInfo s) {}
        };
        try { ((NsdManager) ctx.getSystemService(Context.NSD_SERVICE)).registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdReg); } catch (Exception ignored) {}
    }

    void stopAll() {
        stop();
        try { ((NsdManager) ctx.getSystemService(Context.NSD_SERVICE)).unregisterService(nsdReg); } catch (Exception ignored) {}
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    String pairUrl() { return "http://" + ip() + ":" + PORT + "/?code=" + code; }

    Set<String> tokens() { return new HashSet<>(prefs.getStringSet("tokens", new HashSet<>())); }
    boolean authed(IHTTPSession s) { String t = s.getParms().get("token"); return t != null && tokens().contains(t); }
    int pairedCount() { return tokens().size(); }
    void forgetAll() { prefs.edit().remove("tokens").apply(); synchronized (sockets) { for (Socket s : sockets) try { s.close(WebSocketFrame.CloseCode.NormalClosure, "unpaired", false); } catch (Exception ignored) {} } }

    /** Send to every connected phone (e.g. media controls back to the phone app). */
    void broadcast(JSONObject msg) {
        synchronized (sockets) { for (Socket s : sockets) try { s.send(msg.toString()); } catch (Exception ignored) {} }
    }

    @Override protected Response serveHttp(IHTTPSession s) {
        String uri = s.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) return asset("web/index.html", "text/html; charset=utf-8");
            if (uri.equals("/manifest.webmanifest")) return asset("web/manifest.webmanifest", "application/manifest+json");
            if (uri.equals("/pair") && s.getMethod() == Method.POST) return pair(s);
            if (!authed(s)) return json(Response.Status.UNAUTHORIZED, new JSONObject().put("error", "pair first"));
            if (uri.equals("/apps")) {
                JSONArray[] out = new JSONArray[1];
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                ui.post(() -> { out[0] = l.apps(); done.countDown(); });
                done.await();
                return json(Response.Status.OK, new JSONObject().put("apps", out[0]));
            }
            if (uri.equals("/upload") && s.getMethod() == Method.POST) return upload(s);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", String.valueOf(e.getMessage()));
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    Response asset(String path, String mime) throws IOException {
        return newChunkedResponse(Response.Status.OK, mime, ctx.getAssets().open(path));
    }

    static Response json(Response.Status st, JSONObject o) {
        Response r = newFixedLengthResponse(st, "application/json", o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    Response pair(IHTTPSession s) throws Exception {
        Map<String, String> files = new HashMap<>();
        s.parseBody(files);
        String body = files.get("postData");
        String given = body == null ? s.getParms().get("code") : new JSONObject(body).optString("code");
        if (!code.equals(given)) return json(Response.Status.FORBIDDEN, new JSONObject().put("error", "kode salah"));
        byte[] b = new byte[24]; rnd.nextBytes(b);
        String token = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        Set<String> t = tokens(); t.add(token);
        prefs.edit().putStringSet("tokens", t).apply();
        newCode(); // single use
        return json(Response.Status.OK, new JSONObject().put("token", token).put("name", "Tahoe TV"));
    }

    /** Raw body upload: /upload?token=…&kind=photo|wallpaper|adzan&name=file.jpg */
    Response upload(IHTTPSession s) throws Exception {
        String kind = s.getParms().getOrDefault("kind", "photo");
        String dirName = kind.equals("wallpaper") ? "wallpapers" : kind.equals("adzan") ? "adzan" : "photos";
        String name = new File(s.getParms().getOrDefault("name", "upload")).getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (kind.equals("adzan")) name = name.toLowerCase(Locale.ROOT).contains("subuh") ? "adzan_subuh.mp3" : "adzan.mp3";
        else if (!name.matches("(?i).*\\.(jpe?g|png|webp)")) return json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "bukan gambar"));
        long len = Long.parseLong(s.getHeaders().getOrDefault("content-length", "0"));
        if (len <= 0 || len > MAX_UPLOAD) return json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "ukuran tidak valid"));
        File dir = ctx.getExternalFilesDir(dirName);
        if (dir == null) throw new IOException("storage unavailable");
        File out = new File(dir, (kind.equals("adzan") ? "" : System.currentTimeMillis() + "_") + name);
        try (InputStream in = s.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[16384]; long left = len; int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { o.write(buf, 0, n); left -= n; }
        }
        ui.post(() -> l.onUploaded(kind));
        return json(Response.Status.OK, new JSONObject().put("saved", out.getName()));
    }

    @Override protected WebSocket openWebSocket(IHTTPSession s) {
        boolean ok = authed(s);
        return new Socket(s, ok);
    }

    final class Socket extends WebSocket {
        final boolean ok;
        Socket(IHTTPSession s, boolean ok) { super(s); this.ok = ok; }

        @Override protected void onOpen() {
            if (!ok) { try { close(WebSocketFrame.CloseCode.PolicyViolation, "pair first", false); } catch (IOException ignored) {} return; }
            sockets.add(this);
        }
        @Override protected void onClose(WebSocketFrame.CloseCode c, String r, boolean byRemote) { sockets.remove(this); }
        @Override protected void onPong(WebSocketFrame f) {}
        @Override protected void onException(IOException e) { sockets.remove(this); }

        @Override protected void onMessage(WebSocketFrame f) {
            if (!ok) return;
            try {
                JSONObject m = new JSONObject(f.getTextPayload());
                String t = m.optString("t");
                ui.post(() -> {
                    switch (t) {
                        case "key": l.onKey(m.optString("k")); break;
                        case "launch": l.onLaunch(m.optString("pkg")); break;
                        case "url": l.onUrl(m.optString("u")); break;
                        case "ask": l.onAsk(m.optString("q")); break;
                        case "volume": l.onVolume(m.optInt("d")); break;
                        case "notif": l.onPhoneNotification(m.optString("app"), m.optString("title"), m.optString("text")); break;
                        case "media": l.onPhoneMedia(m.optBoolean("stopped") ? null : m); break;
                    }
                });
            } catch (Exception ignored) {}
        }
    }
}
