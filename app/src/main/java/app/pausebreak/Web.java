package app.pausebreak;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

/** Shows the pause screens from the app's own files, on a fixed https address so saved settings and pictures stay put. */
final class Web {
    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/index.html";
    static final int PICK = 41;

    static ValueCallback<Uri[]> pending;
    static final String DAY = "#F4F6FB", NIGHT = "#0D1226";

    /** Colors the window, status bar and navigation bar for the day or night look. */
    @SuppressWarnings("deprecation")
    static void paint(Activity a, boolean day) {
        try {
            Window win = a.getWindow();
            int c = Color.parseColor(day ? DAY : NIGHT);
            win.setBackgroundDrawable(new ColorDrawable(c));
            win.setStatusBarColor(c);
            win.setNavigationBarColor(c);
            View d = win.getDecorView();
            int f = d.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(day ? (f | light) : (f & ~light));
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ic = win.getInsetsController();
                if (ic != null) {
                    int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(day ? m : 0, m);
                }
            }
        } catch (Exception ignored) { }
    }

    static WebView make(final Activity a, String query) {
        WebView w = new WebView(a);
        boolean day = !"night".equals(Store.state(a).optString("look", "day"));
        w.setBackgroundColor(Color.parseColor(day ? DAY : NIGHT));
        paint(a, day);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        w.addJavascriptInterface(new Bridge(a), "Android");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = a.getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime(path), "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    a.startActivityForResult(Intent.createChooser(i, "Pictures"), PICK);
                } catch (Exception e) {
                    pending = null;
                    return false;
                }
                return true;
            }
        });
        w.loadUrl(BASE + (query == null ? "" : query));
        return w;
    }

    /** Call from the activity's onActivityResult. */
    static void picked(int req, int res, Intent data) {
        if (req != PICK || pending == null) return;
        Uri[] out = null;
        if (res == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        pending.onReceiveValue(out);
        pending = null;
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
