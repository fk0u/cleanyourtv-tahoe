package tv.clean.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Pure data-layer channel logo cache & downloader (~200 px downscaled bitmaps). UI binding lives in [Ui.bindLogo]. */
final class Logos {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(2); // never queued behind Net.bg
    private static final Handler main = new Handler(Looper.getMainLooper());

    static Bitmap cached(String url) {
        return url == null ? null : cache.get(url);
    }

    static void fetchAsync(String url, Consumer<Bitmap> onLoaded) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = cache.get(url);
        if (hit != null) {
            onLoaded.accept(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            cache.put(url, b);
            main.post(() -> onLoaded.accept(b));
        });
    }

    private static Bitmap fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "TahoeTV");
            byte[] data;
            try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[16384]; int n;                     // readAllBytes() needs Android 13; the stick is on 9
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                data = out.toByteArray();
            } finally { c.disconnect(); }
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1; while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 200) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) { return null; }
    }
}
