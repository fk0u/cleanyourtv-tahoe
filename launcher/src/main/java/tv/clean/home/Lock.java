package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/** macOS-style lock screen: wallpaper slideshow, date, big clock, avatar + name, "press OK".
 *  Runs as the system screensaver (this DreamService) and over the launcher at boot (Lock.View). */
public class Lock extends DreamService {
    LockView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // we take the keys: OK unlocks, everything else just nudges the hint
        setFullscreen(true);
        view = new LockView(this);
        setContentView(view);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); view.start(); }
    @Override public void onDreamingStopped() { view.stop(); super.onDreamingStopped(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (isOk(e.getKeyCode())) {
            if (e.getAction() == KeyEvent.ACTION_UP) view.unlock(this::finish);
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) view.nudge();
        return true;
    }

    static boolean isOk(int k) { return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER; }

    /** The lock screen itself; shared by the screensaver and the launcher's boot lock. */
    static class LockView extends FrameLayout {
        final Handler ui = new Handler(Looper.getMainLooper());
        final ImageView back, front;
        final TextView date, clock, hint;
        final LinearLayout user;
        final List<String> walls = new ArrayList<>();
        int index;

        LockView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            back = image(c); front = image(c); front.setAlpha(0f);
            addView(back, new LayoutParams(-1, -1)); addView(front, new LayoutParams(-1, -1));
            View scrim = new View(c);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x66000000, 0x1A000000, 0x1A000000, 0x8C000000}));
            addView(scrim, new LayoutParams(-1, -1));

            LinearLayout top = new LinearLayout(c); top.setOrientation(LinearLayout.VERTICAL); top.setGravity(Gravity.CENTER_HORIZONTAL);
            date = text(c, 22, 500, 0xE6FFFFFF);
            clock = text(c, 112, 600, Color.WHITE);
            clock.setIncludeFontPadding(false);
            top.addView(date); top.addView(clock);
            LayoutParams tl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); tl.topMargin = dp(56);
            addView(top, tl);

            user = new LinearLayout(c); user.setOrientation(LinearLayout.VERTICAL); user.setGravity(Gravity.CENTER_HORIZONTAL);
            String name = c.getString(R.string.user_name);
            TextView avatar = text(c, 30, 500, Color.WHITE);
            avatar.setText(initials(name)); avatar.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL); circle.setColor(0x59FFFFFF); circle.setStroke(dp(1), 0x80FFFFFF);
            avatar.setBackground(circle);
            user.addView(avatar, new LinearLayout.LayoutParams(dp(84), dp(84)));
            TextView who = text(c, 20, 600, Color.WHITE); who.setText(name.isEmpty() ? "Beranda" : name);
            who.setPadding(0, dp(12), 0, dp(6));
            user.addView(who, new LinearLayout.LayoutParams(-2, -2)); // wrap: neither stretched nor clipped to the avatar
            hint = text(c, 15, 400, 0xCCFFFFFF);
            hint.setText("Tekan OK untuk membuka");
            hint.setPadding(dp(16), dp(6), dp(16), dp(6));
            hint.setBackground(Home.glassOf(c, dp(16), 0x33FFFFFF));
            user.addView(hint, new LinearLayout.LayoutParams(-2, -2));
            LayoutParams ul = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); ul.bottomMargin = dp(56);
            addView(user, ul);

            File dir = c.getExternalFilesDir("wallpapers");
            String[] names = dir == null ? null : dir.list();
            if (names != null) { Arrays.sort(names); for (String n : names) if (n.matches("(?i).*\\.(jpe?g|png|webp)")) walls.add(new File(dir, n).getPath()); }
            index = c.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("wp", 0);
        }

        int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

        static String initials(String name) {
            StringBuilder sb = new StringBuilder();
            for (String part : name.split("[\\s-]+")) if (!part.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(part.charAt(0)));
            return sb.toString();
        }

        static ImageView image(Context c) { ImageView v = new ImageView(c); v.setScaleType(ImageView.ScaleType.CENTER_CROP); return v; }

        static TextView text(Context c, float sp, int wght, int color) {
            TextView t = new TextView(c);
            t.setTextSize(sp); t.setTypeface(Home.font(c, wght)); t.setTextColor(color); t.setSingleLine();
            t.setShadowLayer(16, 0, 2, 0x66000000);
            return t;
        }

        final Runnable tick = new Runnable() { public void run() {
            Date now = new Date();
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            clock.setText(new SimpleDateFormat(h24 ? "HH.mm" : "h.mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        } };

        final Runnable slide = new Runnable() { public void run() {
            if (walls.isEmpty()) return;
            show(walls.get(index % walls.size()), back.getDrawable() != null);
            index++;
            ui.postDelayed(this, 30_000); // slideshow while locked
        } };

        void start() { ui.removeCallbacksAndMessages(null); tick.run(); slide.run(); }
        void stop() { ui.removeCallbacksAndMessages(null); }

        void show(String path, boolean fade) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return;
            if (!fade) { back.setImageBitmap(bm); return; }
            front.setImageBitmap(bm); front.setAlpha(0f);
            front.animate().alpha(1).setDuration(1500).withEndAction(() -> {
                back.setImageBitmap(bm); front.setAlpha(0f); front.setImageDrawable(null);
            }).start();
        }

        void nudge() {
            hint.animate().cancel();
            hint.setTranslationX(0);
            hint.animate().translationX(dp(8)).setDuration(60).withEndAction(() ->
                    hint.animate().translationX(-dp(8)).setDuration(60).withEndAction(() ->
                            hint.animate().translationX(0).setDuration(60).start()).start()).start();
        }

        /** Slide the lock away (macOS style), then run `done`. */
        void unlock(Runnable done) {
            stop();
            animate().alpha(0).translationY(-getHeight() * 0.08f).setDuration(380)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .withEndAction(done).start();
        }
    }
}
