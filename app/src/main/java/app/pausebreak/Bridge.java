package app.pausebreak;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the screens can ask the phone to do. Named "Android" in the page. */
final class Bridge {
    private final Activity a;

    Bridge(Activity a) { this.a = a; }

    @JavascriptInterface
    public void save(String json) { Store.saveState(a, json); }

    @JavascriptInterface
    public String apps() {
        PackageManager pm = a.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(a.getPackageName())) continue;
            rows.add(new String[]{String.valueOf(r.loadLabel(pm)), pkg});
        }
        Collections.sort(rows, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        JSONArray out = new JSONArray();
        String last = "";
        for (String[] r : rows) {
            if (r[1].equals(last)) continue;
            last = r[1];
            try { out.put(new JSONObject().put("label", r[0]).put("pkg", r[1])); } catch (Exception ignored) { }
        }
        return out.toString();
    }

    /** The app's own icon as a small PNG data URL, or "" if it can't be read. */
    @JavascriptInterface
    public String icon(String pkg) {
        try {
            android.graphics.drawable.Drawable d = a.getPackageManager().getApplicationIcon(pkg);
            int px = 96;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            b.recycle();
            return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Let an app in until the given time, then open it. */
    @JavascriptInterface
    public void open(String slug, double until) {
        Store.allow(a, "app:" + slug, (long) until);
        String pkg = Store.pkgFor(a, slug);
        Intent i = pkg.isEmpty() ? null : a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        }
        done();
    }

    /** Back to an app without changing how long it's let in (after a closed part like Reels). */
    @JavascriptInterface
    public void launch(String slug) {
        String pkg = Store.pkgFor(a, slug);
        Intent i = pkg.isEmpty() ? null : a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        }
        done();
    }

    @JavascriptInterface
    public void openSite(String host, double until) {
        Store.allow(a, "site:" + host, (long) until);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        done();
    }

    @JavascriptInterface
    public void home() {
        a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        done();
    }

    @JavascriptInterface
    public String week() { return Store.week(a); }

    @JavascriptInterface
    public boolean serviceOn() { return on(a); }

    @JavascriptInterface
    public void look(final String v) {
        a.runOnUiThread(new Runnable() { public void run() { Web.paint(a, !"night".equals(v)); } });
    }

    @JavascriptInterface
    public void openAccessibility() {
        a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @JavascriptInterface
    public void openAppInfo() {
        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static boolean on(Activity a) {
        String list = Settings.Secure.getString(a.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String me = new ComponentName(a, GuardService.class).flattenToString();
        String shortMe = new ComponentName(a, GuardService.class).flattenToShortString();
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)) return true;
        return false;
    }

    private void done() {
        if (a instanceof PauseActivity) a.runOnUiThread(a::finish);
    }
}
