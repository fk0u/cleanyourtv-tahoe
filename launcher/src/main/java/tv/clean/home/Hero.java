package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.*;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.M3u;
import tech.kousozo.tahoetv.core.WallpaperProvider;

/** Full-bleed backdrop with an editorial text block (eyebrow, title, meta) on the left.
 *  The backdrop is the rotating wallpaper/photo (personal uploads or free API photos), or a contextual image. */
final class Hero extends FrameLayout {
    final ImageView back, front;
    final TextureView previewView;
    final TextView previewBadge;
    final LivePreview livePreview;
    final TextView eyebrow, title, meta, attribution;
    final LinearLayout block;
    final List<View> scrims = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final List<String> walls = new ArrayList<>();
    final Map<String, String> attributions = new HashMap<>();
    int wallIndex, rotateMinutes;
    Bitmap wallBitmap;
    String showing; // path of what is on screen, to skip redundant crossfades
    String currentAttr = "";
    int W;
    private long lastApiFetchMs = 0L;

    Hero(Context c, int width, int rotateMinutes) {
        super(c);
        W = width;
        int customMin = c.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("hero_rotate_min", 0);
        this.rotateMinutes = Math.max(1, customMin > 0 ? customMin : rotateMinutes);
        setBackgroundColor(Color.BLACK);
        back = img(c); front = img(c); front.setAlpha(0f);
        addView(back, new LayoutParams(-1, -1)); addView(front, new LayoutParams(-1, -1));
        previewView = new TextureView(c);
        previewView.setAlpha(0f);
        addView(previewView, new LayoutParams(-1, -1));
        View left = new View(c);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x99000000, 0x00000000}));
        addView(left, new LayoutParams((int) (width * 0.62f), -1)); scrims.add(left);
        View bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x40000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, (int) Ui.dp(c, 300), Gravity.BOTTOM)); scrims.add(bottom);
        View top = new View(c);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000}));
        addView(top, new LayoutParams(-1, (int) Ui.dp(c, 120))); scrims.add(top);

        previewBadge = Ui.text(c, 11, 700, Ui.TEXT);
        previewBadge.setText("● " + I18n.get(TvViewer.get(c).getLanguage(), "tv.live_preview"));
        previewBadge.setPadding((int) Ui.dp(c, 10), (int) Ui.dp(c, 5), (int) Ui.dp(c, 10), (int) Ui.dp(c, 5));
        previewBadge.setBackground(new GlassDrawable(Ui.dp(c, 14), 0xB307080D, Ui.dp(c, 1)));
        previewBadge.setAlpha(0f); previewBadge.setVisibility(GONE); // the "Live preview" label is gone for good
        LayoutParams pbl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pbl.rightMargin = (int) Ui.dp(c, Ui.SAFE_SIDE);
        pbl.topMargin = (int) Ui.dp(c, Ui.SAFE_TOP + 54);
        addView(previewBadge, pbl);
        livePreview = new LivePreview(c, previewView, previewBadge);

        block = new LinearLayout(c); block.setOrientation(LinearLayout.VERTICAL);
        eyebrow = Ui.eyebrow(c, Ui.TEXT_2);
        title = Ui.text(c, Ui.TITLE, 600, Ui.TEXT);
        title.setSingleLine(false); title.setMaxLines(2); title.setLineSpacing(0, 1.05f);
        meta = Ui.text(c, Ui.BODY, 400, Ui.TEXT_2);
        for (TextView t : new TextView[]{eyebrow, title, meta}) t.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xAA000000); // readable over a bright picture
        block.addView(eyebrow);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = (int) Ui.dp(c, 8);
        block.addView(title, tl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = (int) Ui.dp(c, 10);
        block.addView(meta, ml);
        LayoutParams bl = new LayoutParams((int) (width * 0.5f), -2, Gravity.TOP); bl.leftMargin = (int) Ui.dp(c, 56); bl.topMargin = (int) Ui.dp(c, 118);
        addView(block, bl);

        attribution = Ui.text(c, 11, 500, Ui.TEXT_3);
        attribution.setPadding((int) Ui.dp(c, 10), (int) Ui.dp(c, 4), (int) Ui.dp(c, 10), (int) Ui.dp(c, 4));
        attribution.setBackground(new GlassDrawable(Ui.dp(c, 12), 0x44000000, Ui.dp(c, 1)));
        attribution.setVisibility(GONE);
        LayoutParams al = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        al.rightMargin = (int) Ui.dp(c, 56);
        al.bottomMargin = (int) Ui.dp(c, 18);
        addView(attribution, al);

        loadWalls();
        maybeRefreshApiWallpaper(false);
    }

    /** The Figma Home draws its own scrim, so the built-in ones step aside. */
    void setScrims(boolean on) { for (View v : scrims) v.setVisibility(on ? VISIBLE : GONE); }

    static ImageView img(Context c) { ImageView v = new ImageView(c); v.setScaleType(ImageView.ScaleType.CENTER_CROP); return v; }

    /** "personal" = wallpapers + phone photos + free photo API; "default" = the built-in gradient (unless API source is chosen). */
    static String wallMode(Context c) {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        return "guest".equals(p.getString("profile", "main")) ? "default" : p.getString("wall_mode", "personal");
    }

    static Bitmap defaultWall;

    /** Built-in Tahoe-style wallpaper: deep blue/violet light blobs, drawn once at half resolution. */
    static Bitmap defaultWall(int w, int h) {
        if (defaultWall != null) return defaultWall;
        w = Math.max(2, w / 2); h = Math.max(2, h / 2);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFF06070F);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float[][] blobs = {{0.12f, 0.15f, 0.7f, 0x2A5AE6}, {0.9f, 0.2f, 0.6f, 0x7A32DC}, {0.65f, 1.05f, 0.65f, 0xE6468F}, {0.35f, 0.65f, 0.35f, 0x1496C8}};
        for (float[] b : blobs) {
            float r = b[2] * w;
            p.setShader(new RadialGradient(b[0] * w, b[1] * h, r, 0x8C000000 | (int) b[3], 0x00000000 | (int) b[3], Shader.TileMode.CLAMP));
            c.drawCircle(b[0] * w, b[1] * h, r, p);
        }
        return defaultWall = bm;
    }

    private File apiWallpaperDir() {
        return new File(getContext().getFilesDir(), "api_wallpapers");
    }

    /** Wallpapers + phone uploads + free photo API cache (#39), rotated every N minutes. */
    void loadWalls() {
        walls.clear();
        attributions.clear();
        Context ctx = getContext();
        SharedPreferences p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        WallpaperProvider.Source src = WallpaperProvider.Source.fromKey(p.getString("wall_source", "auto"));
        String mode = wallMode(ctx);
        if ("default".equals(mode) && src == WallpaperProvider.Source.GRADIENT) return;
        if ("personal".equals(mode)) {
            for (String sub : new String[]{"wallpapers", "photos"}) {
                File dir = ctx.getExternalFilesDir(sub);
                String[] names = dir == null ? null : dir.list();
                if (names == null) continue;
                Arrays.sort(names);
                for (String n : names) if (n.matches("(?i).*\\.(jpe?g|png|webp)")) walls.add(new File(dir, n).getPath());
            }
        }
        if (src != WallpaperProvider.Source.GRADIENT) {
            File apiDir = apiWallpaperDir();
            Map<String, String> metaMap = WallpaperProvider.loadAttributionMap(apiDir);
            String[] names = apiDir.list();
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (n.matches("(?i).*\\.(jpe?g|png|webp)")) {
                        String full = new File(apiDir, n).getPath();
                        walls.add(full);
                        String attr = metaMap.get(n);
                        if (attr != null && !attr.isEmpty()) attributions.put(full, attr);
                    }
                }
            }
        }
        wallIndex = p.getInt("wp", 0);
    }

    void maybeRefreshApiWallpaper(boolean force) {
        Context ctx = getContext();
        SharedPreferences p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        WallpaperProvider.Source src = WallpaperProvider.Source.fromKey(p.getString("wall_source", "auto"));
        if (src == WallpaperProvider.Source.GRADIENT) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastApiFetchMs < 6 * 3600_000L) return;
        lastApiFetchMs = now;
        WallpaperProvider.Theme theme = WallpaperProvider.Theme.fromKey(p.getString("wall_theme", "nature"));
        boolean kidsProfile = Kids.PROFILE.equals(p.getString("profile", "main"));
        String lang = TvViewer.get(ctx).getLanguage();
        String unsplashKey = Cfg.key("unsplash_key", "");
        String pexelsKey = Cfg.key("pexels_key", "");
        tech.kousozo.tahoetv.core.DeviceProfile.Tier tier = DeviceProfiler.current(ctx).getActiveTier();
        File apiDir = apiWallpaperDir();
        Net.bg.execute(() -> {
            WallpaperProvider.PhotoMeta meta = WallpaperProvider.fetchDailyPhoto(
                    src, theme, unsplashKey, pexelsKey, tier, lang, kidsProfile);
            if (meta == null) return;
            File saved = WallpaperProvider.cachePhoto(apiDir, meta);
            if (saved != null) {
                ui.post(() -> {
                    loadWalls();
                    if (contextual == null && (showing == null || "default".equals(showing) || force)) {
                        showWall(true);
                    }
                });
            }
        });
    }

    void refreshWallpaperSettings() {
        int customMin = getContext().getSharedPreferences("home", Context.MODE_PRIVATE).getInt("hero_rotate_min", 0);
        if (customMin > 0) rotateMinutes = Math.max(1, customMin);
        loadWalls();
        showWall(false);
        maybeRefreshApiWallpaper(true);
    }

    final Runnable rotate = () -> nextWall();

    void nextWall() {
        ui.removeCallbacks(rotate);
        if (walls.isEmpty()) return;
        wallIndex = (wallIndex + 1) % walls.size();
        getContext().getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("wp", wallIndex).apply();
        showWall(true);
    }

    private void updateAttributionView() {
        boolean show = contextual == null && currentAttr != null && !currentAttr.isEmpty();
        if (show) {
            attribution.setText(currentAttr);
            attribution.setVisibility(VISIBLE);
        } else {
            attribution.setVisibility(GONE);
        }
    }

    void showWall(boolean fade) {
        ui.removeCallbacks(rotate);
        if (walls.isEmpty()) {
            currentAttr = "";
            wallBitmap = defaultWall(W, getResources().getDisplayMetrics().heightPixels);
            if (contextual == null) {
                crossfade(wallBitmap, "default", fade);
                updateAttributionView();
            }
            return;
        }
        ui.postDelayed(rotate, rotateMinutes * 60_000L);
        String path = walls.get(wallIndex % walls.size());
        String attr = attributions.getOrDefault(path, "");
        decode(path, bm -> {
            wallBitmap = bm;
            currentAttr = attr;
            if (contextual == null) {
                crossfade(bm, path, fade);
                updateAttributionView();
            }
        });
    }

    String contextual;

    /** Show a contextual image (null = back to the wallpaper). */
    void context(File f) {
        contextual = f == null ? null : f.getPath();
        updateAttributionView();
        if (f == null) { if (wallBitmap != null) crossfade(wallBitmap, "wall", true); return; }
        String path = f.getPath();
        decode(path, bm -> { if (path.equals(contextual)) crossfade(bm, path, true); });
    }

    /** The focused app's artwork as the background: its banner, scaled down then up for a soft blur, darkened a little. */
    void context(android.graphics.drawable.Drawable art, String key) {
        if (art == null) { context(null); return; }
        String k = "app:" + key;
        contextual = k;
        updateAttributionView();
        if (LiveTv.PKG.equals(key)) { sharpTahoeLive(k); return; } // its own artwork is meant to be seen, not blurred
        decoder.execute(() -> {
            tech.kousozo.tahoetv.core.DeviceProfile.Profile prof = DeviceProfiler.current(getContext());
            int sw = prof.getEnableHeroBlur() ? 160 : 240;
            int sh = prof.getEnableHeroBlur() ? 90 : 135;
            Bitmap b = Bitmap.createBitmap(sw, sh, prof.getEnableHeroBlur() ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            art.mutate(); android.graphics.Rect old = art.copyBounds();
            art.setBounds(0, 0, sw, sh); art.draw(c); art.setBounds(old);
            if (!prof.getEnableHeroBlur()) {
                c.drawColor(0x40000000);
                final Bitmap flat = b;
                ui.post(() -> { if (k.equals(contextual)) crossfade(flat, k, prof.getHeroAnimMs() > 0); });
                return;
            }
            for (int w = sw / 2; w >= 20; w /= 2) b = Bitmap.createScaledBitmap(b, w, w * 9 / 16, true);
            int H = getResources().getDisplayMetrics().heightPixels, target = Math.min(W / 2, 1280);
            for (int w = b.getWidth() * 2; w <= target; w *= 2) b = Bitmap.createScaledBitmap(b, w, w * 9 / 16, true);
            Bitmap out = Bitmap.createScaledBitmap(b, target, target * H / Math.max(1, W), true);
            new android.graphics.Canvas(out).drawColor(0x40000000);
            ui.post(() -> { if (k.equals(contextual)) crossfade(out, k, true); });
        });
    }

    /** Tahoe Live's picture, sharp: the artwork (mountains and glass screens) on the right, fading into the launcher's dark on the left
     *  where the hero text sits. Drawn once at 1280x720 in RGB_565, so it costs under 2 MB. */
    private void sharpTahoeLive(String k) {
        decoder.execute(() -> {
            Bitmap art = BitmapFactory.decodeResource(getResources(), R.drawable.tahoe_live_hero);
            if (art == null) return;
            int ow = 1280, oh = 720;
            Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawColor(0xFF08080D);
            int aw = art.getWidth() * oh / art.getHeight();
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(art, null, new android.graphics.Rect(ow - aw, 0, ow, oh), p);
            android.graphics.Paint fade = new android.graphics.Paint();
            fade.setShader(new android.graphics.LinearGradient(ow - aw, 0, ow - aw + aw * 0.55f, 0, 0xFF08080D, 0x0008080D, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(ow - aw, 0, ow, oh, fade);
            c.drawColor(0x26000000);
            art.recycle();
            ui.post(() -> { if (k.equals(contextual)) crossfade(out, k, true); });
        });
    }

    static final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newSingleThreadExecutor(); // never queued behind network I/O

    void decode(String path, java.util.function.Consumer<Bitmap> done) {
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1; while (o.outWidth / (o.inSampleSize * 2) >= W) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false; o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm != null) ui.post(() -> done.accept(bm));
        });
    }

    void crossfade(Bitmap bm, String key, boolean fade) {
        if (key.equals(showing)) return;
        showing = key;
        tech.kousozo.tahoetv.core.DeviceProfile.Profile prof = DeviceProfiler.current(getContext());
        long animMs = prof.getHeroAnimMs();
        boolean motion = animMs > 0 && Ui.motion(getContext()) > 0; // contextual art pushes in fast, wallpapers drift slowly
        boolean slow = contextual == null;
        if (!fade || animMs <= 0 || back.getDrawable() == null) { back.setImageBitmap(bm); kenBurns(back, motion, slow); return; }
        front.animate().cancel();
        front.setImageBitmap(bm); front.setAlpha(0f);
        kenBurns(front, motion, slow);
        front.animate().alpha(1).setDuration(animMs + 240).withEndAction(() -> {
            back.animate().cancel();
            back.setImageBitmap(bm); back.setScaleX(front.getScaleX()); back.setScaleY(front.getScaleY());
            kenBurns(back, motion, slow);
            front.setAlpha(0f); front.setImageDrawable(null); front.setScaleX(1); front.setScaleY(1);
        }).start();
    }

    /** Slow push-in: 16 s on contextual images (the "motion" stand-in for trailers), 40 s ambient drift on wallpapers. */
    static void kenBurns(ImageView v, boolean on, boolean slow) {
        v.animate().cancel();
        if (!on) { v.setScaleX(1); v.setScaleY(1); return; }
        float to = slow ? 1.05f : 1.08f;
        v.animate().scaleX(to).scaleY(to).setDuration(slow ? 40_000 : 16_000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    /** Live preview in the Home TV hero (#181): plays quietly after dwell, disabled on LIGHT tier / DRM / DASH. */
    void previewChannel(M3u.Channel ch) {
        if (livePreview != null) {
            previewBadge.setText("● " + I18n.get(TvViewer.get(getContext()).getLanguage(), "tv.live_preview"));
            livePreview.focus(ch);
        }
    }

    /** Preview the first of [sources] that plays: the channel's own and backups, then the next channels'. */
    void previewChannels(java.util.List<M3u.Channel> sources) {
        Home h = Home.live;
        if (h != null && !h.previewAllowed()) { stopPreview(); return; } // not while onboarding, the profile picker, or another screen is in front
        if (livePreview != null) {
            previewBadge.setText("● " + I18n.get(TvViewer.get(getContext()).getLanguage(), "tv.live_preview"));
            livePreview.focus(sources);
        }
    }

    void stopPreview() {
        if (livePreview != null) livePreview.stop();
    }

    void releasePreview() {
        if (livePreview != null) livePreview.release();
    }

    /** Swap the editorial text with a short fade-rise. */
    void text(String e, String t, String m) {
        if (t.equals(title.getText().toString()) && e.equals(eyebrow.getText().toString())) { meta.setText(m); return; }
        block.animate().cancel();
        long dOut = Ui.dur(getContext(), Ui.FAST / 2);
        long dIn = Ui.dur(getContext(), Ui.MED);
        if (dIn <= 0) {
            eyebrow.setText(e); title.setText(t); meta.setText(m);
            block.setAlpha(1f); block.setTranslationY(0f);
            return;
        }
        block.animate().alpha(0).translationY(Ui.dp(getContext(), 6)).setDuration(dOut).setInterpolator(Ui.out()).withEndAction(() -> {
            eyebrow.setText(e); title.setText(t); meta.setText(m);
            block.animate().alpha(1).translationY(0).setDuration(dIn).setInterpolator(Ui.out()).start();
        }).start();
    }
}
