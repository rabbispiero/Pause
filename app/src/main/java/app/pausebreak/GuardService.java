package app.pausebreak;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Watches which app is in front and shows the pause screen before a paused app or site. */
public class GuardService extends AccessibilityService {
    private static final String[][] BROWSERS = {
            {"com.android.chrome", "com.android.chrome:id/url_bar"},
            {"com.chrome.beta", "com.chrome.beta:id/url_bar"},
            {"com.brave.browser", "com.brave.browser:id/url_bar"},
            {"com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"},
            {"com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser:id/location_bar_edit_text"},
            {"org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"},
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> skip = new HashSet<>();
    private String fg = "";
    private long fgSince = 0;
    private String lastShownKey = "";
    private long lastShownAt = 0;
    private long lastUrlCheck = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkTimeUp();
            h.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onServiceConnected() {
        skip.add(getPackageName());
        skip.add("com.android.systemui");
        skip.add("android");
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            List<InputMethodInfo> list = im.getInputMethodList();
            for (InputMethodInfo i : list) skip.add(i.getPackageName());
        } catch (Exception ignored) { }
        h.postDelayed(tick, 15000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (skip.contains(pkg) && !pkg.equals(getPackageName())) return;
            if (!pkg.equals(fg)) {
                long now = System.currentTimeMillis();
                if (!fg.isEmpty()) Store.addSpent(this, fg, now - fgSince);
                fg = pkg;
                fgSince = now;
            }
            if (pkg.equals(getPackageName())) return;
            Map<String, String> apps = Store.guardedApps(this);
            String sl = apps.get(pkg);
            if (sl != null) {
                guardApp(pkg, sl);
                return;
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            String bar = urlBarId(pkg);
            if (bar != null) {
                long now = System.currentTimeMillis();
                if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastUrlCheck < 700) return;
                lastUrlCheck = now;
                checkSite(bar);
            }
        }
    }

    private void guardApp(String pkg, String sl) {
        if (System.currentTimeMillis() < Store.allowed(this, "app:" + sl)) return;
        show("app:" + sl, "?app=" + Uri.encode(sl) + "&spent=" + Store.spentMin(this, pkg));
    }

    private void checkSite(String barId) {
        String[] sites = Store.sites(this);
        if (sites.length == 0) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(barId);
        if (nodes == null || nodes.isEmpty()) return;
        AccessibilityNodeInfo bar = nodes.get(0);
        if (bar.isFocused()) return; // still typing
        CharSequence t = bar.getText();
        if (t == null) return;
        String host = host(t.toString());
        for (String s : sites) {
            if (s.isEmpty()) continue;
            if (host.equals(s) || host.endsWith("." + s)) {
                if (System.currentTimeMillis() < Store.allowed(this, "site:" + s)) return;
                show("site:" + s, "?site=" + Uri.encode(s));
                return;
            }
        }
    }

    /** When the let-in time runs out while the app is still open, pause again. */
    private void checkTimeUp() {
        if (fg.isEmpty() || PauseActivity.showing) return;
        String sl = Store.guardedApps(this).get(fg);
        if (sl == null) return;
        long until = Store.allowed(this, "app:" + sl);
        if (until > 0 && System.currentTimeMillis() >= until) guardApp(fg, sl);
    }

    private void show(String key, String q) {
        long now = System.currentTimeMillis();
        if (PauseActivity.showing) return;
        if (key.equals(lastShownKey) && now - lastShownAt < 2500) return;
        lastShownKey = key;
        lastShownAt = now;
        Intent i = new Intent(this, PauseActivity.class)
                .putExtra("q", q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
    }

    private static String urlBarId(String pkg) {
        for (String[] b : BROWSERS) if (b[0].equals(pkg)) return b[1];
        return null;
    }

    static String host(String v) {
        String s = v.trim().toLowerCase();
        s = s.replaceFirst("^[a-z]+://", "").replaceFirst("^www\\.", "");
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#', ' '}) {
            int k = s.indexOf(c);
            if (k >= 0 && k < cut) cut = k;
        }
        return s.substring(0, cut);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
