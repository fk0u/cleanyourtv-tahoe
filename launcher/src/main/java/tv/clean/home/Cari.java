package tv.clean.home;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.LocalTv;

/** The Figma "TV / Cari" screen: a search field, an on-screen keyboard (so no system keyboard pops up over the TV),
 *  and the matching channels and programmes on the right. Native D-pad focus inside the sheet. */
final class Cari extends FrameLayout implements Sheet {
    private final Home home;
    private final float S;
    private final String lang;
    private final StringBuilder query = new StringBuilder();
    private final TextView field, header;
    private final LinearLayout results;
    private View firstKey;

    Cari(Home h) {
        super(h);
        home = h; S = h.W / 1920f; lang = TvViewer.get(h).getLanguage();
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0D1429, 0xFF08080D}));
        setClickable(true);

        TextView title = tv(I18n.get(lang, "tv.cari_title"), 46, 600, 0xFFF7F8FA); title.setLetterSpacing(-0.02f);
        addAt(title, 112, 64, -2);

        // field: pill with a cyan 3px ring, magnifier, the typed text and a cursor
        LinearLayout fieldBox = new LinearLayout(h); fieldBox.setGravity(Gravity.CENTER_VERTICAL);
        fieldBox.setPadding(px(36), px(14), px(36), px(14));
        GradientDrawable fb = new GradientDrawable(); fb.setCornerRadius(px(999)); fb.setColor(0x1AFFFFFF); fb.setStroke(Math.max(2, px(3)), 0xFF5CD0FF);
        fieldBox.setBackground(fb);
        ImageView mag = new ImageView(h); mag.setImageResource(R.drawable.ic_search_cari); mag.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFF7F8FA));
        fieldBox.addView(mag, new LinearLayout.LayoutParams(px(32), px(32)));
        field = tv("", 30, 600, 0xFFF7F8FA);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -2, 1); fl.setMarginStart(px(20));
        fieldBox.addView(field, fl);
        addAt(fieldBox, 112, 160, 760, 86);

        // keyboard: 7 per row, then Space / Delete / Voice
        LinearLayout kb = new LinearLayout(h); kb.setOrientation(LinearLayout.VERTICAL);
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ12";
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(h);
            for (int c = 0; c < 7; c++) {
                final String ch = letters.substring(r * 7 + c, r * 7 + c + 1);
                TextView k = key(ch, 96, 30, 600);
                k.setOnClickListener(v -> { query.append(ch.toLowerCase(Locale.ROOT)); update(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(96), px(84)); if (c > 0) lp.setMarginStart(px(12));
                line.addView(k, lp);
                if (r == 0 && c == 0) firstKey = k;
            }
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2); if (r > 0) ll.topMargin = px(12);
            kb.addView(line, ll);
        }
        LinearLayout bottom = new LinearLayout(h);
        TextView space = key(I18n.get(lang, "tv.cari_space"), 300, 24, 400); space.setOnClickListener(v -> { query.append(' '); update(); });
        TextView del = key(I18n.get(lang, "tv.cari_delete"), 200, 24, 400); del.setOnClickListener(v -> { if (query.length() > 0) query.setLength(query.length() - 1); update(); });
        TextView voice = key("🎤 " + I18n.get(lang, "tv.cari_voice"), 232, 24, 400); voice.setOnClickListener(v -> { home.closeSheet(); home.openAssistant(); });
        bottom.addView(space, new LinearLayout.LayoutParams(px(300), px(84)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(200), px(84)); dl.setMarginStart(px(12)); bottom.addView(del, dl);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(px(232), px(84)); vl.setMarginStart(px(12)); bottom.addView(voice, vl);
        LinearLayout.LayoutParams bll = new LinearLayout.LayoutParams(-2, -2); bll.topMargin = px(12);
        kb.addView(bottom, bll);
        addAt(kb, 112, 290, -2);

        // results (x1000 y160)
        LinearLayout right = new LinearLayout(h); right.setOrientation(LinearLayout.VERTICAL);
        header = tv("", 16, 600, 0x9EF7F8FA); header.setLetterSpacing(0.1f);
        right.addView(header);
        results = new LinearLayout(h); results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = px(18);
        right.addView(results, rl);
        addAt(right, 1000, 160, 800);

        update();
        post(() -> { if (firstKey != null) firstKey.requestFocus(); });
    }

    // ------------------------------------------------------------------ search

    private void update() {
        String q = query.toString().trim().toLowerCase(Locale.ROOT);
        field.setText(query.length() == 0 ? "|" : query + "|");
        field.setTextColor(query.length() == 0 ? 0x66F7F8FA : 0xFFF7F8FA);
        results.removeAllViews();
        if (q.isEmpty()) { header.setText(I18n.get(lang, "tv.cari_empty").toUpperCase(Locale.ROOT)); return; }
        header.setText(I18n.get(lang, "tv.cari_results", query.toString().trim().toUpperCase(Locale.ROOT)));
        List<LocalTv.Entry> all = Live.cached == null ? new ArrayList<>() : Live.cached;
        Instant now = Instant.now();
        int shown = 0;
        for (LocalTv.Entry e : all) {
            String prog = null;
            if (Live.epg != null) {
                List<Epg.Programme> s = Live.epg.schedule(e.getName(), now, e.getChannel().getTvgId());
                if (s != null) for (Epg.Programme p : s) if (p.getTitle().toLowerCase(Locale.ROOT).contains(q)) { prog = p.getTitle(); break; }
                if (prog == null && s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now)) { /* keep the on-air title only as subtitle below */ }
            }
            boolean byName = e.getName().toLowerCase(Locale.ROOT).contains(q);
            if (!byName && prog == null) continue;
            results.addView(resultRow(e, prog, now));
            if (++shown >= 5) break;
        }
        if (shown == 0) { TextView none = tv(I18n.get(lang, "tv.cari_none"), 30, 600, 0xCCF7F8FA); results.addView(none); }
    }

    private View resultRow(LocalTv.Entry e, String prog, Instant now) {
        LinearLayout row = new LinearLayout(home); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(12), px(12), px(12));
        row.setBackground(rowBg(false));
        row.setFocusable(true); row.setFocusableInTouchMode(true); row.setClickable(true);
        row.setOnFocusChangeListener((v, f) -> row.setBackground(rowBg(f)));
        row.setOnClickListener(v -> { home.closeSheet(); home.start(new Intent(home, LiveTv.class).putExtra("url", e.getChannel().getUrl())); });
        FrameLayout th = new FrameLayout(home);
        int c1 = 0xFF000000 | (e.getName().hashCode() & 0x00FFFFFF);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Tile.blend(c1, 0xFF0A0B10, 0.55f), Tile.blend(c1, 0xFFFFFFFF, 0.15f)});
        g.setCornerRadius(px(16)); th.setBackground(g); Ui.clip(th, px(16));
        ImageView logo = new ImageView(home); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        TextView fb = tv("", 24, 700, 0xCCFFFFFF); fb.setGravity(Gravity.CENTER);
        th.addView(fb, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lgl = new FrameLayout.LayoutParams(-1, -1); lgl.setMargins(px(30), px(18), px(30), px(18)); th.addView(logo, lgl);
        Ui.bindLogo(e.getChannel().getLogo(), logo, fb, e.getName());
        row.addView(th, new LinearLayout.LayoutParams(px(220), px(124)));
        LinearLayout col = new LinearLayout(home); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(prog != null ? prog : e.getName(), 30, 600, 0xFFF7F8FA));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = px(6);
        col.addView(tv(I18n.get(lang, "tv.cari_on_air", e.getName()), 19, 500, 0xCCF7F8FA), sl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1); cl.setMarginStart(px(24));
        row.addView(col, cl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(px(800), -2); rl.bottomMargin = px(18);
        row.setLayoutParams(rl);
        return row;
    }

    private GradientDrawable rowBg(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(24)); g.setColor(focused ? 0x2EFFFFFF : 0x0DFFFFFF);
        if (focused) g.setStroke(px(4), 0xFFFFFFFF);
        return g;
    }

    // ------------------------------------------------------------------ views

    private TextView key(String label, float w, float size, int wght) {
        TextView k = tv(label, size, wght, 0xFFF7F8FA);
        k.setGravity(Gravity.CENTER);
        k.setBackground(keyBg(false));
        k.setFocusable(true); k.setFocusableInTouchMode(true); k.setClickable(true);
        k.setOnFocusChangeListener((v, f) -> { k.setBackground(keyBg(f)); k.setTextColor(f ? 0xFF0D0F14 : 0xFFF7F8FA); });
        return k;
    }

    private GradientDrawable keyBg(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(20));
        g.setColor(focused ? 0xFFFFFFFF : 0x14FFFFFF);
        if (focused) g.setStroke(px(5), 0xFF5CD0FF);
        return g;
    }

    private void addAt(View v, float x, float y, float w) { addAt(v, x, y, w, -2); }

    private void addAt(View v, float x, float y, float w, float h) {
        LayoutParams lp = new LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h));
        lp.leftMargin = px(x); lp.topMargin = px(y);
        addView(v, lp);
    }

    // ------------------------------------------------------------------ Sheet

    @Override public boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return false; // Home closes the sheet
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View f = findFocus();
        if (f == null) { if (firstKey != null) firstKey.requestFocus(); return true; }
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir != 0 && f != null) { View n = f.focusSearch(dir); if (n != null) n.requestFocus(); return true; }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && f != null) { if (e.getRepeatCount() == 0) f.performClick(); return true; }
        if (code == KeyEvent.KEYCODE_DEL && query.length() > 0) { query.setLength(query.length() - 1); update(); return true; }
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) { query.append((char) ('a' + code - KeyEvent.KEYCODE_A)); update(); return true; } // a USB/BT keyboard types too
        return true;
    }

    @Override public void onClose() {}

    private int px(float v) { return Math.round(v * S); }

    private TextView tv(String s, float sizePx, int wght, int color) {
        TextView t = new TextView(home);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * S * (sizePx >= 40 ? 1.08f : 1.15f));
        t.setTypeface(Ui.font(home, wght)); t.setTextColor(color); t.setText(s);
        t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setIncludeFontPadding(false);
        return t;
    }
}
