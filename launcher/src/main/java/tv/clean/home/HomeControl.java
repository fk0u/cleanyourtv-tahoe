package tv.clean.home;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Smart-home control through Home Assistant's REST API (local, token-based).
 *  Google Home itself has no TV-usable API here; the TV's own Google Assistant (mic button) still handles
 *  "Hey Google" home commands, and this covers devices exposed through Home Assistant. */
final class HomeControl {
    static final String[] DOMAINS = {"light", "switch", "fan", "input_boolean", "climate"};

    static final class Device { String id, name, domain; boolean on; }

    static boolean enabled() { return !BuildConfig.HA_URL.isEmpty() && !BuildConfig.HA_TOKEN.isEmpty(); }

    static Map<String, String> auth() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + BuildConfig.HA_TOKEN);
        return h;
    }

    /** Blocking; call off the main thread. */
    static List<Device> devices() throws Exception {
        List<Device> out = new ArrayList<>();
        JSONArray states = new JSONArray(Net.request("GET", BuildConfig.HA_URL + "/api/states", null, auth()));
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.getJSONObject(i);
            String id = s.getString("entity_id"), domain = id.substring(0, id.indexOf('.'));
            if (!Arrays.asList(DOMAINS).contains(domain)) continue;
            Device d = new Device();
            d.id = id; d.domain = domain;
            d.name = s.optJSONObject("attributes") == null ? id : s.getJSONObject("attributes").optString("friendly_name", id);
            d.on = !"off".equals(s.optString("state")) && !"unavailable".equals(s.optString("state"));
            out.add(d);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static void toggle(Device d) throws Exception {
        Net.request("POST", BuildConfig.HA_URL + "/api/services/" + (d.domain.equals("climate") ? "climate" : "homeassistant") + "/toggle",
                new JSONObject().put("entity_id", d.id).toString(), auth());
        d.on = !d.on;
    }

    /** For the assistant: "matikan lampu ruang tamu" → best name match + on/off. Blocking. */
    static String command(String text) {
        if (!enabled()) return "Home Assistant belum diatur (homeassistant.url/token).";
        try {
            String t = text.toLowerCase(Locale.ROOT);
            boolean wantOn = !(t.contains("matikan") || t.contains("mati") || t.contains("off"));
            Device best = null; int score = 0;
            for (Device d : devices()) {
                int s = 0;
                for (String w : d.name.toLowerCase(Locale.ROOT).split("\\s+")) if (w.length() > 2 && t.contains(w)) s++;
                if (s > score) { score = s; best = d; }
            }
            if (best == null) return "Perangkat tidak ditemukan.";
            if (best.on != wantOn) toggle(best);
            return best.name + (wantOn ? " dinyalakan." : " dimatikan.");
        } catch (Exception e) { return "Gagal: " + e.getMessage(); }
    }
}
