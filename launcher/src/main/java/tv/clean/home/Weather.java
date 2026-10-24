package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Open-Meteo weather (no key): current, 24 h hourly and 7 days, cached for 30 minutes,
 *  plus the interactive full-screen sheet with a light animated sky. */
final class Weather {
    static final long TTL = 30 * 60_000L;

    static String url(Context c) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + c.getString(R.string.latitude) + "&longitude=" + c.getString(R.string.longitude)
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day,precipitation"
                + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=7&forecast_hours=24";
    }

    /** Fetch when stale; `done` runs on the caller's handler with the JSON (cached or fresh). */
    static void refresh(Context c, SharedPreferences p, boolean force, java.util.function.Consumer<JSONObject> done, android.os.Handler ui) {
        String cached = p.getString("weather2", null);
        if (cached != null) try { done.accept(new JSONObject(cached)); } catch (Exception ignored) {}
        if (!force && System.currentTimeMillis() - p.getLong("weather2_at", 0) < TTL) return;
        Net.bg.execute(() -> {
            try {
                String js = Net.get(url(c));
                p.edit().putString("weather2", js).putLong("weather2_at", System.currentTimeMillis()).apply();
                JSONObject o = new JSONObject(js);
                ui.post(() -> done.accept(o));
            } catch (Exception ignored) {}
        });
    }

    enum Sky { CLEAR, CLOUDS, FOG, RAIN, STORM }

    static Sky sky(int code) {
        if (code <= 1) return Sky.CLEAR;
        if (code <= 3) return Sky.CLOUDS;
        if (code <= 48) return Sky.FOG;
        if (code >= 95) return Sky.STORM;
        return Sky.RAIN;
    }

    static String glyph(int code, boolean day) {
        switch (sky(code)) {
            case CLEAR: return day ? "☀" : "☾";
            case CLOUDS: return code == 3 ? "☁" : "⛅";
            case FOG: return "≋";
            case STORM: return "⚡";
            default: return "☂";
        }
    }

    static String text(int c) {
        if (c == 0) return "Cerah";
        if (c <= 2) return "Cerah berawan";
        if (c == 3) return "Berawan";
        if (c <= 48) return "Berkabut";
        if (c <= 57) return "Gerimis";
        if (c <= 67) return "Hujan";
        if (c <= 77) return "Salju";
        if (c <= 82) return "Hujan lokal";
        return "Badai petir";
    }

    // ---- sheet ----

    /** Full-screen weather: big readout on the left that follows the selected hour, hourly strip, 7-day column. */
    static final class Panel extends FrameLayout implements tv.clean.home.Sheet {
        final JSONObject data;
        final Sky_View skyView;
        final TextView eyebrow, temp, desc, details;
        final LinearLayout hours;
        int sel = 0, count = 0;

        Panel(Context c, JSONObject data) {
            super(c);
            this.data = data;
            float d = Ui.dp(c, 1);
            setBackgroundColor(0xF00A0C14);
            JSONObject cur = data.optJSONObject("current");
            skyView = new Sky_View(c);
            addView(skyView, new LayoutParams(-1, -1));

            LinearLayout left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            eyebrow = Ui.eyebrow(c, Ui.TEXT_2);
            temp = Ui.text(c, 120, 200, Ui.TEXT);
            desc = Ui.text(c, Ui.HEADLINE, 500, Ui.TEXT);
            details = Ui.text(c, Ui.BODY, 400, Ui.TEXT_2);
            details.setSingleLine(false); details.setMaxLines(3); details.setLineSpacing(0, 1.25f);
            left.addView(eyebrow); left.addView(temp); left.addView(desc);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2); dl.topMargin = (int) (12 * d);
            left.addView(details, dl);
            LayoutParams ll = new LayoutParams((int) (520 * d), -2); ll.leftMargin = (int) (64 * d); ll.topMargin = (int) (56 * d); // fixed width: wrap_content clipped the big readout
            addView(left, ll);

            // 7 days, right column
            LinearLayout days = new LinearLayout(c); days.setOrientation(LinearLayout.VERTICAL);
            days.setBackground(new GlassDrawable(Ui.dp(c, Ui.R_LARGE), Ui.GLASS, d));
            days.setPadding((int) (20 * d), (int) (16 * d), (int) (20 * d), (int) (16 * d));
            TextView dh = Ui.eyebrow(c, Ui.TEXT_3); dh.setText("7 hari"); days.addView(dh);
            JSONObject daily = data.optJSONObject("daily");
            if (daily != null) {
                JSONArray t = daily.optJSONArray("time");
                for (int i = 0; t != null && i < t.length(); i++) {
                    LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, (int) (7 * d), 0, (int) (7 * d));
                    TextView day = Ui.text(c, Ui.BODY, i == 0 ? 600 : 400, Ui.TEXT);
                    day.setText(i == 0 ? "Hari ini" : dayName(t.optString(i)));
                    TextView g = Ui.text(c, Ui.BODY, 400, Ui.TEXT);
                    g.setText(glyph(daily.optJSONArray("weather_code").optInt(i), true));
                    TextView rng = Ui.text(c, Ui.BODY, 400, Ui.TEXT_2);
                    rng.setText(Math.round(daily.optJSONArray("temperature_2m_min").optDouble(i)) + "°  "
                            + Math.round(daily.optJSONArray("temperature_2m_max").optDouble(i)) + "°");
                    row.addView(day, new LinearLayout.LayoutParams((int) (110 * d), -2));
                    row.addView(g, new LinearLayout.LayoutParams((int) (40 * d), -2));
                    row.addView(rng);
                    days.addView(row);
                }
            }
            LayoutParams dlp = new LayoutParams((int) (300 * d), -2, Gravity.END | Gravity.TOP);
            dlp.rightMargin = (int) (56 * d); dlp.topMargin = (int) (56 * d);
            addView(days, dlp);

            // hourly strip, bottom
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false); hs.setClipToPadding(false);
            hours = new LinearLayout(c);
            hours.setPadding((int) (56 * d), (int) (12 * d), (int) (56 * d), (int) (12 * d));
            JSONObject hourly = data.optJSONObject("hourly");
            if (hourly != null) {
                JSONArray ht = hourly.optJSONArray("time");
                count = ht == null ? 0 : ht.length();
                for (int i = 0; i < count; i++) {
                    LinearLayout cell = new LinearLayout(c); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
                    TextView h = Ui.text(c, Ui.CAPTION + 1, 500, Ui.TEXT_2); h.setText(i == 0 ? "Kini" : ht.optString(i).substring(11, 16));
                    TextView g = Ui.text(c, 22, 400, Ui.TEXT);
                    g.setText(glyph(hourly.optJSONArray("weather_code").optInt(i), hourly.optJSONArray("is_day").optInt(i) == 1));
                    TextView tv = Ui.text(c, Ui.BODY, 500, Ui.TEXT); tv.setText(Math.round(hourly.optJSONArray("temperature_2m").optDouble(i)) + "°");
                    TextView pp = Ui.text(c, Ui.CAPTION, 500, Ui.ACCENT);
                    int prob = hourly.optJSONArray("precipitation_probability").optInt(i);
                    pp.setText(prob >= 20 ? prob + "%" : " ");
                    cell.addView(h); cell.addView(g); cell.addView(tv); cell.addView(pp);
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams((int) (72 * d), -2); cl.rightMargin = (int) (8 * d);
                    hours.addView(cell, cl);
                }
            }
            hs.addView(hours);
            LayoutParams hl = new LayoutParams(-1, -2, Gravity.BOTTOM); hl.bottomMargin = (int) (40 * d);
            addView(hs, hl);

            TextView hint = Ui.text(c, Ui.CAPTION, 500, Ui.TEXT_3);
            hint.setText("◀ ▶ pilih jam  ·  Back untuk menutup");
            LayoutParams hil = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hil.bottomMargin = (int) (14 * d);
            addView(hint, hil);
            select(0, cur);
        }

        static String dayName(String iso) {
            try { return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)); }
            catch (Exception e) { return iso; }
        }

        void select(int i, JSONObject cur) {
            sel = Math.max(0, Math.min(i, Math.max(0, count - 1)));
            for (int k = 0; k < hours.getChildCount(); k++)
                hours.getChildAt(k).setBackground(k == sel ? new GlassDrawable(Ui.dp(getContext(), Ui.R_MED), Ui.GLASS_FOCUS, Ui.dp(getContext(), 1)) : null);
            JSONObject hourly = data.optJSONObject("hourly");
            if (sel == 0 && cur != null) {
                int code = cur.optInt("weather_code");
                eyebrow.setText(getContext().getString(R.string.city) + " · sekarang");
                temp.setText(Math.round(cur.optDouble("temperature_2m")) + "°");
                desc.setText(text(code));
                details.setText("Terasa " + Math.round(cur.optDouble("apparent_temperature")) + "°  ·  Kelembapan " + cur.optInt("relative_humidity_2m")
                        + "%\nAngin " + Math.round(cur.optDouble("wind_speed_10m")) + " km/j  ·  UV " + uv());
                skyView.set(sky(code), cur.optInt("is_day", 1) == 1);
            } else if (hourly != null) {
                int code = hourly.optJSONArray("weather_code").optInt(sel);
                eyebrow.setText(getContext().getString(R.string.city) + " · pukul " + hourly.optJSONArray("time").optString(sel).substring(11, 16));
                temp.setText(Math.round(hourly.optJSONArray("temperature_2m").optDouble(sel)) + "°");
                desc.setText(text(code));
                details.setText("Peluang hujan " + hourly.optJSONArray("precipitation_probability").optInt(sel) + "%");
                skyView.set(sky(code), hourly.optJSONArray("is_day").optInt(sel) == 1);
            }
            View cell = hours.getChildAt(sel);
            if (cell != null) ((HorizontalScrollView) hours.getParent()).smoothScrollTo(Math.max(0, cell.getLeft() - (int) Ui.dp(getContext(), 200)), 0);
        }

        String uv() {
            JSONObject daily = data.optJSONObject("daily");
            return daily == null ? "-" : String.valueOf(Math.round(daily.optJSONArray("uv_index_max").optDouble(0)));
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: select(sel - 1, data.optJSONObject("current")); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: select(sel + 1, data.optJSONObject("current")); return true;
            }
            return false; // Back closes (handled by Home)
        }

        @Override public void onClose() { skyView.stop(); }
    }

    /** Cheap animated sky: drifting light blobs for clear/clouds, falling streaks for rain. ~30 fps, no bitmaps. */
    static final class Sky_View extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random rnd = new Random(7);
        final float[] drops = new float[90 * 3];
        Sky sky = Sky.CLEAR; boolean day = true;
        ValueAnimator anim;
        float t;

        Sky_View(Context c) {
            super(c);
            for (int i = 0; i < drops.length; i += 3) { drops[i] = rnd.nextFloat(); drops[i + 1] = rnd.nextFloat(); drops[i + 2] = 0.6f + rnd.nextFloat() * 0.8f; }
        }

        void set(Sky s, boolean d) {
            sky = s; day = d;
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0, 1); anim.setDuration(4000); anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new android.view.animation.LinearInterpolator());
                anim.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
                anim.start();
            }
            invalidate();
        }

        void stop() { if (anim != null) anim.cancel(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int top = day ? (sky == Sky.CLEAR ? 0xFF1E5BB8 : 0xFF3A4556) : 0xFF0B1024;
            int bottom = day ? (sky == Sky.CLEAR ? 0xFF6FA8E8 : 0xFF1B212C) : 0xFF1A1440;
            if (sky == Sky.STORM) { top = 0xFF1A1C24; bottom = 0xFF2B2440; }
            p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            double a = t * Math.PI * 2;
            if (sky == Sky.CLEAR) {
                float cx = w * 0.78f + (float) Math.sin(a) * w * 0.01f, cy = h * 0.22f;
                p.setShader(new RadialGradient(cx, cy, h * 0.55f, day ? 0x66FFE9A8 : 0x40B8C8FF, 0x00000000, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, h * 0.55f, p);
            } else {
                for (int i = 0; i < 3; i++) {
                    float cx = (float) ((i * 0.37 + t * (0.05 + i * 0.02)) % 1.2 - 0.1) * w, cy = h * (0.18f + i * 0.12f);
                    p.setShader(new RadialGradient(cx, cy, h * 0.35f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, h * 0.35f, p);
                }
            }
            if (sky == Sky.RAIN || sky == Sky.STORM) {
                p.setShader(null); p.setColor(0x59B8D8FF); p.setStrokeWidth(Ui.dp(getContext(), 1.4f));
                for (int i = 0; i < drops.length; i += 3) {
                    float x = drops[i] * w, y = ((drops[i + 1] + t * drops[i + 2] * 3) % 1f) * h, len = 18 * drops[i + 2] * getResources().getDisplayMetrics().density;
                    c.drawLine(x, y, x - len * 0.25f, y + len, p);
                }
                if (sky == Sky.STORM && t > 0.93f) { p.setColor(0x26FFFFFF); c.drawRect(0, 0, w, h, p); }
            }
            p.setShader(new LinearGradient(0, 0, w * 0.6f, 0, 0xB3000000, 0x00000000, Shader.TileMode.CLAMP)); // text legibility
            c.drawRect(0, 0, w, h, p);
        }
    }
}
