package com.lenscatalog;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * LensCatalog: manual lens catalogue + EXIF injection + IBIS focal length.
 * Pure Java, no JNI. All Sony framework access goes through {@link Sony}
 * (reflection, graceful in the simulator); the catalogue is JSON
 * ({@link Catalog}); last-used and favourites live in {@link Store}.
 * UI is a d-pad driven native-Sony-menu style list ({@link MenuView}).
 */
public class MainActivity extends Activity implements MenuView.Listener {
    private MenuView view;
    private Sony sony;
    private Screen screen;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        screen = new Screen();
        sony = new Sony();
        view = new MenuView(this, this, screen);
        setContentView(view);
        final LensLog lensLog = new LensLog(this);
        view.init(Catalog.load(this), new Store(this), sony, lensLog);
        view.showChecking();
        // open the framework off the first draw; never block the UI thread long
        view.post(new Runnable() {
            public void run() {
                sony.open();
                screen.open(new Screen.Listener() {
                    public void onScreenChanged() { view.invalidate(); }
                }, new java.util.concurrent.Executor() {
                    public void execute(Runnable r) { view.post(r); }
                });
                String lens = sony.lensName();
                // Log an electronic-lens session if one is mounted (its EXIF
                // is written by the camera; the tagger will skip those photos).
                if (lens != null && lens.length() > 0) {
                    LensLog.Session cur = lensLog.current();
                    if (cur == null || !cur.electronic || !lens.equals(cur.displayName)) {
                        lensLog.startSession("electronic", lens, 0, 0, true);
                    }
                }
                view.showStart(lens);
            }
        });
    }

    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Keys.logical(e.getScanCode(), e.getKeyCode());
        if (k == Keys.NONE) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            view.onKey(k);
            return true;
        }
        return true;
    }

    public void onExit() {
        finish();
    }

    protected void onDestroy() {
        if (sony != null) sony.close();
        if (screen != null) screen.close();
        super.onDestroy();
    }
}
