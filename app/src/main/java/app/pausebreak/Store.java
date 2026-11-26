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
        // keep about two weeks, for "Your week"
        java.util.Set<String> keep = new java.util.HashSet<>();
        Calendar d = Calendar.getInstance();
        for (int i = 0; i < 16; i++) {
            keep.add(d.get(Calendar.YEAR) + "-" + (d.get(Calendar.MONTH) + 1) + "-" + d.get(Calendar.DAY_OF_MONTH));
            d.add(Calendar.DAY_OF_MONTH, -1);
        }
        for (String old : p.getAll().keySet()) {
            if (!old.startsWith("spent:")) continue;
            int j = old.indexOf(':', 6);
            if (j < 0 || !keep.contains(old.substring(6, j))) e.remove(old);
        }
        e.apply();
    }

    /** Time per app per day, as {"Y-M-D":{"pkg":ms}}. */
    static String week(Context c) {
        JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, ?> en : prefs(c).getAll().entrySet()) {
                String k = en.getKey();
                if (!k.startsWith("spent:") || !(en.getValue() instanceof Long)) continue;
                int j = k.indexOf(':', 6);
                if (j < 0) continue;
                String dk = k.substring(6, j), pkg = k.substring(j + 1);
                JSONObject day = out.optJSONObject(dk);
                if (day == null) { day = new JSONObject(); out.put(dk, day); }
                day.put(pkg, (Long) en.getValue());
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    /** The name you see for an app, from its short name. */
    static String appName(Context c, String sl) {
        JSONArray apps = state(c).optJSONArray("apps");
        if (apps != null) for (int i = 0; i < apps.length(); i++) if (slug(apps.optString(i)).equals(sl)) return apps.optString(i);
        return sl.isEmpty() ? sl : Character.toUpperCase(sl.charAt(0)) + sl.substring(1);
    }

    /** Minutes in a row before the long-stay note. 0 is off. */
    static int nudge(Context c) {
        return Math.max(0, state(c).optInt("nudge", 20));
    }

    static long spentMs(Context c, String pkg) {
        return prefs(c).getLong("spent:" + day() + ":" + pkg, 0);
    }

    /** Start of the hour that time t falls in. */
    static long hourStart(long t) {
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(t);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Time in an app this hour, for "use first, pause later". */
    static void addHour(Context c, String pkg, long start, long end) {
        long hs = hourStart(end);
        long ms = end - Math.max(start, hs);
        if (ms <= 0 || ms > 3600_000L) return;
        String k = "hour:" + hs + ":" + pkg;
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putLong(k, p.getLong(k, 0) + ms);
        String now = "hour:" + hs + ":";
        for (String old : p.getAll().keySet()) if (old.startsWith("hour:") && !old.startsWith(now)) e.remove(old);
        e.apply();
    }

    static long hourMs(Context c, String pkg) {
        return prefs(c).getLong("hour:" + hourStart(System.currentTimeMillis()) + ":" + pkg, 0);
    }

    private static JSONObject per(Context c, String slug) {
        JSONObject p = state(c).optJSONObject("per");
        return p == null ? null : p.optJSONObject("app:" + slug);
    }

    /** "Pause when it opens" is off for this app. */
    static boolean pauseOff(Context c, String slug) {
        JSONObject p = per(c, slug);
        return p != null && p.optBoolean("pauseOff", false);
    }

    /** Free minutes each hour before the pause shows. 0 means right away. */
    static int delay(Context c, String slug) {
        JSONObject p = per(c, slug);
        return p == null ? 0 : Math.max(0, p.optInt("delay", 0));
    }

    static int mins(String t) {
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    /** Same rules as the screens: a window can run past midnight, and its days count from the start day. */
    static boolean inWindow(JSONObject w, Calendar d) {
        int a = mins(w.optString("from")), b = mins(w.optString("to"));
        if (a < 0 || b < 0 || a == b) return false;
        int m = d.get(Calendar.HOUR_OF_DAY) * 60 + d.get(Calendar.MINUTE);
        int dow = d.get(Calendar.DAY_OF_WEEK) - 1;
        JSONArray ds = w.optJSONArray("days");
        boolean today = has(ds, dow), yesterday = has(ds, (dow + 6) % 7);
        return a < b ? (today && m >= a && m < b) : ((m >= a && today) || (m < b && yesterday));
    }

    private static boolean has(JSONArray ds, int v) {
        if (ds == null) return true;
        for (int i = 0; i < ds.length(); i++) if (ds.optInt(i, -1) == v) return true;
        return false;
    }

    static boolean closedNow(JSONArray list) {
        if (list == null) return false;
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            if (w != null && inWindow(w, now)) return true;
        }
        return false;
    }

    /** On a break, or in this app's closed hours. */
    static boolean appClosed(Context c, String slug) {
        JSONObject s = state(c);
        JSONObject b = s.optJSONObject("block");
        if (b != null && b.optLong("until") > System.currentTimeMillis()) return true;
        JSONObject cl = s.optJSONObject("closed");
        return cl != null && closedNow(cl.optJSONArray("app:" + slug));
    }

    /** Closed times for one part of an app (like Reels), or null. */
    static JSONArray partTimes(Context c, String slug, String part) {
        JSONObject ps = state(c).optJSONObject("parts");
        JSONObject p = ps == null ? null : ps.optJSONObject("app:" + slug);
        return p == null ? null : p.optJSONArray(part);
    }
}
