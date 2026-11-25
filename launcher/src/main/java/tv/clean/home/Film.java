package tv.clean.home;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import tech.kousozo.tahoetv.core.FilmLibrary;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.MediaScraper;

/** TV "Film & Series" tab: everything from pixeldrain or a film/series group, which never shows in Live TV.
 *  Two poster rows (movies, series); the poster is looked up from the title. OK plays; a series plays its episodes in order. */
final class Film extends FrameLayout implements Sheet {
    private final Home home;
    private final float S;
    private final String lang;
    private final LinearLayout body;

    Film(Home h) {
        super(h);
        home = h; S = h.W / 1920f; lang = TvViewer.get(h).getLanguage();
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0D1429, 0xFF08080D}));
        setClickable(true);
        TextView title = tv(I18n.get(lang, "tv.mh_tab_film"), 46, 600, 0xFFF7F8FA); title.setLetterSpacing(-0.02f);
        addAt(title, 112, 64, -2, -2);
        addAt(tv(I18n.get(lang, "tv.film_hint"), 19, 500, 0x9EF7F8FA), 112, 128, -2, -2);
        ScrollView sv = new ScrollView(h); sv.setVerticalScrollBarEnabled(false); sv.setClipToPadding(false); sv.setPadding(px(8), px(8), px(8), px(8));
        body = new LinearLayout(h); body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        addAt(sv, 104, 170, 1712, 880);
        fill();
        post(() -> { View f = firstCard(); if (f != null) f.requestFocus(); });
    }

    private void fill() {
        List<FilmLibrary.Title> movies = new ArrayList<>(), series = new ArrayList<>();
        for (FilmLibrary.Title t : Live.library) (t.getKind() == FilmLibrary.Kind.Series ? series : movies).add(t);
        if (movies.isEmpty() && series.isEmpty()) {
            TextView empty = tv(I18n.get(lang, "tv.film_empty"), 30, 600, 0xCCF7F8FA);
            empty.setSingleLine(false); empty.setMaxLines(4); empty.setPadding(px(8), px(24), px(8), 0);
            body.addView(empty, new LinearLayout.LayoutParams(px(1400), -2));
            return;
        }
        if (!movies.isEmpty()) addRow(I18n.get(lang, "tv.film_movies"), movies);
        if (!series.isEmpty()) addRow(I18n.get(lang, "tv.film_series"), series);
    }

    private void addRow(String label, List<FilmLibrary.Title> titles) {
        body.addView(tv(label, 30, 600, 0xFFF7F8FA));
        HorizontalScrollView hs = new HorizontalScrollView(home);
        hs.setHorizontalScrollBarEnabled(false); hs.setClipChildren(false); hs.setClipToPadding(false);
        hs.setPadding(px(10), px(24), px(10), px(24));
        LinearLayout row = new LinearLayout(home); row.setClipChildren(false);
        for (FilmLibrary.Title t : titles) row.addView(card(t));
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(1700), -2); lp.setMarginStart(-px(10)); lp.bottomMargin = px(12);
        body.addView(hs, lp);
    }

    private View card(FilmLibrary.Title t) {
        final int w = px(200), h = px(300);
        LinearLayout col = new LinearLayout(home); col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout box = new FrameLayout(home);
        GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF22305A, 0xFF111628}); ph.setCornerRadius(px(18));
        box.setBackground(ph);
        TextView fallback = tv(t.getTitle(), 26, 700, 0xCCFFFFFF);
        fallback.setSingleLine(false); fallback.setMaxLines(5); fallback.setGravity(Gravity.CENTER); fallback.setPadding(px(14), px(14), px(14), px(14));
        box.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(home); img.setScaleType(ImageView.ScaleType.CENTER_CROP); Ui.clip(img, px(18));
        img.setVisibility(View.GONE);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        col.addView(box, new LinearLayout.LayoutParams(w, h));
        boolean series = t.getKind() == FilmLibrary.Kind.Series;
        String sub = series ? I18n.get(lang, "tv.film_episodes", String.valueOf(t.getItems().size())) : (t.getYear() == null ? "" : String.valueOf(t.getYear()));
        TextView name = tv(t.getTitle(), 20, 600, 0xFFF7F8FA);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, -2); nl.topMargin = px(10);
        col.addView(name, nl);
        col.addView(tv(sub, 16, 500, 0x99F7F8FA), new LinearLayout.LayoutParams(w, -2));
        col.setPadding(px(8), px(8), px(8), px(8));
        col.setFocusable(true); col.setFocusableInTouchMode(true); col.setClickable(true);
        col.setOnFocusChangeListener((v, fo) -> { col.setBackground(frame(fo)); col.animate().scaleX(fo ? 1.06f : 1f).scaleY(fo ? 1.06f : 1f).setDuration(Ui.dur(home, Ui.FAST)).start(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMarginEnd(px(20)); col.setLayoutParams(cp);
        col.setTag(t);
        col.setOnClickListener(v -> play(t));
        MediaScraper.prefetchPoster(t.getTitle(), t.getYear(), series, url -> { img.post(() -> Logos.fetchAsync(url, (Bitmap b) -> { img.setImageBitmap(b); img.setVisibility(View.VISIBLE); fallback.setVisibility(View.GONE); })); return kotlin.Unit.INSTANCE; });
        return col;
    }

    private void play(FilmLibrary.Title t) {
        List<String> urls = new ArrayList<>();
        for (FilmLibrary.Item i : t.getItems()) urls.add(i.getChannel().getUrl());
        home.closeSheet();
        home.start(new Intent(home, RecordingPlayer.class).putExtra("urls", urls.toArray(new String[0])).putExtra("title", t.getTitle()));
    }

    private GradientDrawable frame(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(24)); g.setColor(focused ? 0x26FFFFFF : 0);
        if (focused) g.setStroke(px(4), 0xFF5CD0FF);
        return g;
    }

    private View firstCard() { return find(body); }
    private View find(View v) {
        if (v.getTag() instanceof FilmLibrary.Title) return v;
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { View r = find(g.getChildAt(i)); if (r != null) return r; } }
        return null;
    }

    private void addAt(View v, float x, float y, float w, float h) {
        LayoutParams lp = new LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h)); lp.leftMargin = px(x); lp.topMargin = px(y); addView(v, lp);
    }

    @Override public boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View f = findFocus();
        if (f == null) { View c = firstCard(); if (c != null) c.requestFocus(); return true; }
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir != 0) { View n = f.focusSearch(dir); if (n != null) n.requestFocus(); return true; }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() == 0) { f.performClick(); return true; }
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
