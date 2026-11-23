package tv.clean.home;

import tech.kousozo.tahoetv.core.AccountText;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.List;
import tech.kousozo.tahoetv.core.DeviceProfile;
import tech.kousozo.tahoetv.core.I18n;

/** Phone pairing, Home Assistant device grid, and hardware Device Info sheets (#36, #38, #88). */
final class ConnectSheets {
    private ConnectSheets() {}

    static final class Pair extends FrameLayout implements Sheet {
        final Home home;
        final ImageView qr;
        final TextView code, url, count;

        Pair(Home h) {
            super(h);
            home = h;
            String lang = TvViewer.get(h).getLanguage();
            float d = Ui.dp(h, 1);
            setBackgroundColor(0xF2080A10);
            Ui.crumb(this, R.drawable.ic_smartphone, I18n.get(lang, "tv.home_chip_connect_phone"));
            LinearLayout left = new LinearLayout(h); left.setOrientation(LinearLayout.VERTICAL);
            TextView eb = Ui.eyebrow(h, Ui.TEXT_3); eb.setText(I18n.get(lang, "tv.pair_eb"));
            TextView ti = Ui.text(h, Ui.TITLE, 600, Ui.TEXT); ti.setText(I18n.get(lang, "tv.pair_scan_title"));
            TextView body = Ui.text(h, Ui.BODY, 400, Ui.TEXT_2);
            body.setSingleLine(false); body.setLineSpacing(0, 1.3f);
            body.setText(I18n.get(lang, "tv.pair_scan_body"));
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
            TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText(I18n.get(lang, "tv.pair_hint_keys"));
            LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = (int) (18 * d);
            addView(hint, hl);
            refresh();
        }

        void refresh() {
            Companion c = home.companion;
            if (c == null) { code.setText("—"); return; }
            code.setText(c.code());
            url.setText("http://" + Companion.ip() + ":" + Companion.PORT);
            String lang = TvViewer.get(home).getLanguage();
            count.setText(I18n.get(lang, "tv.cc_phones_cnt", String.valueOf(c.pairedCount())));
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
                case KeyEvent.KEYCODE_MENU: if (home.companion != null) { home.companion.forgetAll(); refresh(); home.toast(I18n.get(TvViewer.get(home).getLanguage(), "tv.pair_forgot_all")); } return true;
                case KeyEvent.KEYCODE_BACK: return false;
            }
            return true;
        }

        final Runnable tick = new Runnable() { @Override public void run() {
            Companion c = home.companion;
            if (c != null && !c.code().equals(code.getText().toString())) refresh();
            postDelayed(this, 5000);
        } };
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 5000); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        @Override public void onClose() {}
    }

    /** Paired phones, each with its own key; OK on one removes its access at once (#180). */
    static final class Phones extends Sheets.Grid {
        Phones(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            header(AccountText.get(lang, "phones_sub"), AccountText.get(lang, "phones"));
            org.json.JSONObject meta = h.companion == null ? new org.json.JSONObject() : h.companion.tokenMeta();
            java.util.List<String> tokens = new java.util.ArrayList<>(h.companion == null ? java.util.Collections.<String>emptySet() : h.companion.tokens());
            if (tokens.isEmpty()) { add(new Sheets.Item(R.drawable.ic_smartphone, AccountText.get(lang, "phones_none"), null, null).wide()); return; }
            java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.forLanguageTag(lang));
            for (String t : tokens) {
                org.json.JSONObject m = meta.optJSONObject(t);
                String name = m == null || m.optString("name").isEmpty() ? "Phone" : m.optString("name");
                long at = m == null ? 0 : m.optLong("pairedAt");
                String sub = (at > 0 ? AccountText.get(lang, "phones_paired", df.format(new java.util.Date(at))) : "") + (m != null && !m.optString("ip").isEmpty() ? "  ·  " + m.optString("ip") : "");
                add(new Sheets.Item(R.drawable.ic_smartphone, name, sub, () -> { h.companion.revoke(t); rebuild(); }).wide());
            }
            add(new Sheets.Item(R.drawable.ic_close, AccountText.get(lang, "revoke_all"), null, () -> { h.companion.forgetAll(); rebuild(); }).wide());
        }
    }

    static final class Devices extends Sheets.Grid {
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
                add(new Sheets.Item(R.drawable.ic_mic, "Pakai tombol mic remote", "\"Hey Google, matikan lampu\" — Google Assistant bawaan TV", null).wide());
                add(new Sheets.Item(R.drawable.ic_house, "Home Assistant", "Isi homeassistant.url & token di secrets.properties", null).wide());
                return;
            }
            if (list == null || list.isEmpty()) { add(new Sheets.Item(R.drawable.ic_clock, status == null ? "" : status, null, null).wide()); return; }
            for (int i = 0; i < list.size(); i += 2) {
                HomeControl.Device a = list.get(i);
                if (i + 1 < list.size()) add(item(a), item(list.get(i + 1))); else add(item(a).wide());
            }
        }

        Sheets.Item item(HomeControl.Device dv) {
            int g = dv.domain.equals("light") ? R.drawable.ic_lightbulb : dv.domain.equals("fan") ? R.drawable.ic_fan
                    : dv.domain.equals("climate") ? R.drawable.ic_thermometer : R.drawable.ic_power;
            return new Sheets.Item(g, dv.name, dv.on ? "Nyala" : "Mati", () -> Net.bg.execute(() -> {
                try { HomeControl.toggle(dv); post(this::rebuild); } catch (Exception e) { post(() -> home.toast("Gagal: " + e.getMessage())); }
            })).on(dv.on);
        }
    }

    static final class DeviceInfo extends Sheets.Grid {
        DeviceInfo(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            DeviceProfile.Profile prof = DeviceProfiler.current(h);
            DeviceProfile.Specs sp = prof.getSpecs();
            header(I18n.get(lang, "device.title"), I18n.get(lang, "device.sub"));
            String tierVal = (prof.getManualOverride() == null
                    ? I18n.get(lang, "device.tier_auto", prof.getDetectedTier().label(lang))
                    : prof.getActiveTier().label(lang)) + "  ·  ◀ ▶";
            add(new Sheets.Item(R.drawable.ic_settings, I18n.get(lang, "device.tier_label"), tierVal, null)
                    .wide()
                    .adjust(dir -> {
                        DeviceProfile.Tier next = dir > 0
                                ? DeviceProfile.cycleOverride(prof.getManualOverride())
                                : DeviceProfile.cycleOverride(
                                    DeviceProfile.cycleOverride(
                                        DeviceProfile.cycleOverride(prof.getManualOverride())));
                        DeviceProfiler.setOverrideTier(h, next);
                    }));
            add(new Sheets.Item(R.drawable.ic_info, I18n.get(lang, "device.ram"),
                    sp.getRamMb() + " MB" + (sp.isLowRam() ? " (Low-RAM)" : ""), null),
                new Sheets.Item(R.drawable.ic_info, I18n.get(lang, "device.cpu"),
                    sp.getCpuCores() + " cores · " + sp.getAbi() + " · API " + sp.getApiLevel(), null));
            add(new Sheets.Item(R.drawable.ic_tv, I18n.get(lang, "device.display"),
                    sp.getWidthPx() + "×" + sp.getHeightPx() + " @ " + sp.getRefreshHz() + " Hz" + (sp.getHdrSupported() ? " · HDR" : ""), null),
                new Sheets.Item(R.drawable.ic_play, I18n.get(lang, "device.codecs"),
                    "HEVC: " + I18n.get(lang, sp.getHwHevc() ? "common.yes" : "common.no") + "   AV1: " + I18n.get(lang, sp.getHwAv1() ? "common.yes" : "common.no") + "   AC3: " + I18n.get(lang, sp.getHwAc3() ? "common.yes" : "common.no"), null));
            long bootMs = DeviceProfile.INSTANCE.getStartupMs();
            add(new Sheets.Item(R.drawable.ic_clock, I18n.get(lang, "device.startup_latency"),
                    bootMs >= 0 ? (bootMs + " ms") : "—", null).wide());
        }
    }
}
