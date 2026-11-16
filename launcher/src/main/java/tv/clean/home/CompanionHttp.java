package tv.clean.home;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.DiagLog;
import tech.kousozo.tahoetv.core.Protocol;

/** Modular HTTP REST route handler extracted from [Companion] to reduce god-class coupling (#95). */
final class CompanionHttp {
    private CompanionHttp() {}

    static NanoHTTPD.Response serve(Companion c, NanoHTTPD.IHTTPSession s) {
        String uri = s.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) return c.asset("web/index.html", "text/html; charset=utf-8");
            if (uri.equals("/setup") || uri.equals("/setup.html")) return c.asset("web/setup.html", "text/html; charset=utf-8");
            if (uri.equals("/manifest.webmanifest")) return c.asset("web/manifest.webmanifest", "application/manifest+json");
            if (uri.equals("/pair") && s.getMethod() == NanoHTTPD.Method.POST) return c.pair(s);
            if (uri.equals("/info")) {
                return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject()
                        .put("app", "tahoe")
                        .put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                        .put("version", c.appVersion())
                        .put("id", c.deviceId())
                        .put("proto", Protocol.VERSION)
                        .put("roles", "receiver"));
            }
            if (!c.authed(s)) return Companion.json(NanoHTTPD.Response.Status.UNAUTHORIZED, new JSONObject().put("error", "pair first"));
            if (uri.equals("/settings")) return handleSettings(c, s);
            if (uri.equals("/viewer") && s.getMethod() == NanoHTTPD.Method.POST) return handleViewer(c, s);
            if (uri.equals("/kids/report") && s.getMethod() == NanoHTTPD.Method.GET) return Companion.json(NanoHTTPD.Response.Status.OK, Kids.reportJson(c.ctx));
            if (uri.equals("/apps")) return handleApps(c);
            if (uri.equals("/upload") && s.getMethod() == NanoHTTPD.Method.POST) return c.upload(s);
            if (uri.equals("/log")) {
                NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", Diag.report(c.ctx));
                r.addHeader("Cache-Control", "no-store");
                return r;
            }
            if (uri.equals("/kids_pin") && s.getMethod() == NanoHTTPD.Method.POST) return handleKidsPin(c, s);
            if (uri.equals("/devices") && s.getMethod() == NanoHTTPD.Method.GET) return handleDevices(c);
            if (uri.equals("/devices/revoke") && s.getMethod() == NanoHTTPD.Method.POST) return handleRevokeDevice(c, s);
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", String.valueOf(e.getMessage()));
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    private static NanoHTTPD.Response tooLargeResponse() throws Exception {
        NanoHTTPD.Response r = Companion.json(NanoHTTPD.Response.Status.BAD_REQUEST, new JSONObject().put("error", "permintaan terlalu besar"));
        r.closeConnection(true);
        return r;
    }

    private static NanoHTTPD.Response handleSettings(Companion c, NanoHTTPD.IHTTPSession s) throws Exception {
        if (s.getMethod() == NanoHTTPD.Method.GET) {
            JSONObject[] out = new JSONObject[1];
            CountDownLatch done = new CountDownLatch(1);
            c.ui.post(() -> { out[0] = c.l.settings(); done.countDown(); });
            if (!done.await(2, TimeUnit.SECONDS) || out[0] == null) {
                return Companion.json(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, new JSONObject().put("error", "TV busy"));
            }
            return Companion.json(NanoHTTPD.Response.Status.OK, out[0]);
        }
        if (s.getMethod() != NanoHTTPD.Method.POST) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "method not allowed");
        }
        String raw;
        try { raw = Companion.body(s, 16384); } catch (Companion.TooLarge e) { return tooLargeResponse(); }
        JSONObject patch;
        try { patch = raw.isEmpty() ? new JSONObject() : new JSONObject(raw); }
        catch (Exception e) { return Companion.json(NanoHTTPD.Response.Status.BAD_REQUEST, new JSONObject().put("error", "format JSON tidak valid")); }
        JSONObject[] out = new JSONObject[1];
        CountDownLatch done = new CountDownLatch(1);
        c.ui.post(() -> { out[0] = c.l.applySettings(patch); done.countDown(); });
        if (!done.await(3, TimeUnit.SECONDS) || out[0] == null) {
            return Companion.json(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, new JSONObject().put("error", "TV busy"));
        }
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("ok", true).put("settings", out[0]));
    }

    private static NanoHTTPD.Response handleViewer(Companion c, NanoHTTPD.IHTTPSession s) throws Exception {
        String raw;
        try { raw = Companion.body(s, 16384); } catch (Companion.TooLarge e) { return tooLargeResponse(); }
        if (raw.isEmpty()) return Companion.json(NanoHTTPD.Response.Status.BAD_REQUEST, new JSONObject().put("error", "data kosong"));
        CountDownLatch done = new CountDownLatch(1);
        c.ui.post(() -> { Onboarding.fromPhone(c.ctx, raw); done.countDown(); });
        done.await(2, TimeUnit.SECONDS);
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("ok", true));
    }

    private static NanoHTTPD.Response handleApps(Companion c) throws Exception {
        JSONArray[] out = new JSONArray[1];
        CountDownLatch done = new CountDownLatch(1);
        c.ui.post(() -> { out[0] = c.l.apps(); done.countDown(); });
        if (!done.await(2, TimeUnit.SECONDS) || out[0] == null) {
            return Companion.json(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, new JSONObject().put("error", "TV busy"));
        }
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("apps", out[0]));
    }

    private static NanoHTTPD.Response handleKidsPin(Companion c, NanoHTTPD.IHTTPSession s) throws Exception {
        String pin;
        try { pin = new JSONObject(Companion.body(s, 1024)).optString("pin"); }
        catch (Companion.TooLarge e) { return tooLargeResponse(); }
        catch (Exception e) { pin = ""; }
        if (!pin.matches("\\d{4}")) return Companion.json(NanoHTTPD.Response.Status.BAD_REQUEST, new JSONObject().put("error", "PIN harus 4 angka"));
        Kids.setPin(c.ctx, pin);
        DiagLog.add("kids", "PIN Anak diatur ulang dari HP");
        c.ui.post(c.l::onKidsPin);
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("ok", true));
    }

    private static NanoHTTPD.Response handleDevices(Companion c) throws Exception {
        JSONArray list = new JSONArray();
        JSONObject meta = c.tokenMeta();
        for (String tk : c.tokens()) {
            JSONObject m = meta.optJSONObject(tk);
            list.put(new JSONObject()
                    .put("prefix", tk.substring(0, Math.min(6, tk.length())))
                    .put("name", m != null ? m.optString("name", "HP") : "HP")
                    .put("role", m != null ? m.optString("role", "owner") : "owner")
                    .put("ip", m != null ? m.optString("ip", "") : "")
                    .put("pairedAt", m != null ? m.optLong("pairedAt", 0L) : 0L));
        }
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("devices", list));
    }

    private static NanoHTTPD.Response handleRevokeDevice(Companion c, NanoHTTPD.IHTTPSession s) throws Exception {
        String prefix;
        try { prefix = new JSONObject(Companion.body(s, 1024)).optString("prefix", "").trim(); }
        catch (Companion.TooLarge e) { return tooLargeResponse(); }
        catch (Exception e) { prefix = ""; }
        if (prefix.isEmpty()) return Companion.json(NanoHTTPD.Response.Status.BAD_REQUEST, new JSONObject().put("error", "prefix wajib"));
        String target = null;
        for (String tk : c.tokens()) {
            if (tk.startsWith(prefix)) { target = tk; break; }
        }
        if (target != null) c.revoke(target);
        return Companion.json(NanoHTTPD.Response.Status.OK, new JSONObject().put("ok", target != null));
    }
}
