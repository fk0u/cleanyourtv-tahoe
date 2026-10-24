package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Voice assistant: speech → text (SpeechRecognizer) → Gemini with launcher tools → spoken + shown answer.
 *  Text can also arrive from the phone (companion web client uses the phone's own speech recognition). */
final class Assistant extends FrameLayout implements Sheet {
    final Orb orb;
    final TextView state, heard, answer;
    final Tools tools;
    final List<JSONObject> history = new ArrayList<>();
    SpeechRecognizer sr;
    TextToSpeech tts;
    boolean busy;

    static boolean enabled() { return !BuildConfig.GEMINI_API_KEY.isEmpty(); }

    Assistant(Context c, Tools tools) {
        super(c);
        this.tools = tools;
        float d = Ui.dp(c, 1);
        setBackgroundColor(0x99000000);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(new GlassDrawable(Ui.dp(c, 34), Ui.GLASS_STRONG, d));
        bar.setPadding((int) (22 * d), (int) (20 * d), (int) (28 * d), (int) (20 * d));
        orb = new Orb(c);
        bar.addView(orb, new LinearLayout.LayoutParams((int) (64 * d), (int) (64 * d)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding((int) (20 * d), 0, 0, 0);
        state = Ui.eyebrow(c, Ui.TEXT_3);
        heard = Ui.text(c, Ui.HEADLINE, 500, Ui.TEXT);
        heard.setSingleLine(false); heard.setMaxLines(2);
        answer = Ui.text(c, Ui.BODY + 1, 400, Ui.TEXT_2);
        answer.setSingleLine(false); answer.setMaxLines(5); answer.setLineSpacing(0, 1.2f);
        col.addView(state); col.addView(heard); col.addView(answer);
        bar.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        LayoutParams bl = new LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.leftMargin = bl.rightMargin = (int) (56 * d); bl.bottomMargin = (int) (40 * d);
        addView(bar, bl);
        tts = new TextToSpeech(c, s -> { if (s == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("id", "ID")); });
        if (!enabled()) {
            state.setText("Asisten belum aktif");
            heard.setText("Tambahkan gemini.apiKey di secrets.properties lalu build ulang.");
        } else listen();
    }

    void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            state.setText("Pengenal suara tidak tersedia di TV ini");
            heard.setText("Ucapkan lewat HP: buka web remote → tombol mic.");
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(getContext());
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { state.setText("Mendengarkan…"); orb.mode(1); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float db) { orb.level(db); }
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { orb.mode(2); }
                public void onError(int e) { state.setText("Tidak terdengar. OK untuk coba lagi"); orb.mode(0); }
                public void onPartialResults(Bundle b) { show(b); }
                public void onEvent(int t, Bundle b) {}
                public void onResults(Bundle b) { String s = show(b); if (s != null) ask(s); }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try { sr.startListening(i); } catch (Exception e) { state.setText("Mic tidak bisa dipakai: " + e.getMessage()); }
    }

    String show(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) return null;
        heard.setText(r.get(0));
        return r.get(0);
    }

    /** Entry point for typed or phone-dictated questions too. */
    void ask(String q) {
        if (!enabled() || busy) return;
        busy = true;
        heard.setText(q); answer.setText(""); state.setText("Berpikir…"); orb.mode(2);
        history.add(msg("user", new JSONArray().put(part("text", q))));
        Net.bg.execute(this::turn);
    }

    /** One Gemini round; loops while the model calls tools. */
    void turn() {
        try {
            for (int round = 0; round < 4; round++) {
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(part("text",
                                "Kamu asisten di TV rumah bernama Tahoe. Jawab singkat (maks 3 kalimat), santai, Bahasa Indonesia. "
                                        + "Pakai tools untuk membuka aplikasi, mencari di YouTube, cuaca, jadwal shalat, volume, wallpaper, dan perangkat rumah. "
                                        + "Kota: " + getContext().getString(R.string.city) + "."))))
                        .put("contents", new JSONArray(history))
                        .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations())));
                String js = Net.request("POST", "https://generativelanguage.googleapis.com/v1beta/models/" + BuildConfig.GEMINI_MODEL
                        + ":generateContent?key=" + BuildConfig.GEMINI_API_KEY, body.toString(), null);
                JSONObject content = new JSONObject(js).getJSONArray("candidates").getJSONObject(0).getJSONObject("content");
                history.add(content);
                JSONArray parts = content.getJSONArray("parts");
                JSONArray results = new JSONArray();
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (p.has("text")) text.append(p.getString("text"));
                    if (p.has("functionCall")) {
                        JSONObject fc = p.getJSONObject("functionCall");
                        String name = fc.getString("name");
                        JSONObject args = fc.optJSONObject("args") == null ? new JSONObject() : fc.getJSONObject("args");
                        String[] out = new String[1];
                        if (name.equals("home_control")) out[0] = HomeControl.command(args.optString("command")); // network: stay on this thread
                        else { // UI tools run on the main thread
                            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                            post(() -> { try { out[0] = tools.call(name, args); } finally { done.countDown(); } });
                            done.await();
                        }
                        results.put(new JSONObject().put("functionResponse", new JSONObject().put("name", name)
                                .put("response", new JSONObject().put("result", out[0] == null ? "ok" : out[0]))));
                    }
                }
                if (results.length() == 0) { String a = text.toString().trim(); post(() -> say(a)); return; }
                history.add(msg("user", results));
            }
        } catch (Exception e) {
            post(() -> { state.setText("Gagal menghubungi Gemini"); answer.setText(String.valueOf(e.getMessage())); orb.mode(0); busy = false; });
        }
    }

    void say(String a) {
        busy = false;
        state.setText("Tahoe"); answer.setText(a); orb.mode(3);
        if (tts != null) tts.speak(a.replaceAll("[*_#`]", ""), TextToSpeech.QUEUE_FLUSH, null, "a");
        while (history.size() > 12) history.remove(0); // keep context small
    }

    static JSONObject part(String k, String v) { try { return new JSONObject().put(k, v); } catch (Exception e) { return new JSONObject(); } }
    static JSONObject msg(String role, JSONArray parts) { try { return new JSONObject().put("role", role).put("parts", parts); } catch (Exception e) { return new JSONObject(); } }

    static JSONArray declarations() throws Exception {
        return new JSONArray()
                .put(fn("open_app", "Buka aplikasi di TV berdasarkan nama", "name", "Nama aplikasi, mis. Netflix"))
                .put(fn("youtube_search", "Cari dan tampilkan hasil di YouTube", "query", "Kata kunci"))
                .put(fn("weather", "Cuaca sekarang dan prakiraan hari ini", null, null))
                .put(fn("prayer_times", "Jadwal shalat hari ini dan shalat berikutnya", null, null))
                .put(fn("set_volume", "Atur volume (0-100) atau 'naik'/'turun'", "level", "0-100, naik, atau turun"))
                .put(fn("next_wallpaper", "Ganti wallpaper/foto latar", null, null))
                .put(fn("home_control", "Nyalakan/matikan perangkat rumah (lampu, colokan, kipas)", "command", "Mis. 'matikan lampu ruang tamu'"));
    }

    static JSONObject fn(String name, String desc, String arg, String argDesc) throws Exception {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (arg != null) f.put("parameters", new JSONObject().put("type", "OBJECT")
                .put("properties", new JSONObject().put(arg, new JSONObject().put("type", "STRING").put("description", argDesc)))
                .put("required", new JSONArray().put(arg)));
        return f;
    }

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) { if (!busy) listen(); return true; }
        return k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_BUTTON_B;
    }

    @Override public void onClose() {
        if (sr != null) { sr.destroy(); sr = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        orb.stop();
    }

    /** Gemini-style orb: rotating multi-colour gradient that breathes with the voice level. */
    static final class Orb extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        float t, lvl, mode;

        Orb(Context c) {
            super(c);
            anim.setDuration(3000); anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); lvl *= 0.9f; invalidate(); });
            anim.start();
        }

        void mode(int m) { mode = m; }
        void level(float db) { lvl = Math.max(lvl, Math.min(1, (db + 2) / 12f)); }
        void stop() { anim.cancel(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, base = Math.min(cx, cy);
            float pulse = mode == 2 ? (float) (0.08 * Math.sin(t * Math.PI * 8)) : lvl * 0.22f;
            float r = base * (0.72f + pulse);
            c.save(); c.rotate(t * 360, cx, cy);
            p.setShader(new SweepGradient(cx, cy, new int[]{0xFF4F8BFF, 0xFFA66BFF, 0xFFFF6FA8, 0xFFFFC46B, 0xFF4F8BFF}, null));
            c.drawCircle(cx, cy, r, p);
            c.restore();
            p.setShader(new RadialGradient(cx, cy - r * 0.3f, r, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }
    }
}
