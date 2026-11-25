package app.pausebreak;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

/** The breath, the picture and Open it. Shown over a paused app. */
public class PauseActivity extends Activity {
    static volatile boolean showing;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        show(i);
    }

    private void show(Intent i) {
        String q = i.getStringExtra("q");
        if (web != null) web.destroy();
        web = Web.make(this, q == null ? "" : q);
        setContentView(web);
    }

    @Override
    protected void onResume() { super.onResume(); showing = true; }

    @Override
    protected void onPause() { super.onPause(); showing = false; }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back means never mind
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
