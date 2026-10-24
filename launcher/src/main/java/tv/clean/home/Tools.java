package tv.clean.home;

import org.json.JSONObject;

/** Launcher actions the assistant may call (Gemini function calling); runs on the main thread. */
interface Tools {
    String call(String name, JSONObject args);
}
