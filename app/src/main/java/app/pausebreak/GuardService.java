package app.pausebreak;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
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
    private int nudged = 0;
    private View note;
    private String lastShownKey = "";
    private long lastShownAt = 0;
    private long lastUrlCheck = 0;
    private long lastFrontCheck = 0;
    private long lastPartCheck = 0;

    /** Parts of apps you can close on their own, and how Pause spots each one on screen:
     *  bits of the screen's view names, and a bottom tab name that counts when that tab is selected. */
    private static final String[][] PARTS = {
            // package, part, view name bits (comma separated), tab name
            {"com.instagram.android", "reels", "clips_viewer,clips_video,root_clips_layout", "Reels"},
            {"com.instagram.android", "stories", "reel_viewer", ""},
            {"com.whatsapp", "status", "status_playback,status_viewer", "Updates"},
            {"com.whatsapp.w4b", "status", "status_playback,status_viewer", "Updates"},
            {"com.google.android.youtube", "shorts", "reel_recycler,reel_player,reel_watch,shorts_player", "Shorts"},
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkTimeUp();
            h.postDelayed(this, 5000);
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
        h.postDelayed(tick, 5000);
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

        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                && hasParts(pkg)) {
            long now = System.currentTimeMillis();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastPartCheck > 400) {
                lastPartCheck = now;
                checkParts(pkg);
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
            if (!fg.isEmpty()) {
                Store.addSpent(this, fg, now - fgSince);
                Store.addHour(this, fg, fgSince, now);
            }
            fg = pkg;
            fgSince = now;
            nudged = 0;
        }
        if (pkg.equals(getPackageName())) return;
        String sl = Store.guardedApps(this).get(pkg);
        if (sl != null) guardApp(pkg, sl, OPENED);
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

    private static final int OPENED = 0, TIME_UP = 1, FREE_USED = 2;

    /** Pause an app: when it opens, when the time you picked runs out, or when its free minutes are used. */
    private boolean guardApp(String pkg, String sl, int why) {
        long now = System.currentTimeMillis();
        boolean closed = Store.appClosed(this, sl);
        if (!closed && now < Store.allowed(this, "app:" + sl)) return false;
        if (!closed && why == OPENED) {
            if (Store.pauseOff(this, sl)) return false;
            int d = Store.delay(this, sl);
            if (d > 0 && hourUsed(pkg) < d * 60000L) return false; // free minutes left this hour
        }
        String q = "?app=" + Uri.encode(sl) + "&spent=" + spentNow(pkg) / 60000;
        if (!closed && why == TIME_UP) q += "&up=1";
        if (!closed && why == FREE_USED) q += "&free=" + Store.delay(this, sl);
        return show("app:" + sl, q);
    }

    /** Time in this app today, counting the visit going on now. */
    private long spentNow(String pkg) {
        long ms = Store.spentMs(this, pkg);
        if (pkg.equals(fg)) ms += System.currentTimeMillis() - fgSince;
        return ms;
    }

    /** Time in this app this hour, counting the visit going on now. */
    private long hourUsed(String pkg) {
        long ms = Store.hourMs(this, pkg);
        if (pkg.equals(fg)) {
            long now = System.currentTimeMillis();
            ms += now - Math.max(fgSince, Store.hourStart(now));
        }
        return ms;
    }

    private static boolean hasParts(String pkg) {
        for (String[] p : PARTS) if (p[0].equals(pkg)) return true;
        return false;
    }

    /** A closed part (like Reels) is on screen: go back out of it and say until when. */
    private long partHoldUntil = 0;

    private void checkParts(String pkg) {
        if (PauseActivity.showing || System.currentTimeMillis() < partHoldUntil) return;
        String sl = Store.guardedApps(this).get(pkg);
        if (sl == null) return;
        AccessibilityNodeInfo root = null;
        for (String[] p : PARTS) {
            if (!p[0].equals(pkg) || !Store.closedNow(Store.partTimes(this, sl, p[1]))) continue;
            if (root == null) {
                root = getRootInActiveWindow();
                if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;
            }
            if (onScreen(root, p[2].split(","), p[3])) {
                // Leave the part first, then show the page once the back has landed in the app.
                // Showing at once let the back press hit the page (or a second back fire) and the page got lost.
                partHoldUntil = System.currentTimeMillis() + 1500;
                performGlobalAction(GLOBAL_ACTION_BACK);
                final String key = "part:" + sl + ":" + p[1], q = "?app=" + Uri.encode(sl) + "&part=" + p[1];
                h.postDelayed(new Runnable() { public void run() { lastShownKey = ""; show(key, q); } }, 350);
                return;
            }
        }
    }

    private int seen;

    private boolean onScreen(AccessibilityNodeInfo root, String[] bits, String tab) {
        seen = 0;
        return look(root, bits, tab, 0);
    }

    private boolean look(AccessibilityNodeInfo n, String[] bits, String tab, int depth) {
        if (n == null || depth > 45 || ++seen > 2500) return false;
        String id = n.getViewIdResourceName();
        if (id != null && n.isVisibleToUser()) {
            int k = id.indexOf(":id/");
            String nm = k >= 0 ? id.substring(k + 4) : id;
            for (String b : bits) if (!b.isEmpty() && nm.contains(b)) return true;
        }
        if (!tab.isEmpty() && n.isVisibleToUser()) {
            CharSequence d = n.getContentDescription(), t = n.getText();
            boolean named = (d != null && startsWord(d.toString(), tab)) || (t != null && t.toString().trim().equalsIgnoreCase(tab));
            if (named) {
                AccessibilityNodeInfo up = n.getParent();
                if (n.isSelected() || (up != null && up.isSelected())) return true;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (look(n.getChild(i), bits, tab, depth + 1)) return true;
        }
        return false;
    }

    /** "Reels" or "Reels, tab 4 of 5", but not "Reels you might like". */
    private static boolean startsWord(String s, String w) {
        s = s.trim();
        if (!s.regionMatches(true, 0, w, 0, w.length())) return false;
        if (s.length() == w.length()) return true;
        char c = s.charAt(w.length());
        return c == ',' || c == '.' || c == ';';
    }

    private void checkSite(String barId) {
        String[] sites = Store.sites(this);
        Map<String, String> linked = Store.linkedSites(this);
        if (sites.length == 0 && linked.isEmpty()) return;
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
        // the website of a paused app closes while the app is closed (closed hours or a break)
        for (Map.Entry<String, String> e : linked.entrySet()) {
            String s = e.getKey();
            if (host.equals(s) || host.endsWith("." + s)) {
                if (Store.appClosed(this, e.getValue())) show("site:" + s, "?app=" + Uri.encode(e.getValue()) + "&via=" + Uri.encode(s));
                return;
            }
        }
    }

    /** While an app is open: pause again when the time you picked runs out, or when its free minutes are used. */
    private void checkTimeUp() {
        if (!fg.isEmpty() && !screenOn()) {
            // the phone is locked: stop counting until an app is in front again
            long now = System.currentTimeMillis();
            Store.addSpent(this, fg, now - fgSince);
            Store.addHour(this, fg, fgSince, now);
            fg = "";
            nudged = 0;
            return;
        }
        checkNudge();
        if (fg.isEmpty() || PauseActivity.showing) return;
        String sl = Store.guardedApps(this).get(fg);
        if (sl == null) return;
        long now = System.currentTimeMillis();
        if (Store.appClosed(this, sl)) { guardApp(fg, sl, OPENED); return; }
        if (Store.pauseOff(this, sl)) return;
        long until = Store.allowed(this, "app:" + sl);
        if (until > 0 && now >= until) {
            // the picked time is used up: it counts once, so free minutes work again next hour
            if (guardApp(fg, sl, TIME_UP)) Store.allow(this, "app:" + sl, 0);
            return;
        }
        int d = Store.delay(this, sl);
        if (d > 0 && now >= until && hourUsed(fg) >= d * 60000L) guardApp(fg, sl, FREE_USED);
    }

    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    /** After a while in a row in a paused app, a small note at the top that fades away. */
    private void checkNudge() {
        if (fg.isEmpty() || PauseActivity.showing) return;
        String sl = Store.guardedApps(this).get(fg);
        if (sl == null) return;
        int n = Store.nudge(this);
        if (n <= 0) return;
        long mins = (System.currentTimeMillis() - fgSince) / 60000L;
        if (mins < (long) n * (nudged + 1)) return;
        nudged = (int) (mins / n);
        showNote(nudged * n + " min on " + Store.appName(this, sl));
    }

    private void showNote(String text) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            if (note != null) { try { wm.removeView(note); } catch (Exception ignored) { } note = null; }
            float dp = getResources().getDisplayMetrics().density;
            TextView t = new TextView(this);
            t.setText(text);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            int px = (int) (18 * dp), py = (int) (10 * dp);
            t.setPadding(px, py, px, py);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEB141A2E);
            bg.setCornerRadius(22 * dp);
            t.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (48 * dp);
            t.setAlpha(0f);
            wm.addView(t, lp);
            note = t;
            t.animate().alpha(1f).setDuration(250).start();
            final View v = t;
            h.postDelayed(() -> v.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                try { wm.removeView(v); } catch (Exception ignored) { }
                if (note == v) note = null;
            }).start(), 4000);
        } catch (Exception ignored) { }
    }

    private boolean show(String key, String q) {
        long now = System.currentTimeMillis();
        if (PauseActivity.showing) return false;
        if (key.equals(lastShownKey) && now - lastShownAt < 1200 && PauseActivity.closedAt < lastShownAt) return false;
        lastShownKey = key;
        lastShownAt = now;
        Intent i = new Intent(this, PauseActivity.class)
                .putExtra("q", q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
        return true;
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
