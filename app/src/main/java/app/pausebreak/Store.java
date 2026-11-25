package app.pausebreak;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/** Settings saved by the screens, read by the service. */
final class Store {
    private static final String P = "pause";
    private static JSONObject cached;
    private static String cachedRaw;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static synchronized void saveState(Context c, String json) {
        SharedPreferences.Editor e = prefs(c).edit().putString("state", json);
        try {
            JSONObject s = new JSONObject(json);
            JSONObject b = s.optJSONObject("block");
            if (b != null && b.optLong("until") > System.currentTimeMillis()) {
                // a block cancels any time already let in
                for (String k : prefs(c).getAll().keySet()) if (k.startsWith("allow:")) e.remove(k);
            }
        } catch (Exception ignored) { }
        e.apply();
    }

    static synchronized JSONObject state(Context c) {
        String raw = prefs(c).getString("state", "{}");
        if (!raw.equals(cachedRaw)) {
            try { cached = new JSONObject(raw); } catch (Exception ex) { cached = new JSONObject(); }
            cachedRaw = raw;
        }
        return cached;
    }

    static String slug(String n) {
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** package name -> slug, for every app on the pause list */
    static Map<String, String> guardedApps(Context c) {
        Map<String, String> out = new HashMap<>();
        JSONObject s = state(c);
        JSONArray apps = s.optJSONArray("apps");
        JSONObject pkgs = s.optJSONObject("pkgs");
        if (apps == null || pkgs == null) return out;
        for (int i = 0; i < apps.length(); i++) {
            String sl = slug(apps.optString(i));
            String pkg = pkgs.optString(sl, "");
            if (!pkg.isEmpty()) out.put(pkg, sl);
        }
        return out;
    }

    static String[] sites(Context c) {
        JSONArray a = state(c).optJSONArray("sites");
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i);
        return out;
    }

    static String pkgFor(Context c, String slug) {
        JSONObject pkgs = state(c).optJSONObject("pkgs");
        return pkgs == null ? "" : pkgs.optString(slug, "");
    }

    static void allow(Context c, String key, long until) {
        prefs(c).edit().putLong("allow:" + key, until).apply();
    }

    static long allowed(Context c, String key) {
        return prefs(c).getLong("allow:" + key, 0);
    }

    static String day() {
        Calendar d = Calendar.getInstance();
        return d.get(Calendar.YEAR) + "-" + (d.get(Calendar.MONTH) + 1) + "-" + d.get(Calendar.DAY_OF_MONTH);
    }

    static void addSpent(Context c, String pkg, long ms) {
        if (ms <= 0 || ms > 6 * 3600_000L) return;
        String k = "spent:" + day() + ":" + pkg;
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putLong(k, p.getLong(k, 0) + ms);
        String today = "spent:" + day() + ":";
        for (String old : p.getAll().keySet()) if (old.startsWith("spent:") && !old.startsWith(today)) e.remove(old);
        e.apply();
    }

    static long spentMin(Context c, String pkg) {
        return prefs(c).getLong("spent:" + day() + ":" + pkg, 0) / 60000;
    }
}
