package tv.clean.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.*;

/** Control Center, app menu, phone pairing and smart-home sheets. All are focus-driven grids of glass tiles. */
final class Sheets {
    interface Adjust { void by(int dir); }

    static final class Item {
        String glyph, title, sub; Runnable enter; Adjust adjust; boolean wide; float level = -1; boolean on;
        Item(String glyph, String title, String sub, Runnable enter) { this.glyph = glyph; this.title = title; this.sub = sub; this.enter = enter; }
        Item wide() { wide = true; return this; }
        Item adjust(Adjust a) { adjust = a; return this; }
        Item level(float l) { level = l; return this; }
        Item on(boolean b) { on = b; return this; }
    }

    /** Glass panel with rows of items (a row holds one wide item or up to two normal ones). */
    static abstract class Grid extends FrameLayout implements Sheet {
        final Home home;
        final LinearLayout panel;
        final ScrollView scroll;
        final List<List<Item>> rows = new ArrayList<>();
        final List<List<View>> views = new ArrayList<>();
        int r, c;
        final float d;

        Grid(Home h, int widthDp, int gravity) {
            super(h);
            home = h; d = Ui.dp(h, 1);
            setBackgroundColor(0x80000000);
            panel = new LinearLayout(h); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
            // Scrolls when the content is taller than the screen; render() keeps the focused row visible.
            scroll = new ScrollView(h);
            scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(false);
            scroll.setBackground(new GlassDrawable(Ui.dp(h, 34), Ui.GLASS_STRONG, d));
            Ui.clip(scroll, Ui.dp(h, 34));
            scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
            LayoutParams lp = new LayoutParams((int) (widthDp * d), -2, gravity);
            lp.topMargin = lp.bottomMargin = lp.rightMargin = lp.leftMargin = (int) (24 * d);
            addView(scroll, lp);
        }

        abstract void fill();

        void header(String eyebrow, String title) {
            TextView e = Ui.eyebrow(getContext(), Ui.TEXT_3); e.setText(eyebrow);
            TextView t = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT); t.setText(title);
            e.setPadding((int) (6 * d), 0, 0, 0); t.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (12 * d));
            panel.addView(e); panel.addView(t);
        }

        void add(Item... items) { rows.add(Arrays.asList(items)); }

        void rebuild() {
            int keepR = r, keepC = c;
            panel.removeAllViews(); rows.clear(); views.clear();
            fill();
            for (List<Item> row : rows) {
                LinearLayout line = new LinearLayout(getContext());
                List<View> vs = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    View v = tile(row.get(i));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                    if (i > 0) lp.leftMargin = (int) (10 * d);
                    line.addView(v, lp); vs.add(v);
                }
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = (int) (8 * d);
                panel.addView(line, ll);
                views.add(vs);
            }
            r = Math.min(keepR, rows.size() - 1); c = Math.min(keepC, rows.get(r).size() - 1);
            render();
        }

        View tile(Item it) {
            Context ctx = getContext();
            LinearLayout t = new LinearLayout(ctx); t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding((int) (14 * d), (int) (11 * d), (int) (14 * d), (int) (11 * d));
            LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView g = Ui.text(ctx, 20, 400, Ui.TEXT); g.setText(it.glyph); g.setTag("g");
            TextView ti = Ui.text(ctx, 15, 600, Ui.TEXT); ti.setText(it.title); ti.setTag("t");
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1); tl.leftMargin = (int) (10 * d);
            top.addView(g); top.addView(ti, tl);
            t.addView(top);
            if (it.sub != null && !it.sub.isEmpty()) {
                TextView s = Ui.text(ctx, 12.5f, 400, Ui.TEXT_2); s.setText(it.sub); s.setTag("s");
                s.setPadding((int) (30 * d), (int) (2 * d), 0, 0);
                t.addView(s);
            }
            if (it.level >= 0) {
                Slider sl = new Slider(ctx, it.level); sl.setTag("sl");
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, (int) (10 * d)); sp.topMargin = (int) (10 * d);
                t.addView(sl, sp);
            }
            t.setBackground(new GlassDrawable(Ui.dp(ctx, 20), it.on ? 0x40FFFFFF : 0x1FFFFFFF, d));
            return t;
        }

        void render() {
            for (int i = 0; i < views.size(); i++) for (int j = 0; j < views.get(i).size(); j++) {
                boolean f = i == r && j == c;
                View v = views.get(i).get(j);
                Item it = rows.get(i).get(j);
                ((GlassDrawable) v.getBackground()).fill(f ? 0xF2F5F5F7 : it.on ? 0x40FFFFFF : 0x1FFFFFFF);
                int col = f ? 0xFF111114 : Ui.TEXT;
                for (String tag : new String[]{"g", "t", "s"}) {
                    TextView tv = v.findViewWithTag(tag);
                    if (tv != null) { tv.setTextColor(tag.equals("s") ? (f ? 0xB3111114 : Ui.TEXT_2) : col); tv.setShadowLayer(0, 0, 0, 0); }
                }
                Slider sl = v.findViewWithTag("sl");
                if (sl != null) { sl.dark = f; sl.invalidate(); }
                v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.FAST).start();
            }
            View line = (View) views.get(r).get(c).getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, line.getTop() - (int) (60 * d))));
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            Item it = rows.get(r).get(c);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: if (r > 0) { r--; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (r < rows.size() - 1) { r++; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (it.adjust != null) { it.adjust.by(dir); rebuild(); }
                    else if (c + dir >= 0 && c + dir < rows.get(r).size()) { c += dir; render(); }
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0 && it.enter != null) it.enter.run();
                    return true;
            }
            return false;
        }

        @Override public void onClose() {}
    }

    /** Thin capsule slider for volume. */
    static final class Slider extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final float level; boolean dark;
        Slider(Context c, float level) { super(c); this.level = level; }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            p.setColor(dark ? 0x26000000 : 0x33FFFFFF); c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            p.setColor(dark ? 0xFF111114 : 0xFFF5F5F7); c.drawRoundRect(0, 0, Math.max(h, w * level), h, h / 2, h / 2, p);
        }
    }

    // ---------------------------------------------------------------- Control Center

    static final class Control extends Grid {
        Control(Home h) { super(h, 440, Gravity.END | Gravity.TOP); rebuild(); }

        @Override void fill() {
            Home h = home;
            String name = h.getString(R.string.user_name);
            header(Lock.LockView.initials(name) + "  ·  Pusat Kontrol", name.isEmpty() ? "Tahoe TV" : name);
            AudioManager am = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            add(new Item("🔊", "Volume", Math.round(100f * v / max) + "%   ◀ ▶", null).wide().level((float) v / max)
                    .adjust(dir -> am.adjustSuggestedStreamVolume(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.STREAM_MUSIC, 0)));
            if (h.phoneMedia != null) {
                boolean playing = h.phoneMedia.optBoolean("playing");
                add(new Item(playing ? "⏸" : "▶", h.phoneMedia.optString("title"), h.phoneMedia.optString("artist") + "  ·  di HP  ·  ◀ ▶ lagu", () -> {
                    h.phoneMediaCommand(playing ? "pause" : "play");
                }).wide().on(playing).adjust(dir -> h.phoneMediaCommand(dir > 0 ? "next" : "prev")));
            }
            add(new Item("◉", "Wi-Fi", ssid(h), () -> h.start(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                new Item("ᛒ", "Bluetooth", "Remote & controller", () -> h.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
            add(new Item("◐", "Wallpaper", "Ganti sekarang", () -> h.hero.nextWall()),
                new Item("☁", "Cuaca", "Per jam & 7 hari", () -> { h.closeSheet(); h.openWeather(); }));
            add(new Item("☪", "Shalat & adzan", "Jadwal & suara", () -> { h.closeSheet(); h.openPrayer(); }),
                new Item("✦", "Asisten", Assistant.enabled() ? "Gemini" : "Belum diatur", () -> { h.closeSheet(); h.openAssistant(); }));
            add(new Item("⌂", "Rumah", HomeControl.enabled() ? "Home Assistant" : "Hey Google", () -> { h.closeSheet(); h.openSheet(new Devices(h)); }),
                new Item("▯", "HP", h.companion == null || h.companion.pairedCount() == 0 ? "Pairing" : h.companion.pairedCount() + " HP", () -> { h.closeSheet(); h.openPair(); }),
                new Item("⚙", "Setelan", "Sistem", () -> h.start(new Intent(Settings.ACTION_SETTINGS))));
        }

        static String ssid(Context c) {
            String s = ((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getSSID();
            return s == null || s.contains("unknown ssid") || s.equals("0x") ? "Tidak terhubung" : s.replace("\"", "");
        }
    }

    // ---------------------------------------------------------------- App menu (hold OK)

    static final class AppMenu extends Grid {
        final Home.App app;

        AppMenu(Home h, Home.App app, Tile anchor) {
            super(h, 340, Gravity.CENTER);
            this.app = app;
            setBackgroundColor(0x66000000);
            rebuild();
        }

        @Override void fill() {
            Home h = home;
            header(app.system ? "Aplikasi sistem" : "Aplikasi", app.label);
            add(new Item("▶", "Buka", null, () -> { h.closeSheet(); h.launch(app.launch, app, h.focusedTile()); }).wide());
            if (app.pkg.equals(Trending.YT) && !h.trending.isEmpty()) {
                Trending.Video v = h.trending.get(h.trendIndex % h.trending.size());
                add(new Item("★", "Putar trending", v.title, () -> { h.closeSheet(); h.start(Trending.watch(v.id)); }).wide());
            }
            List<String> pins = h.dockPackages();
            int at = pins.indexOf(app.pkg);
            if (at >= 0) {
                add(new Item("−", "Lepas dari Dock", null, () -> { pins.remove(app.pkg); h.closeSheet(); h.saveDock(pins); }),
                    new Item("⇄", "Geser", "◀ ▶", null).adjust(dir -> {
                        int i = pins.indexOf(app.pkg), j = i + dir;
                        if (j >= 0 && j < pins.size()) { Collections.swap(pins, i, j); h.saveDock(pins); }
                    }));
            } else if (h.dock.size() < h.dockSize)
                add(new Item("+", "Sematkan ke Dock", null, () -> { pins.add(app.pkg); h.closeSheet(); h.saveDock(pins); }).wide());
            else add(new Item("+", "Dock penuh", "Lepas satu dulu (" + h.dockSize + ")", null).wide());
            Item info = new Item("ⓘ", "Info", null, () -> { h.closeSheet(); h.start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); });
            if (app.system) add(info.wide());
            else add(info, new Item("🗑", "Uninstall", null, () -> { h.closeSheet(); h.start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))); }));
        }

        @Override public boolean key(KeyEvent e) {
            // still holding OK from the long-press that opened us
            if ((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() > 0) return true;
            return super.key(e);
        }
    }

    // ---------------------------------------------------------------- Phone pairing

    static final class Pair extends FrameLayout implements Sheet {
        final Home home;
        final ImageView qr;
        final TextView code, url, count;

        Pair(Home h) {
            super(h);
            home = h;
            float d = Ui.dp(h, 1);
            setBackgroundColor(0xF2080A10);
            LinearLayout left = new LinearLayout(h); left.setOrientation(LinearLayout.VERTICAL);
            TextView eb = Ui.eyebrow(h, Ui.TEXT_3); eb.setText("Hubungkan HP");
            TextView ti = Ui.text(h, Ui.TITLE, 600, Ui.TEXT); ti.setText("Pindai dengan kamera HP");
            TextView body = Ui.text(h, Ui.BODY, 400, Ui.TEXT_2);
            body.setSingleLine(false); body.setLineSpacing(0, 1.3f);
            body.setText("Remote, kirim link & foto, tanya asisten pakai suara HP.\nApp Android Tahoe Companion juga meneruskan notifikasi dan musik dari HP.\nHP dan TV harus di Wi-Fi yang sama.");
            code = Ui.text(h, 54, 300, Ui.TEXT); code.setLetterSpacing(0.25f);
            url = Ui.text(h, Ui.BODY, 500, Ui.ACCENT);
            count = Ui.text(h, Ui.CAPTION + 1, 500, Ui.TEXT_3);
            left.addView(eb); left.addView(ti);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.topMargin = (int) (14 * d);
            left.addView(body, bl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.topMargin = (int) (26 * d);
            left.addView(code, cl); left.addView(url); left.addView(count);
            LayoutParams ll = new LayoutParams((int) (520 * d), -2, Gravity.CENTER_VERTICAL); ll.leftMargin = (int) (64 * d);
            addView(left, ll);
            qr = new ImageView(h);
            qr.setBackground(new GlassDrawable(Ui.dp(h, 28), 0xFFF5F5F7, d));
            qr.setPadding((int) (18 * d), (int) (18 * d), (int) (18 * d), (int) (18 * d));
            LayoutParams ql = new LayoutParams((int) (260 * d), (int) (260 * d), Gravity.END | Gravity.CENTER_VERTICAL); ql.rightMargin = (int) (72 * d);
            addView(qr, ql);
            TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText("OK: kode baru  ·  Menu: lupakan semua HP  ·  Back: tutup");
            LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = (int) (18 * d);
            addView(hint, hl);
            refresh();
        }

        void refresh() {
            Companion c = home.companion;
            if (c == null) { code.setText("—"); return; }
            code.setText(c.code);
            url.setText("http://" + Companion.ip() + ":" + Companion.PORT);
            count.setText(c.pairedCount() + " HP tersambung");
            qr.setImageBitmap(qr(c.pairUrl(), 512));
        }

        static Bitmap qr(String text, int size) {
            try {
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                int[] px = new int[size * size];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF111114 : 0xFFF5F5F7;
                bm.setPixels(px, 0, size, 0, 0, size, size);
                return bm;
            } catch (Exception e) { return null; }
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: if (home.companion != null) { home.companion.newCode(); refresh(); } return true;
                case KeyEvent.KEYCODE_MENU: if (home.companion != null) { home.companion.forgetAll(); refresh(); home.toast("Semua HP dilupakan"); } return true;
                case KeyEvent.KEYCODE_BACK: return false;
            }
            return true;
        }

        @Override public void onClose() {}
    }

    // ---------------------------------------------------------------- Smart home (Home Assistant)

    static final class Devices extends Grid {
        List<HomeControl.Device> list;
        String status = "Memuat…";

        Devices(Home h) {
            super(h, 520, Gravity.CENTER);
            rebuild();
            if (HomeControl.enabled()) Net.bg.execute(() -> {
                try { List<HomeControl.Device> l = HomeControl.devices(); post(() -> { list = l; status = l.isEmpty() ? "Tidak ada perangkat" : null; rebuild(); }); }
                catch (Exception e) { post(() -> { status = "Gagal: " + e.getMessage(); rebuild(); }); }
            });
        }

        @Override void fill() {
            header("Rumah", HomeControl.enabled() ? "Perangkat" : "Kontrol rumah");
            if (!HomeControl.enabled()) {
                add(new Item("🎙", "Pakai tombol mic remote", "\"Hey Google, matikan lampu\" — Google Assistant bawaan TV", null).wide());
                add(new Item("⌂", "Home Assistant", "Isi homeassistant.url & token di secrets.properties", null).wide());
                return;
            }
            if (list == null || list.isEmpty()) { add(new Item("…", status == null ? "" : status, null, null).wide()); return; }
            for (int i = 0; i < list.size(); i += 2) {
                HomeControl.Device a = list.get(i);
                if (i + 1 < list.size()) add(item(a), item(list.get(i + 1))); else add(item(a).wide());
            }
        }

        Item item(HomeControl.Device dv) {
            String g = dv.domain.equals("light") ? "💡" : dv.domain.equals("fan") ? "❋" : dv.domain.equals("climate") ? "❄" : "⏻";
            return new Item(g, dv.name, dv.on ? "Nyala" : "Mati", () -> Net.bg.execute(() -> {
                try { HomeControl.toggle(dv); post(this::rebuild); } catch (Exception e) { post(() -> home.toast("Gagal: " + e.getMessage())); }
            })).on(dv.on);
        }
    }
}
