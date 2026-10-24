package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Full-bleed backdrop with an editorial text block (eyebrow, title, meta) on the left.
 *  The backdrop is the rotating wallpaper/photo, or a contextual image (e.g. a trending video). */
final class Hero extends FrameLayout {
    final ImageView back, front;
    final TextView eyebrow, title, meta;
    final LinearLayout block;
    final Handler ui = new Handler(Looper.getMainLooper());
    final List<String> walls = new ArrayList<>();
    int wallIndex, rotateMinutes;
    Bitmap wallBitmap;
    String showing; // path of what is on screen, to skip redundant crossfades
    int W;

    Hero(Context c, int width, int rotateMinutes) {
        super(c);
        W = width; this.rotateMinutes = rotateMinutes;
        setBackgroundColor(Color.BLACK);
        back = img(c); front = img(c); front.setAlpha(0f);
        addView(back, new LayoutParams(-1, -1)); addView(front, new LayoutParams(-1, -1));
        View left = new View(c);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xCC000000, 0x66000000, 0x00000000}));
        addView(left, new LayoutParams((int) (width * 0.62f), -1));
        View bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x40000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, (int) Ui.dp(c, 300), Gravity.BOTTOM));
        View top = new View(c);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000}));
        addView(top, new LayoutParams(-1, (int) Ui.dp(c, 120)));

        block = new LinearLayout(c); block.setOrientation(LinearLayout.VERTICAL);
        eyebrow = Ui.eyebrow(c, Ui.TEXT_2);
        title = Ui.text(c, Ui.TITLE, 600, Ui.TEXT);
        title.setSingleLine(false); title.setMaxLines(2); title.setLineSpacing(0, 1.05f);
        meta = Ui.text(c, Ui.BODY, 400, Ui.TEXT_2);
        block.addView(eyebrow);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = (int) Ui.dp(c, 8);
        block.addView(title, tl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = (int) Ui.dp(c, 10);
        block.addView(meta, ml);
        LayoutParams bl = new LayoutParams((int) (width * 0.5f), -2, Gravity.TOP); bl.leftMargin = (int) Ui.dp(c, 56); bl.topMargin = (int) Ui.dp(c, 118);
        addView(block, bl);
        loadWalls();
    }

    static ImageView img(Context c) { ImageView v = new ImageView(c); v.setScaleType(ImageView.ScaleType.CENTER_CROP); return v; }

    /** "personal" = wallpapers + phone photos; "default" = the built-in gradient. Guests always get the default. */
    static String wallMode(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
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

    /** Wallpapers + photos sent from the phone (companion), sorted, rotated every N minutes. */
    void loadWalls() {
        walls.clear();
        if (wallMode(getContext()).equals("default")) return;
        for (String sub : new String[]{"wallpapers", "photos"}) {
            File dir = getContext().getExternalFilesDir(sub);
            String[] names = dir == null ? null : dir.list();
            if (names == null) continue;
            Arrays.sort(names);
            for (String n : names) if (n.matches("(?i).*\\.(jpe?g|png|webp)")) walls.add(new File(dir, n).getPath());
        }
        wallIndex = getContext().getSharedPreferences("home", Context.MODE_PRIVATE).getInt("wp", 0);
    }

    final Runnable rotate = () -> nextWall();

    void nextWall() {
        ui.removeCallbacks(rotate);
        if (walls.isEmpty()) return;
        wallIndex = (wallIndex + 1) % walls.size();
        getContext().getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("wp", wallIndex).apply();
        showWall(true);
    }

    void showWall(boolean fade) {
        ui.removeCallbacks(rotate);
        if (walls.isEmpty()) {
            wallBitmap = defaultWall(W, getResources().getDisplayMetrics().heightPixels);
            if (contextual == null) crossfade(wallBitmap, "default", fade);
            return;
        }
        ui.postDelayed(rotate, rotateMinutes * 60_000L);
        String path = walls.get(wallIndex % walls.size());
        decode(path, bm -> { wallBitmap = bm; if (contextual == null) crossfade(bm, path, fade); });
    }

    String contextual;

    /** Show a contextual image (null = back to the wallpaper). */
    void context(File f) {
        contextual = f == null ? null : f.getPath();
        if (f == null) { if (wallBitmap != null) crossfade(wallBitmap, "wall", true); return; }
        String path = f.getPath();
        decode(path, bm -> { if (path.equals(contextual)) crossfade(bm, path, true); });
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
        boolean motion = contextual != null && Ui.motion(getContext()) > 0;
        if (!fade || back.getDrawable() == null) { back.setImageBitmap(bm); kenBurns(back, motion); return; }
        front.animate().cancel();
        front.setImageBitmap(bm); front.setAlpha(0f);
        kenBurns(front, motion);
        front.animate().alpha(1).setDuration(Ui.SLOW + 300).withEndAction(() -> {
            back.animate().cancel();
            back.setImageBitmap(bm); back.setScaleX(front.getScaleX()); back.setScaleY(front.getScaleY());
            kenBurns(back, motion);
            front.setAlpha(0f); front.setImageDrawable(null); front.setScaleX(1); front.setScaleY(1);
        }).start();
    }

    /** Slow push-in on contextual images (the "motion" stand-in for trailers); wallpapers stay still. */
    static void kenBurns(ImageView v, boolean on) {
        v.animate().cancel();
        if (!on) { v.setScaleX(1); v.setScaleY(1); return; }
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(16_000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    /** Swap the editorial text with a short fade-rise. */
    void text(String e, String t, String m) {
        if (t.equals(title.getText().toString()) && e.equals(eyebrow.getText().toString())) { meta.setText(m); return; }
        block.animate().cancel();
        block.animate().alpha(0).translationY(Ui.dp(getContext(), 6)).setDuration(Ui.FAST / 2).withEndAction(() -> {
            eyebrow.setText(e); title.setText(t); meta.setText(m);
            block.animate().alpha(1).translationY(0).setDuration(Ui.MED).setInterpolator(Ui.out()).start();
        }).start();
    }
}
