package tv.clean.home;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP helper and the one background thread the launcher uses for I/O. */
final class Net {
    static final ExecutorService bg = Executors.newSingleThreadExecutor();

    static String get(String url) throws IOException { return request("GET", url, null, null); }

    static String request(String method, String url, String body, Map<String, String> headers) throws IOException {
        return request(method, url, body, headers, 20000);
    }

    static String request(String method, String url, String body, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(readTimeoutMs); c.setRequestMethod(method);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            if (code >= 400) throw new IOException("HTTP " + code + ": " + sb);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(20000);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { c.disconnect(); }
        if (!tmp.renameTo(to)) throw new IOException("rename failed");
    }
}
