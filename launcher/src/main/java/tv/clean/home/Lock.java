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
        static final String[] KEYS = {"main", "guest", Kids.PROFILE};
        final LinearLayout[] cards = new LinearLayout[3];
        int sel = 0;
        boolean okDown;
        final Pick onPick;

        Profiles(Context c, String lastApp, android.graphics.drawable.Drawable lastIcon, String current, Pick onPick) {
            super(c, new int[]{0xB3000000, 0x8C000000, 0xB3000000});
            this.onPick = onPick;
            float d = Ui.dp(c, 1);
            boolean compact = c.getResources().getConfiguration().screenWidthDp < 600;
            int av = compact ? 76 : 132;
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
            if (compact) col.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
            String lang2 = TvViewer.get(c).getLanguage();
            TextView title = Ui.text(c, compact ? 24 : Ui.TITLE, 700, Ui.TEXT);
            title.setText(tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_who_title"));
            TextView sub = Ui.text(c, compact ? 13.5f : Ui.BODY, 400, Ui.TEXT_2);
            sub.setText(tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_who_sub"));
            sub.setPadding(0, (int) (10 * d), 0, (int) ((compact ? 24 : 40) * d));
            title.setGravity(Gravity.CENTER); sub.setGravity(Gravity.CENTER);
            col.addView(title); col.addView(sub);
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            String name = Cfg.s(c, "user_name", R.string.user_name);
            cards[0] = card(c, Avatar.view(c, av), name.isEmpty() ? tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_me") : name, lastApp, lastIcon);
            ImageView guest = Avatar.view(c, av, false); // guest never shows the owner's photo
            int gPad = (int) ((compact ? 18 : 34) * d);
            guest.setPadding(gPad, gPad, gPad, gPad);
            cards[1] = card(c, guest, tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_guest"), null, null);
            ImageView kid = new ImageView(c);
            kid.setImageResource(R.drawable.ic_lock); kid.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            kid.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            GradientDrawable kb = new GradientDrawable(); kb.setShape(GradientDrawable.OVAL); kb.setColor(0xFF34C759);
            int kPad = (int) ((compact ? 22 : 40) * d);
            kid.setBackground(kb); kid.setPadding(kPad, kPad, kPad, kPad);
            kid.setLayoutParams(new LinearLayout.LayoutParams((int) (av * d), (int) (av * d)));
            cards[2] = card(c, kid, tech.kousozo.tahoetv.core.I18n.get(lang2, "tv.lock_kids"), null, null);
            ((TextView) cards[2].getChildAt(2)).setText(tech.kousozo.tahoetv.core.I18n.get(lang2, Kids.hasPin(c) ? "tv.lock_pin_locked" : "tv.lock_kids_badge"));
            Ui.direction(this);
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(current)) sel = i;
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                cards[i].setClickable(true);
                cards[i].setFocusable(false);
                cards[i].setFocusableInTouchMode(false);
                cards[i].setOnClickListener(v -> { sel = idx; render(); onPick.pick(KEYS[idx]); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) ((compact ? 104 : 220) * d), -2);
                if (i > 0) lp.leftMargin = (int) ((compact ? 12 : 48) * d);
                row.addView(cards[i], lp);
            }
            col.addView(row);
            addView(col, new LayoutParams(-2, -2, Gravity.CENTER));
            render();
        }

        LinearLayout card(Context c, ImageView photo, String name, String last, android.graphics.drawable.Drawable icon) {
            float d = Ui.dp(c, 1);
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setClipChildren(false);
            GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke((int) (3 * d), Color.WHITE);
            photo.setForeground(ring);
            card.addView(photo);
            TextView n = Ui.text(c, 22, 700, Ui.TEXT); n.setText(name); n.setPadding(0, (int) (16 * d), 0, (int) (10 * d));
            card.addView(n, new LinearLayout.LayoutParams(-2, -2));
            if (last != null) {
                LinearLayout pill = new LinearLayout(c); pill.setGravity(Gravity.CENTER_VERTICAL);
                ImageView ic = new ImageView(c); ic.setImageDrawable(icon); ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Ui.clip(ic, 4 * d);
                pill.addView(ic, new LinearLayout.LayoutParams((int) (40 * d), (int) (24 * d)));
                LinearLayout txt = new LinearLayout(c); txt.setOrientation(LinearLayout.VERTICAL); txt.setPadding((int) (8 * d), 0, 0, 0);
                TextView a = Ui.text(c, 13, 600, Ui.TEXT); a.setText(last);
                TextView b = Ui.text(c, 11.5f, 400, Ui.TEXT_2); b.setText(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(c).getLanguage(), "tv.lock_last_opened"));
                txt.addView(a); txt.addView(b);
                pill.addView(txt);
                card.addView(pill, new LinearLayout.LayoutParams(-2, -2));
            } else {
                TextView pill = Ui.text(c, 12, 600, Ui.TEXT_2); pill.setText(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(c).getLanguage(), "tv.lock_no_history"));
                pill.setLetterSpacing(0.08f);
                pill.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
                pill.setBackground(new GlassDrawable(10 * d, 0x26FFFFFF, d));
                card.addView(pill, new LinearLayout.LayoutParams(-2, -2));
            }
            return card;
        }

        void render() {
            long d = Ui.dur(getContext(), Ui.MED);
            int ringColor = Ui.highContrast(getContext()) ? tech.kousozo.tahoetv.core.Brand.HC_FOCUS : Color.WHITE;
            for (int i = 0; i < 3; i++) {
                boolean f = i == sel;
                cards[i].animate().scaleX(f ? 1f : 0.86f).scaleY(f ? 1f : 0.86f).alpha(f ? 1f : 0.55f).setDuration(d).setInterpolator(Ui.out()).start();
                View p = cards[i].getChildAt(0);
                ((GradientDrawable) p.getForeground()).setStroke((int) Ui.dp(getContext(), 3), f ? ringColor : 0x00FFFFFF);
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
