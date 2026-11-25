package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/** Screensaver (this DreamService) = macOS-style lock: photo slideshow, date, big clock, profile photo, "press OK".
 *  At boot the launcher shows Profiles ("Siapa yang menonton?") instead. */
public class Lock extends DreamService {
    LockView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // OK unlocks; other keys just nudge the hint
        setFullscreen(true);
        setScreenBright(false); // low-energy: the system dims the panel while the screensaver runs
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

    static boolean isOk(int k) { return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A; }

    /** Wallpaper/photo slideshow shared by the lock and the profile picker. */
    static class Backdrop extends FrameLayout {
        final Handler ui = new Handler(Looper.getMainLooper());
        final ImageView back, front;
        final List<String> walls = new ArrayList<>();
        int index;

        Backdrop(Context c, int[] scrim) {
            super(c);
            setBackgroundColor(Color.BLACK);
            back = Hero.img(c); front = Hero.img(c); front.setAlpha(0f);
            addView(back, new LayoutParams(-1, -1)); addView(front, new LayoutParams(-1, -1));
            View s = new View(c);
            s.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, scrim));
            addView(s, new LayoutParams(-1, -1));
            for (String sub : new String[]{"wallpapers", "photos"}) { // photos = sent from the phone (Google Photos via its picker)
                File dir = c.getExternalFilesDir(sub);
                String[] names = dir == null ? null : dir.list();
                if (names != null) { Arrays.sort(names); for (String n : names) if (n.matches("(?i).*\\.(jpe?g|png|webp)")) walls.add(new File(dir, n).getPath()); }
            }
            if (Hero.wallMode(c).equals("default")) walls.clear();
            index = c.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("wp", 0);
        }

        long interval = 30_000; int fadeMs = 1500;

        final Runnable slide = () -> advanceSlide();

        void advanceSlide() {
            if (walls.isEmpty()) {
                back.setImageBitmap(Hero.defaultWall(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
                return;
            }
            displayPhoto(walls.get(index++ % walls.size()), back.getDrawable() != null);
            ui.postDelayed(slide, interval);
        }

        void start() { ui.removeCallbacksAndMessages(null); advanceSlide(); }
        void stop() { ui.removeCallbacksAndMessages(null); }

        void displayPhoto(String path, boolean fade) {
            Hero.decoder.execute(() -> {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bm = BitmapFactory.decodeFile(path, o);
                if (bm == null) return;
                ui.post(() -> {
                    if (!fade) { back.setImageBitmap(bm); return; }
                    front.setImageBitmap(bm); front.setAlpha(0f);
                    front.animate().alpha(1).setDuration(fadeMs).withEndAction(() -> { back.setImageBitmap(bm); front.setAlpha(0f); front.setImageDrawable(null); }).start();
                });
            });
        }

        /** Slide away (macOS style), then run `done`. */
        void unlock(Runnable done) {
            stop();
            long d = Ui.dur(getContext(), Ui.SLOW);
            if (d <= 0) { done.run(); return; }
            animate().alpha(0).translationY(-getHeight() * 0.06f).setDuration(d)
                    .setInterpolator(Ui.out()).withEndAction(done).start();
        }
    }

    static class LockView extends Backdrop {
        final TextView date, clock, hint;

        LockView(Context c) {
            // Low-energy look: heavy scrim (dim photo), slow slideshow with long fades.
            super(c, new int[]{0xD9000000, 0x99000000, 0x99000000, 0xD9000000});
            interval = 120_000; fadeMs = 3000;
            float d = Ui.dp(c, 1);
            LinearLayout top = new LinearLayout(c); top.setOrientation(LinearLayout.VERTICAL); top.setGravity(Gravity.CENTER_HORIZONTAL);
            date = Ui.text(c, 22, 500, 0xE6FFFFFF); date.setGravity(Gravity.CENTER);
            clock = Ui.text(c, 112, 600, Color.WHITE);
            clock.setGravity(Gravity.CENTER); clock.setEllipsize(null); // full width + no ellipsis: "00.25" was cut to "00.2"
            date.setAlpha(0.7f); clock.setAlpha(0.8f); // low-energy: dimmer type
            top.addView(date, new LinearLayout.LayoutParams(-1, -2));
            top.addView(clock, new LinearLayout.LayoutParams(-1, -2));
            LayoutParams tl = new LayoutParams(-1, -2, Gravity.TOP); tl.topMargin = (int) (56 * d);
            addView(top, tl);

            LinearLayout user = new LinearLayout(c); user.setOrientation(LinearLayout.VERTICAL); user.setGravity(Gravity.CENTER_HORIZONTAL);
            String name = Cfg.s(c, "user_name", R.string.user_name);
            ImageView photo = Avatar.view(c, 84);
            GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke((int) (2 * d), 0xB3FFFFFF);
            photo.setForeground(ring);
            user.addView(photo);
            TextView who = Ui.text(c, 20, 600, Color.WHITE); who.setText(name.isEmpty() ? "Tahoe TV" : name);
            who.setPadding(0, (int) (12 * d), 0, (int) (8 * d));
            user.addView(who, new LinearLayout.LayoutParams(-2, -2));
            hint = Ui.text(c, 14, 500, 0xE6FFFFFF);
            hint.setText(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(c).getLanguage(), "tv.lock_unlock_hint"));
            hint.setPadding((int) (16 * d), (int) (7 * d), (int) (16 * d), (int) (7 * d));
            hint.setBackground(new GlassDrawable(16 * d, 0x33FFFFFF, d));
            user.addView(hint, new LinearLayout.LayoutParams(-2, -2));
            LayoutParams ul = new LayoutParams(-1, -2, Gravity.BOTTOM); ul.bottomMargin = (int) (52 * d);
            addView(user, ul);
        }

        final Runnable tick = new Runnable() { public void run() {
            tech.kousozo.tahoetv.core.Viewer tv = TvViewer.get(getContext());
            Locale loc = Locale.forLanguageTag(tv.getLanguage());
            TimeZone tz = TimeZone.getTimeZone(tv.getZone().getId());
            Date now = new Date();
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            SimpleDateFormat tf = new SimpleDateFormat(h24 ? "HH.mm" : "h.mm", loc);
            tf.setTimeZone(tz);
            SimpleDateFormat df = new SimpleDateFormat("EEEE, d MMMM", loc);
            df.setTimeZone(tz);
            clock.setText(tf.format(now));
            date.setText(df.format(now));
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        } };

        @Override void start() { super.start(); tick.run(); }

        void nudge() {
            hint.animate().cancel(); hint.setTranslationX(0);
            float n = Ui.dp(getContext(), 8);
            hint.animate().translationX(n).setDuration(60).withEndAction(() -> hint.animate().translationX(-n).setDuration(60)
                    .withEndAction(() -> hint.animate().translationX(0).setDuration(60).start()).start()).start();
        }

        static String initials(String name) {
            StringBuilder sb = new StringBuilder();
            for (String part : name.split("[\\s-]+")) if (!part.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(part.charAt(0)));
            return sb.toString();
        }
    }

    /** Boot screen: "Siapa yang menonton?" with the owner profile (photo, last app), a guest profile and the kids profile. */
    static class Profiles extends Backdrop {
        interface Pick { void pick(String profile); }
        static final String[] KEYS = {"main", Kids.PROFILE, "guest"}; // the Figma order: me, kids, guest
        final LinearLayout[] cards = new LinearLayout[3];
        int sel = 0;
        boolean okDown;
        final Pick onPick;
        final float S;

        Profiles(Context c, String lastApp, android.graphics.drawable.Drawable lastIcon, String current, Pick onPick) {
            super(c, new int[]{0xB3000000, 0x8C000000, 0xB3000000});
            this.onPick = onPick;
            S = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            String lang2 = TvViewer.get(c).getLanguage();
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView title = txt(c, tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_who_title"), 72, 700, 0xFFF7F8FA);
            title.setLetterSpacing(-0.02f); title.setGravity(Gravity.CENTER);
            // a fixed wide box (wrapping to two lines if a language needs it): the column is only as wide as the three cards, which cut the title off
            title.setSingleLine(false); title.setMaxLines(2);
            col.addView(title, new LinearLayout.LayoutParams(Math.round(1500 * S), -2));
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); row.setClipChildren(false);
            String name = Cfg.s(c, "user_name", R.string.user_name);
            String me = name.isEmpty() ? tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_me") : name;
            cards[0] = card(c, initials(me), 0xFF5CD1FF, me, null);
            String kidsLbl = tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_kids");
            cards[1] = card(c, kidsLbl.isEmpty() ? "A" : kidsLbl.substring(0, 1).toUpperCase(), 0xFF4DE599, kidsLbl,
                    tech.kousozo.tahoetv.core.I18n.get(lang2, Kids.hasPin(c) ? "tv.lock_pin_locked" : "tv.lock_kids_badge"));
            cards[2] = card(c, "+", 0xFF9999B2, tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_guest"), null);
            Ui.direction(this);
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(current)) sel = i;
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                cards[i].setClickable(true);
                cards[i].setFocusable(false);
                cards[i].setFocusableInTouchMode(false);
                cards[i].setOnClickListener(v -> { sel = idx; render(); onPick.pick(KEYS[idx]); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) lp.leftMargin = Math.round(56 * S);
                row.addView(cards[i], lp);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = Math.round(118 * S);
            col.addView(row, rl);
            addView(col, new LayoutParams(-2, -2, Gravity.CENTER));
            TextView hint = txt(c, tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.pick_hint"), 19, 500, 0x9EF7F8FA);
            LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = Math.round(130 * S);
            addView(hint, hl);
            render();
        }

        /** Text drawn from the Figma sizes, a touch larger for the sofa. */
        TextView txt(Context c, String s, float sizePx, int wght, int color) {
            TextView t = new TextView(c);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * S * (sizePx >= 40 ? 1.08f : 1.15f));
            t.setTypeface(Ui.font(c, wght)); t.setTextColor(color); t.setText(s);
            t.setSingleLine(); t.setIncludeFontPadding(false);
            return t;
        }

        static String initials(String n) {
            String[] w = n.trim().split("\\s+");
            String a = w.length > 1 ? w[0].substring(0, 1) + w[1].substring(0, 1) : n.trim().substring(0, Math.min(2, n.trim().length()));
            return a.toUpperCase();
        }

        /** Figma profile tile: 200 rounded square in the profile's colour with big initials, name under it, optional caption. */
        LinearLayout card(Context c, String letters, int color, String name, String caption) {
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setClipChildren(false);
            FrameLayout tile = new FrameLayout(c);
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(56 * S); bg.setColor(color);
            tile.setBackground(bg);
            GradientDrawable ring = new GradientDrawable(); ring.setCornerRadius(56 * S); ring.setColor(Color.TRANSPARENT); ring.setStroke(Math.round(6 * S), Color.WHITE);
            tile.setForeground(ring);
            TextView l = txt(c, letters, 72, 700, 0xFF0D0F14); l.setLetterSpacing(-0.02f);
            tile.addView(l, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            card.addView(tile, new LinearLayout.LayoutParams(Math.round(200 * S), Math.round(200 * S)));
            TextView n = txt(c, name, 30, 600, 0xFFF7F8FA);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = Math.round(20 * S);
            card.addView(n, nl);
            TextView cap = txt(c, caption == null ? "" : caption, 19, 500, 0xCCF7F8FA);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.topMargin = Math.round(20 * S);
            cap.setVisibility(caption == null ? View.INVISIBLE : View.VISIBLE); // keeps the three columns level
            card.addView(cap, cl);
            return card;
        }

        void render() {
            long d = Ui.dur(getContext(), Ui.MED);
            int ringColor = Ui.highContrast(getContext()) ? tech.kousozo.tahoetv.core.Brand.HC_FOCUS : Color.WHITE;
            for (int i = 0; i < 3; i++) {
                boolean f = i == sel;
                cards[i].animate().scaleX(f ? 1f : 0.9f).scaleY(f ? 1f : 0.9f).alpha(f ? 1f : 0.6f).setDuration(d).setInterpolator(Ui.out()).start();
                View p = cards[i].getChildAt(0);
                ((GradientDrawable) p.getForeground()).setStroke(Math.round(6 * S), f ? ringColor : 0x00FFFFFF);
            }
            CharSequence name = ((TextView) cards[sel].getChildAt(1)).getText();
            if (name != null) cards[sel].announceForAccessibility(name);
        }

        boolean key(KeyEvent e) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT && sel > 0) { sel--; render(); }
                    if (k == KeyEvent.KEYCODE_DPAD_RIGHT && sel < 2) { sel++; render(); }
                    if (isOk(k)) okDown = true;
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && isOk(k)) {
                boolean fire = !e.isCanceled() && (okDown || e.getDownTime() == e.getEventTime());
                okDown = false;
                if (fire) onPick.pick(KEYS[sel]);
            }
            return true;
        }
    }

}
