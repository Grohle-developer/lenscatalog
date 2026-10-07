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
        // Every card-log line also goes to logcat (the simulator's tour reads it there).
        AppLog.sink = new AppLog.Sink() {
            public void line(String msg) { android.util.Log.i("LensCatalog", msg); }
        };
        // Init the card log first, so every later AppLog.i() is recorded.
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            AppLog.init(new java.io.File(ext, "AINTFILM"));
            AppLog.i("LensCatalog start");
        } catch (Throwable t) {
            // ignore
        }
        screen = new Screen();
        sony = new Sony();
        view = new MenuView(this, this, screen);
        view.setVersion(versionName());
        setContentView(view);
        final LensLog lensLog = new LensLog(this);
        Catalog catalog = Catalog.load(this);
        AppLog.i("catalogue: " + (catalog.userCopy ? "card" : "built-in") + ", "
                + catalog.lenses.size() + " lenses, " + catalog.brands.size() + " brands");
        view.init(catalog, new Store(this), sony, lensLog);
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
                        lensLog.startSession("electronic", lens, 0, 0, true, CardSeq.next(
                                android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
                    }
                }
                view.showStart(lens);
            }
        });
    }

    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Keys.logical(e.getScanCode(), e.getKeyCode());
        if (k == Keys.NONE) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // A held arrow or wheel repeats (long lists, the focal picker);
            // OK and MENU act once per press.
            if (e.getRepeatCount() > 0 && Keys.oneShot(k)) return true;
            view.onKey(k, e.getRepeatCount());
            return true;
        }
        return true;
    }

    public void onExit() {
        finish();
    }

    /** This build's versionName, shown on the home screen and in diagnostics. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    protected void onDestroy() {
        if (sony != null) sony.close();
        if (screen != null) screen.close();
        super.onDestroy();
    }
}
