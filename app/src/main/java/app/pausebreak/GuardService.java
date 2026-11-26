package app.pausebreak;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
    private long lastFrontCheck = 0;

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
        int type = e.getEventType();
        String pkg = e.getPackageName() == null ? null : e.getPackageName().toString();

        // Some apps open without a clear "new window" event (splash screens, coming back from
        // recents). So also look at which app is in front when the screen or its windows change.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg == null) {
            String front = frontPkg();
            if (front != null) onFront(front);
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (skip.contains(pkg) && !pkg.equals(getPackageName())) return;
            onFront(pkg);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !pkg.equals(fg)
                && !skip.contains(pkg) && Store.guardedApps(this).containsKey(pkg)) {
            // a paused app drew on screen before we heard it opened: check it's really in front
            long now = System.currentTimeMillis();
            if (now - lastFrontCheck > 250) {
                lastFrontCheck = now;
                String front = frontPkg();
                if (pkg.equals(front)) onFront(pkg);
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

    /** An app came to the front: count its time and pause it if it's one you picked. */
    private void onFront(String pkg) {
        if (skip.contains(pkg) && !pkg.equals(getPackageName())) return;
        boolean changed = !pkg.equals(fg);
        if (changed) {
            long now = System.currentTimeMillis();
            if (!fg.isEmpty()) Store.addSpent(this, fg, now - fgSince);
            fg = pkg;
            fgSince = now;
        }
        if (pkg.equals(getPackageName())) return;
        String sl = Store.guardedApps(this).get(pkg);
        if (sl != null) guardApp(pkg, sl);
    }

    /** The app that owns the window you're using now. */
    private String frontPkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                if (!skip.contains(p) || p.equals(getPackageName())) return p;
            }
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isActive() || w.isFocused())) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
            }
        } catch (Exception ignored) { }
        return null;
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
        if (key.equals(lastShownKey) && now - lastShownAt < 1200 && PauseActivity.closedAt < lastShownAt) return;
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
