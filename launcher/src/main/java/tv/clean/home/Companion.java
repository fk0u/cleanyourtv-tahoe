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
import tech.kousozo.tahoetv.core.Protocol;

/** Phone ↔ TV link on the LAN: serves the web client, pairs phones with a 6-digit code shown on the TV,
 *  and carries remote keys, app launches, session commands, uploads, phone notifications and now-playing over a WebSocket.
 *  Every request except the page itself, /info, and /pair needs a token. */
final class Companion extends NanoWSD {
    static final int PORT = 8765;
    static final long MAX_UPLOAD = 25L * 1024 * 1024;
    private static final long PING_INTERVAL_MS = 25_000L;
    private static final long SOCKET_TIMEOUT_MS = 75_000L;

    interface Listener {
        void onKey(String key);
        void onLaunch(String pkg);
        void onUrl(String url);
        void onAsk(String text);
        void onVolume(int dir);
        void onPhoneNotification(String app, String title, String text);
        void onPhoneMedia(JSONObject media); // null = stopped
        void onUploaded(String kind);
        void onText(String text);     // typed on the phone → the focused text field on the TV
        void onVolumeSet(int percent);
        void onPower();               // standby ↔ wake
        void onSleep(int minutes);    // sleep timer, 0 = off
        void onKidsPin();             // a paired phone set a new PIN for the kids profile
        void onSessionLoad(String channelId, String url);
        void onSessionControl(String action);
        JSONObject state();           // volume, mute, channel: sent to phones on connect and on change
        JSONArray apps();
    }

    final Context ctx;
    final Listener l;
    final Handler ui = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;
    final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
    final SecureRandom rnd = new SecureRandom();
    /** Pairing code: expires, locks after repeated wrong guesses (see PairGuard). */
    final tech.kousozo.tahoetv.core.PairGuard guard = new tech.kousozo.tahoetv.core.PairGuard();
    NsdManager.RegistrationListener nsdReg;
    private java.util.concurrent.ScheduledExecutorService pingExec;
    private final java.util.concurrent.atomic.AtomicLong serverSeq = new java.util.concurrent.atomic.AtomicLong(0);

    Companion(Context c, Listener l) {
        super(PORT);
        ctx = c.getApplicationContext(); this.l = l;
        prefs = c.getSharedPreferences("companion", Context.MODE_PRIVATE);
    }

    String deviceId() {
        String id = prefs.getString("device_id", null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String appVersion() {
        try {
            return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** A fresh code (OK on the "Hubungkan HP" screen); also lifts a lockout, since only someone at the TV can do it. */
    void newCode() { guard.newCode(); }
    /** The code to show now (renewed after it expires). */
    String code() { return guard.current(); }

    void startAll() {
        try { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true); } catch (IOException e) { return; }
        String id = deviceId();
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName("Tahoe TV (" + id.substring(0, 4).toUpperCase(Locale.ROOT) + ")");
        si.setServiceType("_tahoetv._tcp");
        si.setPort(PORT);
        si.setAttribute("id", id);
        si.setAttribute("proto", String.valueOf(Protocol.VERSION));
        si.setAttribute("ver", appVersion());
        si.setAttribute("roles", "receiver");
        si.setAttribute("caps", "hls,dash,ac3,hevc");
        nsdReg = new NsdManager.RegistrationListener() {
            public void onRegistrationFailed(NsdServiceInfo s, int e) {}
            public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            public void onServiceRegistered(NsdServiceInfo s) {}
            public void onServiceUnregistered(NsdServiceInfo s) {}
        };
        try { ((NsdManager) ctx.getSystemService(Context.NSD_SERVICE)).registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdReg); } catch (Exception ignored) {}
        pingExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        pingExec.scheduleAtFixedRate(this::heartbeat, PING_INTERVAL_MS, PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        String ping = Protocol.encode(new Protocol.Ping(now), "s-" + serverSeq.incrementAndGet(), now, deviceId(), false).toString();
        List<Socket> snap;
        synchronized (sockets) { snap = new ArrayList<>(sockets); }
        for (Socket s : snap) {
            if (now - s.lastSeenMs > SOCKET_TIMEOUT_MS) {
                sockets.remove(s);
                try { s.close(WebSocketFrame.CloseCode.GoingAway, "timeout", false); } catch (Exception ignored) {}
                continue;
            }
            out.execute(() -> { try { s.send(ping); } catch (Exception ignored) {} });
        }
    }

    void stopAll() {
        if (pingExec != null) { pingExec.shutdownNow(); pingExec = null; }
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

    String pairUrl() { return "http://" + ip() + ":" + PORT + "/?code=" + code(); }

    Set<String> tokens() { return new HashSet<>(prefs.getStringSet("tokens", new HashSet<>())); }
    private JSONObject tokenMeta() {
        try { return new JSONObject(prefs.getString("token_meta", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    boolean authed(IHTTPSession s) {
        String auth = s.getHeaders().get("authorization");
        String t = null;
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            t = auth.substring(7).trim();
        }
        if (t == null) t = s.getParms().get("token");
        return t != null && tokens().contains(t);
    }
    int pairedCount() { return tokens().size(); }
    void revoke(String token) {
        Set<String> t = tokens();
        t.remove(token);
        JSONObject meta = tokenMeta();
        meta.remove(token);
        prefs.edit().putStringSet("tokens", t).putString("token_meta", meta.toString()).apply();
    }
    void forgetAll() { prefs.edit().remove("tokens").remove("token_meta").apply(); synchronized (sockets) { for (Socket s : sockets) try { s.close(WebSocketFrame.CloseCode.NormalClosure, "unpaired", false); } catch (Exception ignored) {} } }

    /** Send to every connected phone (e.g. media controls back to the phone app). */
    /** Socket writes off the main thread (Android throws NetworkOnMainThreadException there). */
    final java.util.concurrent.ExecutorService out = java.util.concurrent.Executors.newSingleThreadExecutor();

    void broadcast(JSONObject msg) {
        String m = msg.toString();
        out.execute(() -> { synchronized (sockets) { for (Socket s : sockets) try { s.send(m); } catch (Exception ignored) {} } });
    }

    @Override protected Response serveHttp(IHTTPSession s) {
        String uri = s.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) return asset("web/index.html", "text/html; charset=utf-8");
            if (uri.equals("/manifest.webmanifest")) return asset("web/manifest.webmanifest", "application/manifest+json");
            if (uri.equals("/pair") && s.getMethod() == Method.POST) return pair(s);
            if (uri.equals("/info")) // public: lets the phone's scanner tell a Tahoe TV from any open port
                return json(Response.Status.OK, new JSONObject().put("app", "tahoe").put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                        .put("version", appVersion()).put("id", deviceId()).put("proto", Protocol.VERSION).put("roles", "receiver"));
            if (!authed(s)) return json(Response.Status.UNAUTHORIZED, new JSONObject().put("error", "pair first"));
            if (uri.equals("/apps")) {
                JSONArray[] out = new JSONArray[1];
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                ui.post(() -> { out[0] = l.apps(); done.countDown(); });
                if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS) || out[0] == null)
                    return json(Response.Status.SERVICE_UNAVAILABLE, new JSONObject().put("error", "TV busy"));
                return json(Response.Status.OK, new JSONObject().put("apps", out[0]));
            }
            if (uri.equals("/upload") && s.getMethod() == Method.POST) return upload(s);
            // "Kirim log" on the phone: the TV's diagnostic report (no tokens or keys, see DiagLog)
            if (uri.equals("/log")) {
                Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", Diag.report(ctx));
                r.addHeader("Cache-Control", "no-store"); // device details and stream paths: never cached
                return r;
            }
            // Forgotten kids PIN: a paired phone (it holds this TV's token) sets a new one. The owner's phone is the proof.
            if (uri.equals("/kids_pin") && s.getMethod() == Method.POST) {
                // in the body, not the URL: URLs end up in logs. A missing, oversized or broken body is a bad request.
                String pin;
                try { pin = new JSONObject(body(s, 1024)).optString("pin"); }
                catch (TooLarge e) {
                    Response r = json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "permintaan terlalu besar"));
                    r.closeConnection(true); // its unread bytes must not be taken for the next request
                    return r;
                }
                catch (Exception e) { pin = ""; }
                if (!pin.matches("\\d{4}")) return json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "PIN harus 4 angka"));
                Kids.setPin(ctx, pin);
                tech.kousozo.tahoetv.core.DiagLog.add("kids", "PIN Anak diatur ulang dari HP");
                ui.post(l::onKidsPin);
                return json(Response.Status.OK, new JSONObject().put("ok", true));
            }
            if (uri.equals("/devices") && s.getMethod() == Method.GET) {
                JSONArray list = new JSONArray();
                JSONObject meta = tokenMeta();
                for (String tk : tokens()) {
                    JSONObject m = meta.optJSONObject(tk);
                    list.put(new JSONObject()
                            .put("prefix", tk.substring(0, Math.min(6, tk.length())))
                            .put("name", m != null ? m.optString("name", "HP") : "HP")
                            .put("role", m != null ? m.optString("role", "owner") : "owner")
                            .put("ip", m != null ? m.optString("ip", "") : "")
                            .put("pairedAt", m != null ? m.optLong("pairedAt", 0L) : 0L));
                }
                return json(Response.Status.OK, new JSONObject().put("devices", list));
            }
            if (uri.equals("/devices/revoke") && s.getMethod() == Method.POST) {
                String prefix;
                try { prefix = new JSONObject(body(s, 1024)).optString("prefix", "").trim(); }
                catch (TooLarge e) {
                    Response r = json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "permintaan terlalu besar"));
                    r.closeConnection(true);
                    return r;
                }
                catch (Exception e) { prefix = ""; }
                if (prefix.isEmpty()) return json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "prefix wajib"));
                String target = null;
                for (String tk : tokens()) {
                    if (tk.startsWith(prefix)) { target = tk; break; }
                }
                if (target != null) revoke(target);
                return json(Response.Status.OK, new JSONObject().put("ok", target != null));
            }
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
        String given;
        String deviceName = "HP";
        try {
            String b = body(s, 1024);
            if (b.isEmpty()) {
                given = s.getParms().get("code");
            } else {
                JSONObject jb = new JSONObject(b);
                given = jb.optString("code");
                String dn = jb.optString("deviceName", "").trim();
                if (!dn.isEmpty()) deviceName = dn;
            }
        } catch (TooLarge e) {
            Response r = json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "permintaan terlalu besar"));
            r.closeConnection(true);
            return r;
        } catch (Exception e) {
            given = s.getParms().get("code");
        }
        tech.kousozo.tahoetv.core.PairGuard.Result r = guard.check(given);
        if (r instanceof tech.kousozo.tahoetv.core.PairGuard.Result.Locked) {
            long sec = ((tech.kousozo.tahoetv.core.PairGuard.Result.Locked) r).getSeconds();
            tech.kousozo.tahoetv.core.DiagLog.add("pair", "terlalu banyak kode salah, dikunci " + sec + " dtk");
            return json(Response.Status.TOO_MANY_REQUESTS, new JSONObject().put("error", "Terlalu banyak percobaan. Tunggu " + sec + " detik, atau tekan OK di layar Hubungkan HP untuk kode baru.").put("retry_after", sec));
        }
        if (!(r instanceof tech.kousozo.tahoetv.core.PairGuard.Result.Ok)) return json(Response.Status.FORBIDDEN, new JSONObject().put("error", "kode salah"));
        tech.kousozo.tahoetv.core.DiagLog.add("pair", "HP baru dipasangkan dari " + s.getRemoteIpAddress());
        byte[] b = new byte[24]; rnd.nextBytes(b);
        String token = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        Set<String> t = tokens(); t.add(token);
        JSONObject meta = tokenMeta();
        try {
            meta.put(token, new JSONObject()
                    .put("name", deviceName)
                    .put("role", "owner")
                    .put("ip", String.valueOf(s.getRemoteIpAddress()))
                    .put("pairedAt", System.currentTimeMillis()));
        } catch (Exception ignored) {}
        prefs.edit().putStringSet("tokens", t).putString("token_meta", meta.toString()).apply();
        return json(Response.Status.OK, new JSONObject()
                .put("token", token)
                .put("id", deviceId())
                .put("name", "Tahoe TV")
                .put("role", "owner"));
    }

    static final class TooLarge extends IOException {}

    /** A small request body as text: all of it (Content-Length bytes) or, past [max], TooLarge. No length = empty. */
    static String body(IHTTPSession s, int max) throws IOException {
        long declared;
        try { declared = Long.parseLong(s.getHeaders().getOrDefault("content-length", "0").trim()); } catch (NumberFormatException e) { declared = 0; }
        if (declared > max) throw new TooLarge();
        byte[] b = new byte[(int) Math.max(0, declared)]; int got = 0, n;
        InputStream in = s.getInputStream();
        while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
        return new String(b, 0, got, "UTF-8");
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
        volatile long lastSeenMs = System.currentTimeMillis();
        Socket(IHTTPSession s, boolean ok) { super(s); this.ok = ok; }

        private String welcomePayload() {
            Protocol.Welcome w = new Protocol.Welcome(
                    Protocol.VERSION, deviceId(), "Tahoe TV", appVersion(),
                    Arrays.asList("hls", "dash", "ac3", "hevc"));
            return Protocol.encode(w, "s-" + serverSeq.incrementAndGet(), System.currentTimeMillis(), deviceId(), false).toString();
        }

        private void sendAsync(String payload) {
            out.execute(() -> { try { send(payload); } catch (Exception ignored) {} });
        }

        @Override protected void onOpen() {
            if (!ok) { try { close(WebSocketFrame.CloseCode.PolicyViolation, "pair first", false); } catch (IOException ignored) {} return; }
            lastSeenMs = System.currentTimeMillis();
            sockets.add(this);
            sendAsync(welcomePayload());
            ui.post(() -> { String m = l.state().toString(); sendAsync(m); });
        }
        @Override protected void onClose(WebSocketFrame.CloseCode c, String r, boolean byRemote) { sockets.remove(this); }
        @Override protected void onPong(WebSocketFrame f) { lastSeenMs = System.currentTimeMillis(); }
        @Override protected void onException(IOException e) { sockets.remove(this); }

        @Override protected void onMessage(WebSocketFrame f) {
            if (!ok) return;
            lastSeenMs = System.currentTimeMillis();
            Protocol.Decoded dec = Protocol.decode(f.getTextPayload());
            if (dec == null) {
                sendAsync(Protocol.error("", "bad_json", "unrecognized message", "s-" + serverSeq.incrementAndGet()).toString());
                return;
            }
            Protocol.Envelope env = dec.getEnvelope();
            String msgId = env.getId();
            if (!Protocol.isVersionSupported(env.getV())) {
                sendAsync(Protocol.error(msgId, "unsupported_version", "please update", "s-" + serverSeq.incrementAndGet()).toString());
                try { close(WebSocketFrame.CloseCode.PolicyViolation, "please update", false); } catch (Exception ignored) {}
                return;
            }
            Protocol.Message msg = dec.getMessage();
            if (msg instanceof Protocol.Hello) {
                Protocol.Hello hello = (Protocol.Hello) msg;
                if (!Protocol.isVersionSupported(hello.getProto())) {
                    sendAsync(Protocol.error(msgId, "unsupported_version", "please update", "s-" + serverSeq.incrementAndGet()).toString());
                    try { close(WebSocketFrame.CloseCode.PolicyViolation, "please update", false); } catch (Exception ignored) {}
                    return;
                }
                sendAsync(welcomePayload());
                if (!msgId.isEmpty()) sendAsync(Protocol.ack(msgId, 0L, "s-" + serverSeq.incrementAndGet()).toString());
                return;
            }
            if (msg instanceof Protocol.Ping) {
                sendAsync(Protocol.encode(new Protocol.Pong(((Protocol.Ping) msg).getNonce()),
                        "s-" + serverSeq.incrementAndGet(), System.currentTimeMillis(), deviceId(), false).toString());
                return;
            }
            if (msg instanceof Protocol.Pong || msg instanceof Protocol.Ack) return;
            if (msg instanceof Protocol.Unknown) {
                sendAsync(Protocol.error(msgId, "unknown_type", "unsupported type: " + ((Protocol.Unknown) msg).getRawType(),
                        "s-" + serverSeq.incrementAndGet()).toString());
                return;
            }
            ui.post(() -> {
                if (msg instanceof Protocol.Key) l.onKey(((Protocol.Key) msg).getKey());
                else if (msg instanceof Protocol.ViewerSync) Onboarding.fromPhone(ctx, ((Protocol.ViewerSync) msg).getJson());
                else if (msg instanceof Protocol.ViewerPatch) Onboarding.fromPhone(ctx, ((Protocol.ViewerPatch) msg).getPatch().toString());
                else if (msg instanceof Protocol.Launch) l.onLaunch(((Protocol.Launch) msg).getPkg());
                else if (msg instanceof Protocol.OpenUrl) l.onUrl(((Protocol.OpenUrl) msg).getUrl());
                else if (msg instanceof Protocol.Ask) l.onAsk(((Protocol.Ask) msg).getQuery());
                else if (msg instanceof Protocol.VolumeDelta) l.onVolume(((Protocol.VolumeDelta) msg).getDelta());
                else if (msg instanceof Protocol.VolumeSet) l.onVolumeSet(((Protocol.VolumeSet) msg).getPercent());
                else if (msg instanceof Protocol.TextInput) l.onText(((Protocol.TextInput) msg).getText());
                else if (msg == Protocol.Power.INSTANCE) l.onPower();
                else if (msg instanceof Protocol.Sleep) l.onSleep(((Protocol.Sleep) msg).getMinutes());
                else if (msg instanceof Protocol.PhoneNotification) {
                    Protocol.PhoneNotification n = (Protocol.PhoneNotification) msg;
                    l.onPhoneNotification(n.getApp(), n.getTitle(), n.getText());
                } else if (msg instanceof Protocol.PhoneMedia) {
                    Protocol.PhoneMedia pm = (Protocol.PhoneMedia) msg;
                    l.onPhoneMedia(pm.getStopped() ? null : pm.getRaw());
                } else if (msg instanceof Protocol.SessionLoad) {
                    Protocol.SessionLoad sl = (Protocol.SessionLoad) msg;
                    l.onSessionLoad(sl.getChannelId(), sl.getUrl());
                } else if (msg instanceof Protocol.SessionControl) {
                    l.onSessionControl(((Protocol.SessionControl) msg).getAction());
                }
                if (!msgId.isEmpty()) {
                    long rev = l.state().optLong("rev", 0L);
                    sendAsync(Protocol.ack(msgId, rev, "s-" + serverSeq.incrementAndGet()).toString());
                }
            });
        }
    }
}
