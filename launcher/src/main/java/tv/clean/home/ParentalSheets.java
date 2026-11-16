package tv.clean.home;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.KidsPolicy;
import tech.kousozo.tahoetv.core.MathGate;
import tech.kousozo.tahoetv.core.WatchRecap;

/** Parental controls, PIN/Math gates, Kids screen-time block sheet, and local Monthly Recap (#47, #54, #62). */
final class ParentalSheets {
    private ParentalSheets() {}

    static final class KidsSetup extends Sheets.Grid {
        KidsSetup(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            header(I18n.get(lang, "tv.kids_title"), I18n.get(lang, "tv.kids_sub"));
            add(new Sheets.Item(R.drawable.ic_lock, I18n.get(lang, Kids.hasPin(h) ? "tv.kids_pin_change" : "tv.kids_pin_create"),
                    I18n.get(lang, "tv.kids_pin_sub"),
                    () -> h.createPin(() -> { h.openSheet(new KidsSetup(h)); h.toast(I18n.get(lang, "tv.kids_pin_saved")); }, () -> h.openSheet(new KidsSetup(h)))).wide());
            MathGate.Mode gm = Kids.gateMode(h);
            MathGate.Difficulty gd = Kids.mathDifficulty(h);
            add(new Sheets.Item(R.drawable.ic_lock, I18n.get(lang, "kids.gate_mode"),
                    gm.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> Kids.setGateMode(h, MathGate.Mode.next(gm, dir))),
                new Sheets.Item(R.drawable.ic_sparkles, I18n.get(lang, "kids.gate_diff"),
                    gd.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> Kids.setMathDifficulty(h, MathGate.Difficulty.next(gd, dir))));
            addQuotaRows(h, lang);
            addAllowedAppsRows(h, lang);
        }

        private void addQuotaRows(Home h, String lang) {
            KidsPolicy.Config pol = Kids.policy(h);
            int[] limitPresets = {0, 30, 60, 90, 120, 180};
            String limTxt = pol.getDailyLimitMinutes() <= 0
                    ? I18n.get(lang, "kids.limit_off")
                    : I18n.get(lang, "kids.limit_fmt", pol.getDailyLimitMinutes());
            String schedTxt = !pol.getScheduleEnabled()
                    ? I18n.get(lang, "kids.schedule_off")
                    : KidsPolicy.formatClockMinutes(pol.getAllowedStartMin()) + "–" + KidsPolicy.formatClockMinutes(pol.getAllowedEndMin());
            add(new Sheets.Item(R.drawable.ic_clock, I18n.get(lang, "kids.daily_limit"), limTxt + "  ·  ◀ ▶", null)
                    .adjust(dir -> {
                        int idx = 0;
                        for (int i = 0; i < limitPresets.length; i++) if (limitPresets[i] == pol.getDailyLimitMinutes()) { idx = i; break; }
                        int next = limitPresets[((idx + dir) % limitPresets.length + limitPresets.length) % limitPresets.length];
                        Kids.setPolicy(h, pol.copy(next, pol.getScheduleEnabled(), pol.getAllowedStartMin(), pol.getAllowedEndMin(), pol.getExtraChannelIds(), pol.getBlockedChannelIds()));
                    }),
                new Sheets.Item(R.drawable.ic_moon, I18n.get(lang, "kids.schedule_label"), schedTxt + "  ·  ◀ ▶", null)
                    .adjust(dir -> {
                        int[][] w = {{0, 360, 1140}, {1, 360, 1140}, {1, 360, 1200}, {1, 420, 1260}};
                        int cur = 0;
                        if (pol.getScheduleEnabled()) {
                            for (int i = 1; i < w.length; i++) if (w[i][1] == pol.getAllowedStartMin() && w[i][2] == pol.getAllowedEndMin()) { cur = i; break; }
                            if (cur == 0) cur = 2;
                        }
                        int ni = ((cur + dir) % w.length + w.length) % w.length;
                        Kids.setPolicy(h, pol.copy(pol.getDailyLimitMinutes(), w[ni][0] == 1, w[ni][1], w[ni][2], pol.getExtraChannelIds(), pol.getBlockedChannelIds()));
                    }));
            JSONObject rep = Kids.reportJson(h);
            int watchedMin = rep.optInt("watchedMinutes", 0);
            String repText = pol.getDailyLimitMinutes() > 0
                    ? I18n.get(lang, "kids.report_today", watchedMin, pol.getDailyLimitMinutes())
                    : I18n.get(lang, "kids.report_no_limit", watchedMin);
            add(new Sheets.Item(R.drawable.ic_info, I18n.get(lang, "kids.report_title"), repText, null).wide());
        }

        private void addAllowedAppsRows(Home h, String lang) {
            List<Sheets.Item> row = new ArrayList<>();
            for (Home.App a : h.all) {
                boolean on = Kids.appAllowed(h, a.pkg);
                row.add(new Sheets.Item(on ? R.drawable.ic_play : R.drawable.ic_lock, a.label,
                        I18n.get(lang, on ? "tv.kids_allowed" : "tv.kids_locked"), () -> {
                    Kids.setApp(h, a.pkg, !Kids.appAllowed(h, a.pkg)); rebuild();
                }).on(on));
                if (row.size() == 2) { add(row.toArray(new Sheets.Item[0])); row.clear(); }
            }
            if (!row.isEmpty()) add(row.toArray(new Sheets.Item[0]));
        }
    }

    static final class PinPad extends Sheets.Grid {
        interface Done { void done(PinPad pad, String pin); }
        final String title; String hint; final Done done; final Runnable cancel;
        String pin = "";
        boolean error;
        String backupLabel;
        Runnable backupAction;

        PinPad(Home h, String title, String hint, Done done, Runnable cancel) {
            super(h, 400, Gravity.CENTER);
            this.title = title; this.hint = hint; this.done = done; this.cancel = cancel;
            setBackgroundColor(0xB3000000);
            rebuild();
        }

        void withBackup(String label, Runnable action) {
            this.backupLabel = label;
            this.backupAction = action;
            rebuild();
        }

        @Override void fill() {
            TextView ti = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT); ti.setText(title);
            TextView hi = Ui.text(getContext(), 14, 500, error ? 0xFFFF6B6B : Ui.TEXT_2); hi.setText(hint);
            ti.setPadding((int) (6 * d), 0, 0, 0); hi.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (12 * d));
            panel.addView(ti); panel.addView(hi);
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < 4; i++) dots.append(i < pin.length() ? "●" : "○").append(i < 3 ? "   " : "");
            TextView t = Ui.text(getContext(), 30, 600, Ui.TEXT); t.setText(dots); t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, (int) (10 * d));
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
            add(digit("1"), digit("2"), digit("3"));
            add(digit("4"), digit("5"), digit("6"));
            add(digit("7"), digit("8"), digit("9"));
            String cancelText = I18n.get(TvViewer.get(getContext()).getLanguage(), "common.cancel");
            add(new Sheets.Item(0, "⌫", null, this::erase), digit("0"), new Sheets.Item(0, cancelText, null, cancel));
            if (backupLabel != null && backupAction != null) {
                add(new Sheets.Item(0, backupLabel, null, backupAction).wide());
            }
        }

        Sheets.Item digit(String n) { return new Sheets.Item(0, n, null, () -> type(n)); }

        void type(String n) {
            if (pin.length() >= 4) return;
            pin += n;
            if (pin.length() < 4) { rebuild(); return; }
            String p = pin; pin = ""; rebuild();
            done.done(this, p);
        }

        void erase() { if (!pin.isEmpty()) { pin = pin.substring(0, pin.length() - 1); rebuild(); } }

        void fail(String why) {
            hint = why; error = true; pin = ""; rebuild();
            scroll.animate().translationX(d * 14).setDuration(50).withEndAction(() -> scroll.animate().translationX(-d * 10).setDuration(60)
                    .withEndAction(() -> scroll.animate().translationX(0).setDuration(60).start()).start()).start();
        }

        void status(String msg, boolean err) {
            hint = msg; error = err; pin = ""; rebuild();
        }

        @Override void render() {
            super.render();
            View line = (View) views.get(r).get(c).getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, line.getBottom() + (int) (16 * d) - scroll.getHeight())));
        }

        @Override View tile(Sheets.Item it) {
            if (it.icon != 0) return super.tile(it);
            TextView t = Ui.text(getContext(), it.title.length() == 1 ? 24 : 15, 600, Ui.TEXT);
            t.setText(it.title); t.setTag("t"); t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (52 * d));
            t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            t.setBackground(new GlassDrawable(Ui.dp(getContext(), 20), 0x1FFFFFFF, d));
            return t;
        }

        @Override public boolean key(KeyEvent e) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { cancel.run(); return true; }
                if (!isEnabled()) return true;
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { type(String.valueOf(k - KeyEvent.KEYCODE_0)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { erase(); return true; }
            } else if (!isEnabled()) return true;
            return super.key(e);
        }
    }

    static final class MathPad extends Sheets.Grid {
        final String title;
        final MathGate.Difficulty diff;
        final Runnable onSuccess, cancel;
        MathGate.Problem problem;
        String input = "";
        String statusMsg;
        boolean error;

        MathPad(Home h, String title, MathGate.Difficulty diff, Runnable onSuccess, Runnable cancel) {
            super(h, 420, Gravity.CENTER);
            this.title = title;
            this.diff = diff;
            this.onSuccess = onSuccess;
            this.cancel = cancel;
            this.problem = MathGate.generate(diff, null);
            setBackgroundColor(0xB3000000);
            rebuild();
            if (Kids.pinLockLeftMs(h) > 0) tickLock();
        }

        @Override void fill() {
            String lang = TvViewer.get(getContext()).getLanguage();
            TextView ti = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT);
            ti.setText(title == null || title.isEmpty() ? I18n.get(lang, "kids.math_title") : title);
            String hint = statusMsg != null ? statusMsg : I18n.get(lang, "kids.math_hint", problem.getQuestion());
            TextView hi = Ui.text(getContext(), 15, 600, error ? 0xFFFF6B6B : Ui.TEXT_2);
            hi.setSingleLine(false);
            hi.setText(hint);
            ti.setPadding((int) (6 * d), 0, 0, 0);
            hi.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (10 * d));
            panel.addView(ti);
            panel.addView(hi);

            TextView qv = Ui.text(getContext(), 28, 700, Ui.TEXT);
            qv.setText(problem.getQuestion().replace("?", input.isEmpty() ? "_" : input));
            qv.setGravity(Gravity.CENTER);
            qv.setPadding(0, (int) (4 * d), 0, (int) (12 * d));
            panel.addView(qv, new LinearLayout.LayoutParams(-1, -2));

            add(digit("1"), digit("2"), digit("3"));
            add(digit("4"), digit("5"), digit("6"));
            add(digit("7"), digit("8"), digit("9"));
            String cancelText = I18n.get(lang, "common.cancel");
            add(new Sheets.Item(0, "⌫", null, this::erase), digit("0"), new Sheets.Item(0, I18n.get(lang, "kids.math_submit"), null, this::submit));
            add(new Sheets.Item(0, cancelText, null, cancel).wide());
        }

        private Sheets.Item digit(String n) { return new Sheets.Item(0, n, null, () -> type(n)); }

        private void type(String n) {
            if (Kids.pinLockLeftMs(home) > 0 || input.length() >= 4) return;
            input += n;
            error = false;
            statusMsg = null;
            rebuild();
        }

        private void erase() {
            if (input.isEmpty()) return;
            input = input.substring(0, input.length() - 1);
            rebuild();
        }

        private void submit() {
            String lang = TvViewer.get(getContext()).getLanguage();
            if (Kids.pinLockLeftMs(home) > 0) { tickLock(); return; }
            if (MathGate.verify(problem, input)) {
                Kids.clearPinBackoff(home);
                home.closeSheet();
                onSuccess.run();
                return;
            }
            int sec = Kids.recordWrongPin(home);
            input = "";
            error = true;
            problem = MathGate.generate(diff, problem.getQuestion());
            statusMsg = I18n.get(lang, "kids.math_wrong") + " (" + sec + "s)";
            rebuild();
            scroll.animate().translationX(d * 14).setDuration(50).withEndAction(() -> scroll.animate().translationX(-d * 10).setDuration(60)
                    .withEndAction(() -> scroll.animate().translationX(0).setDuration(60).start()).start()).start();
            tickLock();
        }

        private void tickLock() {
            long left = Kids.pinLockLeftMs(home);
            String lang = TvViewer.get(getContext()).getLanguage();
            if (left <= 0) {
                setEnabled(true); setAlpha(1f);
                error = false; statusMsg = null; rebuild();
                return;
            }
            setEnabled(false); setAlpha(0.65f);
            long sec = (left + 999) / 1000;
            error = true;
            statusMsg = I18n.get(lang, "tv.home_pin_locked", String.valueOf(sec));
            rebuild();
            postDelayed(() -> { if (isAttachedToWindow()) tickLock(); }, Math.min(left, 1000L));
        }

        @Override View tile(Sheets.Item it) {
            if (it.icon != 0) return super.tile(it);
            TextView t = Ui.text(getContext(), it.title.length() <= 2 ? 22 : 15, 600, Ui.TEXT);
            t.setText(it.title); t.setTag("t"); t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (48 * d));
            t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            t.setBackground(new GlassDrawable(Ui.dp(getContext(), 20), 0x1FFFFFFF, d));
            return t;
        }

        @Override public boolean key(KeyEvent e) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { cancel.run(); return true; }
                if (!isEnabled()) return true;
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { type(String.valueOf(k - KeyEvent.KEYCODE_0)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { erase(); return true; }
            } else if (!isEnabled()) return true;
            return super.key(e);
        }
    }

    static final class KidsBlocked extends Sheets.Grid {
        final KidsPolicy.EvalResult eval;

        KidsBlocked(Home h, KidsPolicy.EvalResult eval) {
            super(h, 460, Gravity.CENTER);
            this.eval = eval;
            setBackgroundColor(0xD905060B);
            rebuild();
        }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            KidsPolicy.Config pol = Kids.policy(h);
            String body = eval.getReason() == KidsPolicy.BlockReason.OUTSIDE_SCHEDULE
                    ? I18n.get(lang, "kids.outside_hours_body",
                        KidsPolicy.formatClockMinutes(pol.getAllowedStartMin()),
                        KidsPolicy.formatClockMinutes(pol.getAllowedEndMin()))
                    : I18n.get(lang, "kids.time_up_body");
            header(I18n.get(lang, "kids.time_up_title"), body);
            add(new Sheets.Item(R.drawable.ic_clock, I18n.get(lang, "kids.extend_30m"), null, () ->
                    h.verifyAdult(I18n.get(lang, "kids.extend_30m"), () -> {
                        Kids.grantBonusMinutes(h, 30);
                        h.closeSheet();
                        h.toast("+30 min");
                    })).wide());
            add(new Sheets.Item(R.drawable.ic_users, I18n.get(lang, "kids.exit_profile"), null, () -> {
                h.closeSheet();
                h.showProfiles();
            }).wide());
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN) return true;
            return super.key(e);
        }
    }

    static final class Recap extends Sheets.Grid {
        Recap(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            File dir = new File(h.getFilesDir(), "recap");
            String prof = h.prefs.getString("profile", "main");
            WatchRecap.Summary s = WatchRecap.summarize(dir, prof, WatchRecap.currentMonth(), lang);
            header(I18n.get(lang, "recap.title"), s.getTagline());
            add(new Sheets.Item(R.drawable.ic_clock, I18n.get(lang, "recap.total_time"),
                    s.getTotalHoursDecimal() + " · " + I18n.get(lang, "recap.sessions", s.getSessionCount()), null),
                new Sheets.Item(R.drawable.ic_sparkles, I18n.get(lang, "recap.peak_hour"),
                    s.getPeakHour() >= 0 ? String.format(java.util.Locale.US, "%02d:00", s.getPeakHour()) : "—", null));
            for (int i = 0; i < s.getTopChannels().size(); i++) {
                WatchRecap.RankedItem it = s.getTopChannels().get(i);
                add(new Sheets.Item(R.drawable.ic_tv, "#" + (i + 1) + "  " + it.getName(), it.getMinutes() + " min", null).wide());
            }
            boolean on = WatchRecap.isEnabled(dir);
            add(new Sheets.Item(on ? R.drawable.ic_sparkles : R.drawable.ic_lock, I18n.get(lang, "recap.enable"),
                    I18n.get(lang, on ? "tv.cc_simple_on" : "tv.cc_simple_off"), () -> {
                WatchRecap.setEnabled(dir, !on);
                rebuild();
            }).on(on),
                new Sheets.Item(R.drawable.ic_trash_2, I18n.get(lang, "recap.clear"), null, () -> {
                    WatchRecap.clear(dir);
                    h.toast(I18n.get(lang, "recap.cleared"));
                    rebuild();
                }));
        }
    }
}
