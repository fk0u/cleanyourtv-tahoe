package tv.clean.home;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import tech.kousozo.tahoetv.core.I18n;

/** TV "Rekaman" tab: the recordings made from Media Center (files in the app's recordings folder), newest first.
 *  OK plays one in [RecordingPlayer]; each row has a Delete pill. Same look as the other Figma screens. */
final class Rekaman extends FrameLayout implements Sheet {
    private final Home home;
    private final float S;
    private final String lang;
    private final LinearLayout list;

    Rekaman(Home h) {
        super(h);
        home = h; S = h.W / 1920f; lang = TvViewer.get(h).getLanguage();
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0D1429, 0xFF08080D}));
        setClickable(true);
        TextView title = tv(I18n.get(lang, "tv.mh_tab_rec"), 46, 600, 0xFFF7F8FA); title.setLetterSpacing(-0.02f);
        addAt(title, 112, 64, -2, -2);
        TextView hint = tv(I18n.get(lang, "tv.rec_hint"), 19, 500, 0x9EF7F8FA);
        addAt(hint, 112, 128, -2, -2);
        ScrollView sv = new ScrollView(h); sv.setVerticalScrollBarEnabled(false); sv.setClipToPadding(false); sv.setPadding(px(8), px(8), px(8), px(8));
        list = new LinearLayout(h); list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        addAt(sv, 104, 190, 1712, 840);
        fill();
        post(() -> { if (list.getChildCount() > 0) list.getChildAt(0).requestFocus(); });
    }

    private File dir() {
        File d = home.getExternalFilesDir("recordings");
        return d != null ? d : new File(home.getFilesDir(), "recordings");
    }

    private void fill() {
        list.removeAllViews();
        File[] files = dir().listFiles((d, n) -> n.endsWith(".ts"));
        if (files == null || files.length == 0) {
            TextView empty = tv(I18n.get(lang, "tv.rec_empty"), 30, 600, 0xCCF7F8FA);
            empty.setSingleLine(false); empty.setPadding(px(8), px(24), px(8), 0);
            list.addView(empty);
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy · HH.mm", Locale.forLanguageTag(lang));
        for (File f : files) list.addView(row(f, df));
    }

    private View row(File f, SimpleDateFormat df) {
        LinearLayout row = new LinearLayout(home); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(36), px(24), px(24), px(24));
        row.setBackground(bg(false));
        row.setFocusable(true); row.setFocusableInTouchMode(true); row.setClickable(true);
        row.setOnFocusChangeListener((v, fo) -> { row.setBackground(bg(fo)); });
        String base = f.getName().replaceAll("\\.ts$", "");
        int dash = base.lastIndexOf('-');
        String name = (dash > 0 ? base.substring(0, dash) : base).replace('_', ' ');
        LinearLayout col = new LinearLayout(home); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(name, 30, 600, 0xFFF7F8FA));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.topMargin = px(6);
        col.addView(tv(df.format(new Date(f.lastModified())) + "  ·  " + mb(f.length()), 19, 500, 0xCCF7F8FA), ml);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView del = tv(I18n.get(lang, "tv.cari_delete"), 24, 600, 0xFFF7F8FA);
        del.setPadding(px(28), px(14), px(28), px(14));
        del.setBackground(pill(false));
        del.setFocusable(true); del.setFocusableInTouchMode(true); del.setClickable(true);
        del.setOnFocusChangeListener((v, fo) -> del.setBackground(pill(fo)));
        del.setOnClickListener(v -> { f.delete(); Toast.makeText(home, I18n.get(lang, "tv.rec_gone"), Toast.LENGTH_SHORT).show(); fill(); if (list.getChildCount() > 0) list.getChildAt(0).requestFocus(); });
        row.addView(del);
        row.setOnClickListener(v -> { home.closeSheet(); home.start(new Intent(home, RecordingPlayer.class).putExtra("path", f.getPath()).putExtra("title", name)); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = px(16);
        row.setLayoutParams(lp);
        return row;
    }

    private static String mb(long bytes) { return bytes >= 1_000_000_000L ? String.format(Locale.US, "%.1f GB", bytes / 1e9) : String.format(Locale.US, "%d MB", bytes / 1_000_000L); }

    private GradientDrawable bg(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(28)); g.setColor(focused ? 0x26FFFFFF : 0x0FFFFFFF);
        if (focused) g.setStroke(px(4), 0xFF5CD0FF);
        return g;
    }

    private GradientDrawable pill(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(999)); g.setColor(focused ? 0xFFFFFFFF : 0x24FFFFFF);
        if (focused) g.setStroke(px(4), 0xFF5CD0FF);
        return g;
    }

    private void addAt(View v, float x, float y, float w, float h) {
        LayoutParams lp = new LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h)); lp.leftMargin = px(x); lp.topMargin = px(y); addView(v, lp);
    }

    @Override public boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View f = findFocus();
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir != 0 && f != null) { View n = f.focusSearch(dir); if (n != null) n.requestFocus(); return true; }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && f != null) { if (e.getRepeatCount() == 0) f.performClick(); return true; }
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
