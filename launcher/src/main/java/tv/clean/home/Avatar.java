package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.widget.ImageView;
import java.io.File;

/** Profile photo from config `avatar_url`, downloaded once and cached; shown as a circle with a thin rim. */
final class Avatar {
    static Bitmap cached;

    static File file(Context c) { return new File(c.getFilesDir(), "avatar.jpg"); }

    /** Calls `done` on the main thread with the photo (cached immediately if present, fetched otherwise). */
    static void load(Context c, java.util.function.Consumer<Bitmap> done) {
        if (cached != null) { done.accept(cached); return; }
        File f = file(c);
        String url = c.getString(R.string.avatar_url);
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        Net.bg.execute(() -> {
            try {
                if (!f.exists() && !url.isEmpty()) Net.download(url, f);
                Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                if (bm == null) return;
                cached = Bitmap.createScaledBitmap(bm, 256, 256, true);
                ui.post(() -> done.accept(cached));
            } catch (Exception ignored) {}
        });
    }

    /** Circular view; falls back to the user icon until the photo arrives. */
    static ImageView view(Context c, float sizeDp) { return view(c, sizeDp, true); }

    static ImageView view(Context c, float sizeDp, boolean photo) {
        ImageView v = new ImageView(c);
        int px = (int) Ui.dp(c, sizeDp);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(px, px));
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageResource(R.drawable.ic_user);
        v.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x33FFFFFF);
        v.setBackground(ring);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(android.view.View w, Outline o) { o.setOval(0, 0, w.getWidth(), w.getHeight()); }
        });
        v.setClipToOutline(true);
        if (photo) load(c, bm -> { v.setImageTintList(null); v.setImageBitmap(bm); });
        return v;
    }
}
