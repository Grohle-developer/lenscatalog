package com.lenscatalog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole UI: a native-Sony-menu style list navigator (d-pad + centre,
 * control wheel, no touch). Drawn on a plain View: the canvas is 480 high and
 * as wide as the glass it ends up on (Screen.logicalWidth: 640 on the A7 II's
 * 4:3 LCD and finder), squeezed into the 640x480 framebuffer, so shapes stay
 * true on a 16:9 screen too.
 *
 * Screens: CHECKING -> HOME -> FAVORITES | MODELS -> CONFIRM -> TOAST
 * (auto-exit); MANUAL (form); ELENS (electronic lens: nothing to do).
 * LCCORR (lens-correction editor, from CONFIRM); DIAG; LOG.
 * Zooms go straight to CONFIRM with the wide end: no manual focal step
 * (Berto 2026-10-06).
 *
 * Look (0.3.0): a header with the screen's mark, its title and where you are
 * in it; rows with drawn marks, values on the right and a chevron where the
 * centre button goes deeper; the selection in Sony's orange; and a legend of
 * the keys at the bottom. Text is the bundled Roboto (assets/fonts): the
 * camera's own font has no stars, ticks or arrows (0.2.6), so every mark is
 * drawn as a shape. Every colour sits on one of the sixteen levels a channel
 * of the camera's RGBA4444 framebuffer: nothing is lost to banding there.
 */
final class MenuView extends View {
    interface Listener { void onExit(); }

    static final int ST_CHECKING = 0, ST_HOME = 1, ST_FAVORITES = 2, ST_MODELS = 3,
            ST_MANUAL = 4, ST_CONFIRM = 5, ST_TOAST = 6, ST_ELENS = 7, ST_LCCORR = 8,
            ST_DIAG = 9, ST_LOG = 10;

    // Palette: Sony's menu black and orange. Each value is #RGB with its digits
    // doubled, i.e. exactly one of the camera's 4-bit levels.
    private static final int BG = 0xFF000000, CARD = 0xFF111111, RULE = 0xFF222222,
            LINE = 0xFF333333, TEXT = 0xFFFFFFFF, TEXT2 = 0xFFAAAAAA, TEXT3 = 0xFF777777,
            ACCENT = 0xFFFF8800, ON_ACCENT = 0xFF000000, ON_ACCENT2 = 0xFF442200,
            TRACK_ON_ACCENT = 0xFFAA5500, OK = 0xFF55CC77, BAD = 0xFFFF5544, WARN = 0xFFFFBB33;

    // Layout of the 480-high canvas.
    private static final int H = 480, HEADER_H = 56, FOOTER_TOP = 436,
            BODY_TOP = HEADER_H + 4, BODY_BOTTOM = FOOTER_TOP - 4;
    private static final int ROW_H = 50, SECTION_H = 30, LENS_ROW_H = 62, LC_ROW_H = 37,
            DIAG_ROW_H = 44, LOG_ROW_H = 28, STEP_ROW_H = 64;
    /** Text sizes: title, row, secondary, small. Nothing under 15 px on a 3" screen. */
    private static final float T_TITLE = 23, T_ROW = 21, T_SUB = 17, T_SMALL = 15;

    private final Listener listener;
    private final Handler handler = new Handler();
    private final Screen screen;
    /** The canvas width (Screen.logicalWidth), worked out as the view is drawn. */
    private int logicalW = 640;

    private int state = ST_CHECKING;
    private Catalog catalog;
    private Store store;
    private Sony sony;
    private LensLog lensLog;
    private Context ctx;
    private String version = "";

    /** Rows of the current list screen. */
    private final List<Row> rows = new ArrayList<Row>();
    private int sel;
    /** First visible row (list scrolling). */
    private int top;
    private String title = "";
    private String eLensName;

    /** The lens being confirmed / applied. */
    private Catalog.Lens lens;
    private int chosenFocal;
    private String brand; // ST_MODELS

    // result window
    private static final int RES_OK = 0, RES_WARN = 1, RES_BUSY = 2;
    private int resKind;
    private String toastTitle = "", resNote;
    private List<String[]> resLines = new ArrayList<String[]>();
    private long toastStart, toastUntil;
    private static final long TOAST_MS = 2400;
    private final Runnable exitLater = new Runnable() {
        public void run() { listener.onExit(); }
    };

    // manual form state
    private int manFocal = 50, manApIdx = 0;
    private static final int MAN_FOCAL_MIN = 4, MAN_FOCAL_MAX = 1000;
    private static final String[] APS =
        { "—", "1.0", "1.2", "1.4", "1.7", "1.8", "2", "2.8", "3.5", "4", "5.6", "8", "11", "16", "22" };

    // lens-correction working copy for the lens being confirmed
    private boolean lcEnabled;
    private int[] lcLevels = new int[Store.LC_N];
    private int[][] lcRanges;
    private static final String[] LC_LABELS = {
        "lc_shading_w", "lc_shading_wm", "lc_shading_cr", "lc_shading_cb", "lc_shading_cm",
        "lc_chroma_r", "lc_chroma_b", "lc_dist", "lc_dist_m",
    };

    // marks, drawn
    private static final int I_NONE = -1, I_APERTURE = 0, I_STAR = 1, I_STAR_O = 2, I_CHECK = 3,
            I_CHEV = 4, I_LEFT = 5, I_RIGHT = 6, I_UP = 7, I_DOWN = 8, I_CENTER = 9, I_CLOCK = 10,
            I_PHOTO = 11, I_PENCIL = 12, I_INFO = 13, I_DOC = 14, I_POWER = 15, I_HALF = 16,
            I_SLIDERS = 17, I_RESET = 18, I_ALERT = 19, I_CROSS = 20;

    private static final class Row {
        final String label, sub;
        final int action;
        int icon = I_NONE;
        /** 0: the row's usual colour for its mark. */
        int iconColor;
        /** Lens rows: the lens itself (two models of one name, on two mounts, stay apart). */
        Catalog.Lens lens;
        /** Lens-correction rows: which of the 9 levels; -1 otherwise. */
        int index = -1;
        /** A section title: drawn, never selected. */
        boolean header;
        boolean chevron;
        /** A switch on the right: -1 none, 0 off, 1 on. */
        int sw = -1;
        /** Lens rows: in the favourites. */
        boolean fav;
        /** Diagnostics: 0 plain, 1 good, 2 bad, 3 not run. */
        int status;
        /** Where this row is, for coming back to it: "a<action>" or "b<brand>". */
        String key = "";

        Row(String label, String sub, int action) { this.label = label; this.sub = sub; this.action = action; }

        Row icon(int i) { icon = i; return this; }
        Row chev() { chevron = true; return this; }
        Row key(String k) { key = k; return this; }
    }

    private static Row section(String label, String right) {
        Row r = new Row(label, right, A_NOTHING);
        r.header = true;
        return r;
    }

    // row actions
    private static final int A_FAVORITES = 1, A_LAST = 2, A_BRAND = 3, A_MANUAL = 4,
            A_MODEL = 5, A_FAVLENS = 6, A_APPLY = 7, A_TOGGLEFAV = 8, A_NOTHING = 9,
            A_LCTOGGLE = 10, A_LCCORR = 11, A_LCRESET = 12, A_DIAG = 13, A_TAG = 14,
            A_LOG = 15, A_AUTOEXIT = 16, A_DUMP = 17;

    private final Typeface regular, medium;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();

    MenuView(Context ctx, Listener listener, Screen screen) {
        super(ctx);
        this.listener = listener;
        this.screen = screen;
        regular = face(ctx, "fonts/Roboto-Regular.ttf", Typeface.DEFAULT);
        medium = face(ctx, "fonts/Roboto-Medium.ttf", Typeface.DEFAULT_BOLD);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static Typeface face(Context ctx, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), asset);
        } catch (Throwable t) {
            return fallback;
        }
    }

    void init(Catalog catalog, Store store, Sony sony, LensLog lensLog) {
        this.catalog = catalog;
        this.store = store;
        this.sony = sony;
        this.lensLog = lensLog;
        this.ctx = getContext();
        logicalW = Screen.logicalWidth(screen.aspect, 640, 480, 480);
    }

    void setVersion(String v) { version = v == null ? "" : v; }

    // ------------------------------------------------------------ screens
    void showChecking() {
        state = ST_CHECKING;
        title = Text.get("title");
        rows.clear();
        invalidate();
    }

    /** eLens: "" = none attached; null = unknown; else its name. */
    void showStart(String eLens) {
        if (eLens != null && eLens.length() > 0) {
            state = ST_ELENS;
            eLensName = eLens;
            title = Text.get("title");
            rows.clear();
            invalidate();
            return;
        }
        showHome();
    }

    void showHome() {
        showHomeAt(null);
    }

    /** Home, with the row whose key is `at` selected (the first row when null or gone). */
    private void showHomeAt(String at) {
        state = ST_HOME;
        title = Text.get("title");
        rows.clear();
        top = 0;
        rows.add(section(Text.get("sec_quick"), ""));
        // Write EXIF first (Berto 2026-10-06: most-used action).
        rows.add(new Row(Text.get("tag_exif"), "", A_TAG).icon(I_PHOTO).key("a" + A_TAG));
        // the favourites this catalogue has (a card catalogue may lack some)
        int favs = 0;
        for (String id : store.favorites()) if (catalog.byId(id) != null) favs++;
        rows.add(new Row(Text.get("favorites"), String.valueOf(favs), A_FAVORITES)
                .icon(I_STAR).chev().key("a" + A_FAVORITES));
        String last = store.lastUsed();
        Catalog.Lens ll = last == null ? null : catalog.byId(last);
        if (ll != null) {
            Row r = new Row(Text.get("last_used"), ll.displayName(), A_LAST).icon(I_CLOCK).chev().key("a" + A_LAST);
            r.lens = ll;
            rows.add(r);
        }
        int nb = catalog.brands.size();
        rows.add(section(Text.get("sec_catalog"), Text.fmt(nb == 1 ? "n_brand" : "n_brands", String.valueOf(nb))));
        for (String b : catalog.brands) {
            int n = catalog.modelsOf(b).size();
            rows.add(new Row(b, String.valueOf(n), A_BRAND).chev().key("b" + b));
        }
        rows.add(section(Text.get("sec_tools"), ""));
        rows.add(new Row(Text.get("manual"), "", A_MANUAL).icon(I_PENCIL).chev().key("a" + A_MANUAL));
        rows.add(new Row(Text.get("diagnostics"), "", A_DIAG).icon(I_INFO).chev().key("a" + A_DIAG));
        rows.add(new Row(Text.get("view_log"), "", A_LOG).icon(I_DOC).chev().key("a" + A_LOG));
        Row ae = new Row(Text.get("auto_exit"), "", A_AUTOEXIT).icon(I_POWER).key("a" + A_AUTOEXIT);
        ae.sw = store.autoExit() ? 1 : 0;
        rows.add(ae);
        sel = firstSelectable();
        if (at != null) {
            for (int i = 0; i < rows.size(); i++) if (at.equals(rows.get(i).key)) sel = i;
        }
        ensureVisible();
        invalidate();
    }

    /** Diagnostic screen: which Sony EXIF/correction APIs exist on this body. */
    void showDiag() {
        state = ST_DIAG;
        title = Text.get("diagnostics");
        rows.clear();
        top = 0;
        rows.add(new Row(Text.get("store_dump"), "", A_DUMP).icon(I_DOC));
        rows.add(new Row(Text.get("app_version"), "LensCatalog " + version, A_NOTHING));
        String n = String.valueOf(catalog.lenses.size());
        rows.add(new Row(Text.get("catalog_src"),
                Text.fmt(catalog.userCopy ? "catalog_card" : "catalog_apk", n), A_NOTHING));
        for (String line : sony.diagnose()) {
            // "label: value" or "label=value"
            int c = line.indexOf(": "), e = line.indexOf('=');
            String label = line, value = "";
            if (c > 0 && (e < 0 || c < e)) { label = line.substring(0, c); value = line.substring(c + 2); }
            else if (e > 0) { label = line.substring(0, e); value = line.substring(e + 1); }
            Row r = new Row(label, value, A_NOTHING);
            r.status = statusOf(value);
            rows.add(r);
        }
        sel = 0;
        invalidate();
    }

    private static int statusOf(String v) {
        String u = v.toUpperCase();
        if (u.startsWith("EXISTS") || u.startsWith("OK") || u.startsWith("TRUE")) return 1;
        if (u.startsWith("MISSING") || u.startsWith("FAILED") || u.startsWith("FALSE")
                || u.startsWith("ERROR")) return 2;
        if (u.startsWith("SKIPPED")) return 3;
        return 0;
    }

    /** Show the last lines of the card log (AINTFILM.LOG), the newest selected. */
    void showLog() {
        state = ST_LOG;
        title = Text.get("log_title");
        rows.clear();
        top = 0;
        java.util.List<String> lines = AppLog.tail(40);
        for (String line : lines) rows.add(new Row(line, "", A_NOTHING));
        sel = rows.isEmpty() ? 0 : rows.size() - 1;
        ensureVisible();
        invalidate();
    }

    private void showFavorites() {
        showFavorites(null);
    }

    private void showFavorites(Catalog.Lens at) {
        state = ST_FAVORITES;
        title = Text.get("favorites");
        rows.clear();
        top = 0;
        for (String id : store.favorites()) {
            Catalog.Lens l = catalog.byId(id);
            if (l != null) rows.add(lensRow(l, l.displayName(), A_FAVLENS));
        }
        sel = indexOfLens(at);
        ensureVisible();
        invalidate();
    }

    private void showModels(String brand) {
        showModels(brand, null);
    }

    private void showModels(String brand, Catalog.Lens at) {
        this.brand = brand;
        state = ST_MODELS;
        title = brand;
        rows.clear();
        top = 0;
        for (Catalog.Lens l : catalog.modelsOf(brand))
            rows.add(lensRow(l, l.model, A_MODEL));
        sel = indexOfLens(at);
        ensureVisible();
        invalidate();
    }

    private Row lensRow(Catalog.Lens l, String label, int action) {
        Row r = new Row(label, specLine(l), action);
        r.lens = l;
        r.fav = store.isFavorite(l.id);
        r.chevron = true;
        return r;
    }

    private int indexOfLens(Catalog.Lens at) {
        if (at != null) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).lens == at) return i;
        }
        return 0;
    }

    private void showManual() {
        state = ST_MANUAL;
        title = Text.get("manual");
        rows.clear();
        top = 0;
        rows.add(new Row(Text.get("focal"), "", A_NOTHING));
        rows.add(new Row(Text.get("max_aperture"), "", A_NOTHING));
        rows.add(new Row(Text.get("apply"), "", A_APPLY).icon(I_CHECK));
        sel = 0;
        invalidate();
    }

    private void showConfirm(Catalog.Lens lens, int focal) {
        showConfirm(lens, focal, false);
    }

    /** keepSel: stay on the row just used (a toggle), instead of going back to Apply. */
    private void showConfirm(Catalog.Lens lens, int focal, boolean keepSel) {
        this.lens = lens;
        this.chosenFocal = focal;
        lcEnabled = store.lensCorrectionEnabled(lens.id);
        lcLevels = store.lensCorrectionLevels(lens.id);
        lcRanges = null; // queried lazily when the editor opens
        state = ST_CONFIRM;
        title = lens.brand;
        int was = sel;
        rows.clear();
        top = 0;
        rows.add(new Row(Text.get("apply"), "", A_APPLY).icon(I_CHECK));
        Row lc = new Row(Text.get("lens_correction"), "", A_LCTOGGLE).icon(I_HALF);
        lc.sw = lcEnabled ? 1 : 0;
        rows.add(lc);
        int set = 0;
        for (int v : lcLevels) if (v != 0) set++;
        rows.add(new Row(Text.get("lc_adjust"),
                set == 0 ? Text.get("lc_none") : Text.fmt("lc_count", String.valueOf(set)), A_LCCORR)
                .icon(I_SLIDERS).chev());
        if (!lens.manual) {
            boolean fav = store.isFavorite(lens.id);
            Row f = new Row(fav ? Text.get("in_fav") : Text.get("add_fav"), "", A_TOGGLEFAV)
                    .icon(fav ? I_STAR : I_STAR_O);
            if (fav) f.iconColor = ACCENT;
            rows.add(f);
        }
        sel = keepSel && was < rows.size() ? was : 0;
        invalidate();
    }

    /** Lens-correction editor: 9 level rows (◀ ▶ adjust, write-through to
     * the store) + reset. Ranges come from the camera when available. */
    private void showLcCorr() {
        state = ST_LCCORR;
        title = Text.get("lc_adjust");
        if (lcRanges == null) lcRanges = sony.lensCorrectionRanges();
        rows.clear();
        top = 0;
        for (int i = 0; i < Store.LC_N; i++) {
            Row r = new Row(Text.get(LC_LABELS[i]), "", A_NOTHING);
            r.index = i;
            rows.add(r);
        }
        rows.add(new Row(Text.get("lc_reset"), "", A_LCRESET).icon(I_RESET));
        sel = 0;
        invalidate();
    }

    private static String fmtLevel(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    /** f-number without trailing zeros: 2.0 -> "2", 2.8 -> "2.8", 0.95 -> "0.95". */
    static String fmtF(double f) {
        long c = Math.round(f * 100);
        String s = (c / 100) + "";
        long frac = c % 100;
        if (frac == 0) return s;
        if (frac % 10 == 0) return s + "." + (frac / 10);
        return s + "." + (frac < 10 ? "0" : "") + frac;
    }

    /** Result window: a mark, a title and labelled lines; debug text stays in logcat. */
    private void showResult(int kind, String title, List<String[]> lines, String note) {
        showResult(kind, title, lines, note, true);
    }

    /** mayExit: false for results that are no step of shooting (a store dump), which never auto-exit. */
    private void showResult(int kind, String title, List<String[]> lines, String note, boolean mayExit) {
        state = ST_TOAST;
        resKind = kind;
        toastTitle = title;
        resLines = lines == null ? new ArrayList<String[]>() : lines;
        resNote = note;
        handler.removeCallbacks(exitLater);
        // Auto-exit is configurable (Berto 2026-10-06): if disabled, the
        // result stays on screen until the user presses MENU. Work still
        // under way (tagging) never exits: its result does.
        if (mayExit && kind != RES_BUSY && store != null && store.autoExit()) {
            toastStart = System.currentTimeMillis();
            toastUntil = toastStart + TOAST_MS;
            handler.postDelayed(exitLater, TOAST_MS);
        } else {
            toastUntil = 0; // no auto-dismiss
        }
        invalidate();
    }

    /** Shorten s with … so it fits maxW pixels in paint p. API 10 safe. */
    private static String ellipsize(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        final String ell = "…";
        final float ew = p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s, 0, n) + ew > maxW) n--;
        return s.substring(0, n) + ell;
    }

    /** What a list row says under a lens's name: focal, aperture, mount. */
    private static String specLine(Catalog.Lens l) {
        StringBuilder s = new StringBuilder();
        if (l.isZoom()) s.append(Text.get("zoom").charAt(0)).append(Text.get("zoom").substring(1).toLowerCase())
                .append(" · ").append(l.focalMin).append('–').append(l.focalMax).append(" mm");
        else s.append(l.focal).append(" mm");
        if (l.maxAperture > 0) s.append(" · f/").append(fmtF(l.maxAperture));
        if (l.mount != null && l.mount.length() > 0) s.append(" · ").append(l.mount);
        return s.toString();
    }

    // ------------------------------------------------------------ keys
    void onKey(int k) {
        onKey(k, 0);
    }

    /** repeat: the key-repeat count of a held key (0 for a press). */
    void onKey(int k, int repeat) {
        if (state == ST_TOAST) {
            // If auto-exit is off, MENU (or OK) goes back to home; otherwise let it finish.
            // (a result with no count-down, such as a store dump, closes with MENU too)
            boolean closes = store != null && (!store.autoExit() || (toastUntil == 0 && resKind != RES_BUSY));
            if ((k == Keys.MENU || k == Keys.ENTER) && closes) {
                showHome();
                return;
            }
            return;
        }
        boolean editor = state == ST_MANUAL || state == ST_LCCORR;
        switch (k) {
            case Keys.UP: move(-1); return;
            case Keys.DOWN: move(1); return;
            case Keys.LEFT:
                if (editor) adjust(-step(repeat)); else page(-1);
                return;
            case Keys.RIGHT:
                if (editor) adjust(step(repeat)); else page(1);
                return;
            case Keys.WHEEL_CW:
                if (editor && adjustable()) adjust(1); else move(1);
                return;
            case Keys.WHEEL_CCW:
                if (editor && adjustable()) adjust(-1); else move(-1);
                return;
            case Keys.ENTER: enter(); return;
            case Keys.MENU: back(); return;
        }
    }

    /** A held arrow speeds up on the focal picker: 1 mm, then 5, then 10. */
    private int step(int repeat) {
        if (state != ST_MANUAL || sel != 0) return 1;
        return repeat < 8 ? 1 : repeat < 24 ? 5 : 10;
    }

    private boolean adjustable() {
        if (state == ST_MANUAL) return sel == 0 || sel == 1;
        if (state == ST_LCCORR) return sel >= 0 && sel < Store.LC_N;
        return false;
    }

    private void move(int d) {
        int n = rows.size();
        if (n == 0) return;
        int i = sel;
        for (int k = 0; k < n; k++) {
            i = (i + d + n) % n;
            if (!rows.get(i).header) {
                sel = i;
                break;
            }
        }
        ensureVisible();
        invalidate();
    }

    /** A page of a long list at once (◀ ▶), stopping at its ends. */
    private void page(int d) {
        if (!isList() || rows.isEmpty()) return;
        int per = Math.max(1, visibleRows() - 1), i = sel;
        for (int moved = 0; moved < per; moved++) {
            int j = i + d;
            while (j >= 0 && j < rows.size() && rows.get(j).header) j += d;
            if (j < 0 || j >= rows.size()) break;
            i = j;
        }
        if (i != sel) {
            sel = i;
            ensureVisible();
            invalidate();
        }
    }

    private boolean isList() {
        return state == ST_HOME || state == ST_FAVORITES || state == ST_MODELS
                || state == ST_DIAG || state == ST_LOG;
    }

    private void adjust(int d) {
        if (state == ST_MANUAL) {
            if (sel == 0) {
                manFocal += d;
                if (manFocal < MAN_FOCAL_MIN) manFocal = MAN_FOCAL_MIN;
                if (manFocal > MAN_FOCAL_MAX) manFocal = MAN_FOCAL_MAX;
                invalidate();
            } else if (sel == 1) {
                int s = d > 0 ? 1 : -1;
                manApIdx = (manApIdx + s + APS.length) % APS.length;
                invalidate();
            }
        } else if (state == ST_LCCORR) {
            if (sel >= 0 && sel < Store.LC_N && lcRanges != null) {
                int lo = lcRanges[sel][0], hi = lcRanges[sel][1];
                int v = lcLevels[sel] + (d > 0 ? 1 : -1);
                if (v < lo) v = lo;
                if (v > hi) v = hi;
                if (v != lcLevels[sel]) {
                    lcLevels[sel] = v;
                    store.setLensCorrection(lens.id, lcEnabled, lcLevels);
                    invalidate();
                }
            }
        }
    }

    private void enter() {
        switch (state) {
            case ST_ELENS:
                listener.onExit();
                return;
            case ST_MANUAL:
                if (sel == 2) {
                    Catalog.Lens m = new Catalog.Lens();
                    m.id = "manual:" + manFocal;
                    m.brand = "Manual";
                    m.model = manualModel();
                    m.mount = "";
                    m.notes = "";
                    m.type = "prime";
                    m.focal = manFocal;
                    m.manual = true;
                    if (manApIdx > 0) {
                        try { m.maxAperture = Double.parseDouble(APS[manApIdx]); }
                        catch (NumberFormatException e) { m.maxAperture = 0; }
                    }
                    confirmBack = ST_MANUAL;
                    showConfirm(m, manFocal);
                } else {
                    // OK on a field moves on to the next one, down to Apply
                    sel++;
                    invalidate();
                }
                return;
        }
        if (rows.isEmpty()) return;
        Row r = rows.get(sel);
        switch (r.action) {
            case A_FAVORITES: showFavorites(); break;
            case A_DIAG: showDiag(); break;
            case A_TAG: tagPhotos(); break;
            case A_LOG: showLog(); break;
            case A_DUMP: dumpStore(); break;
            case A_AUTOEXIT:
                store.setAutoExit(!store.autoExit());
                showHomeAt(r.key);
                break;
            case A_LAST: {
                Catalog.Lens l = catalog.byId(store.lastUsed());
                if (l != null) {
                    confirmBack = ST_HOME;
                    showConfirm(l, l.isZoom() ? l.focalMin : l.focal);
                }
                break;
            }
            case A_BRAND: showModels(r.label); break;
            case A_MANUAL: showManual(); break;
            case A_MODEL:
            case A_FAVLENS: {
                Catalog.Lens l = r.lens;
                if (l == null) break;
                confirmBack = r.action == A_FAVLENS ? ST_FAVORITES : ST_MODELS;
                // Zooms go straight to CONFIRM with the wide end (no manual
                // focal step: Berto 2026-10-06).
                showConfirm(l, l.isZoom() ? l.focalMin : l.focal);
                break;
            }
            case A_APPLY: apply(); break;
            case A_LCTOGGLE:
                if (lens != null) {
                    lcEnabled = !lcEnabled;
                    store.setLensCorrection(lens.id, lcEnabled, lcLevels);
                    showConfirm(lens, chosenFocal, true);
                }
                break;
            case A_LCCORR:
                if (lens != null) showLcCorr();
                break;
            case A_LCRESET:
                for (int i = 0; i < Store.LC_N; i++) lcLevels[i] = 0;
                store.setLensCorrection(lens.id, lcEnabled, lcLevels);
                invalidate();
                break;
            case A_TOGGLEFAV:
                if (lens != null && !lens.manual) {
                    store.toggleFavorite(lens.id);
                    showConfirm(lens, chosenFocal, true);
                }
                break;
        }
    }

    /** What SteadyShot is set to: the nearest focal it offers, and the lens's own when they differ. */
    private String ibisText() {
        int ibis = Sony.ibisFocal(chosenFocal);
        String s = ibis + " " + Text.get("mm");
        if (ibis != chosenFocal) s += " " + Text.fmt("ibis_from", String.valueOf(chosenFocal));
        if (lens != null && lens.isZoom()) s += " " + Text.get("wide_end");
        return s;
    }

    private String manualModel() {
        return manFocal + "mm" + (manApIdx == 0 ? "" : " f/" + APS[manApIdx]);
    }

    private void back() {
        switch (state) {
            case ST_HOME:
            case ST_ELENS:
            case ST_CHECKING:
                listener.onExit();
                break;
            case ST_FAVORITES:
                showHomeAt("a" + A_FAVORITES);
                break;
            case ST_MANUAL:
                showHomeAt("a" + A_MANUAL);
                break;
            case ST_DIAG:
                showHomeAt("a" + A_DIAG);
                break;
            case ST_LOG:
                showHomeAt("a" + A_LOG);
                break;
            case ST_MODELS:
                showHomeAt("b" + brand);
                break;
            case ST_CONFIRM:
                switch (confirmBack) {
                    case ST_FAVORITES: showFavorites(lens); break;
                    case ST_MODELS: showModels(brand, lens); break;
                    case ST_MANUAL: showManual(); break;
                    default: showHomeAt("a" + A_LAST); break;
                }
                break;
            case ST_LCCORR:
                if (lens != null) showConfirm(lens, chosenFocal);
                else showHome();
                break;
        }
    }

    /** Where MENU from ST_CONFIRM returns: one of ST_HOME/ST_FAVORITES/ST_MODELS/ST_MANUAL. */
    private int confirmBack = ST_HOME;

    /** The camera's settings store to the card (StoreDump), on a worker thread. */
    private void dumpStore() {
        if (!NativeStore.available()) {
            List<String[]> l = new ArrayList<String[]>();
            showResult(RES_WARN, Text.get("store_dump"), l, Text.get("store_camera_only"), false);
            return;
        }
        showResult(RES_BUSY, Text.get("store_dumping"), new ArrayList<String[]>(), null);
        final String header = sony.steadyShot();
        new Thread(new Runnable() {
            public void run() {
                final StoreDump.Result r = StoreDump.dump(new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), "AINTFILM"), header);
                post(new Runnable() {
                    public void run() {
                        List<String[]> l = new ArrayList<String[]>();
                        if (r.error == null) {
                            l.add(new String[] { Text.get("store_file"), "/AINTFILM/" + r.file, "ok" });
                            l.add(new String[] { Text.get("store_slots"), String.valueOf(r.slots), "" });
                        } else {
                            l.add(new String[] { Text.get("res_failed"), r.error, "bad" });
                        }
                        l.add(new String[] { Text.get("tag_time"), r.millis + " ms", "" });
                        if (header.length() > 0) l.add(new String[] { "SteadyShot", header.replace("SteadyShot ", ""), "" });
                        showResult(r.error == null ? RES_OK : RES_WARN, Text.get("store_dump"), l,
                                r.error == null ? Text.get("store_hint") : null, false);
                    }
                });
            }
        }).start();
    }

    /** Run the post-capture EXIF tagger on a worker thread. */
    private void tagPhotos() {
        List<String[]> wait = new ArrayList<String[]>();
        showResult(RES_BUSY, Text.get("tagging"), wait, Text.get("tagging_hint"));
        new Thread(new Runnable() {
            public void run() {
                final PhotoTagger.Result r = new PhotoTagger(ctx, lensLog).tagNewPhotos();
                post(new Runnable() {
                    public void run() {
                        List<String[]> lines = new ArrayList<String[]>();
                        lines.add(new String[] { Text.get("tag_tagged"), String.valueOf(r.tagged),
                                r.tagged > 0 ? "ok" : "" });
                        lines.add(new String[] { Text.get("tag_already"), String.valueOf(r.alreadyTagged), "" });
                        if (r.skippedElectronic > 0)
                            lines.add(new String[] { Text.get("tag_electronic"),
                                    String.valueOf(r.skippedElectronic), "" });
                        lines.add(new String[] { Text.get("tag_failed"), String.valueOf(r.failed),
                                r.failed > 0 ? "bad" : "" });
                        if (r.noSession > 0)
                            lines.add(new String[] { Text.get("tag_nosession"), String.valueOf(r.noSession), "warn" });
                        lines.add(new String[] { Text.get("tag_time"), r.millis + " ms", "" });
                        showResult(r.failed > 0 || (r.tagged == 0 && r.noSession > 0) ? RES_WARN : RES_OK,
                                Text.get("tag_done"), lines, r.failed > 0 ? Text.get("tag_failed_hint")
                                : r.noSession > 0 ? Text.get("tag_nosession_hint") : null);
                    }
                });
            }
        }).start();
    }

    private void apply() {
        if (lens == null) return;
        double ap = lens.maxAperture;
        boolean zoom = lens.isZoom();
        // Zooms: IBIS gets the wide end (safe direction: under-corrects if
        // the user zooms in afterwards); the numeric EXIF focal is skipped
        // (a range is not a rational; it travels in lensName instead).
        String done = sony.apply(lens.displayName(), chosenFocal, ap, !zoom, lcEnabled, lcLevels);
        if (done == null) done = "";
        if (!lens.manual) store.setLastUsed(lens.id);
        // Log the lens session for post-capture EXIF tagging. A zoom's
        // session has no focal (0): the tagger then leaves FocalLength alone,
        // as the pre-capture EXIF does.
        if (lensLog != null) {
            lensLog.startSession(lens.id, lens.displayName(), zoom ? 0 : chosenFocal, ap, false,
                    CardSeq.next(android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
        }
        boolean sim = !sony.isCamera();
        String ibis = ibisText();
        List<String[]> lines = new ArrayList<String[]>();
        lines.add(new String[] { Text.get("res_lens"), lens.displayName(), "" });
        boolean ibisOk = sim || done.indexOf("IBIS=") >= 0;
        lines.add(new String[] { Text.get("summary_ibis"),
                ibisOk ? ibis : ibis + " · " + Text.get("res_failed"), ibisOk ? "" : "bad" });
        if (done.indexOf("IBISOFF") >= 0)
            lines.add(new String[] { "SteadyShot", Text.get("ibis_off"), "warn" });
        if (sim) lines.add(new String[] { Text.get("summary_exif"), Text.get("res_sim"), "" });
        else if (done.indexOf("EXIF=ok") >= 0) lines.add(new String[] { Text.get("summary_exif"), Text.get("res_ok"), "ok" });
        else lines.add(new String[] { Text.get("summary_exif"), Text.get("res_unsupported"), "warn" });
        boolean lcOk = sim || done.indexOf("LC=") >= 0;
        lines.add(new String[] { Text.get("res_lc"),
                (lcEnabled ? Text.get("lc_on") : Text.get("lc_off")) + (lcOk ? "" : " · " + Text.get("res_failed")),
                lcOk ? (lcEnabled ? "on" : "") : "bad" });
        showResult(ibisOk && lcOk ? RES_OK : RES_WARN, Text.get("applied_title"), lines,
                sim ? Text.get("sim_note") : null);
    }

    // ------------------------------------------------------------ geometry of lists
    private int rowHeight() {
        switch (state) {
            case ST_FAVORITES: case ST_MODELS: return LENS_ROW_H;
            case ST_LCCORR: return LC_ROW_H;
            case ST_DIAG: return DIAG_ROW_H;
            case ST_LOG: return LOG_ROW_H;
            default: return ROW_H;
        }
    }

    private int heightOf(Row r) {
        return r.header ? SECTION_H : rowHeight();
    }

    private int visibleRows() {
        return Math.max(1, (BODY_BOTTOM - BODY_TOP) / rowHeight());
    }

    private int firstSelectable() {
        for (int i = 0; i < rows.size(); i++) if (!rows.get(i).header) return i;
        return 0;
    }

    /** Scroll so the selected row is in view (and the section title above it, when it starts one). */
    private void ensureVisible() {
        if (rows.isEmpty()) { top = 0; return; }
        if (sel < top) top = sel;
        if (top == sel && top > 0 && rows.get(top - 1).header) top--;
        while (top < sel && bottomOf(sel) > BODY_BOTTOM) top++;
    }

    private int bottomOf(int idx) {
        int y = BODY_TOP;
        for (int i = top; i <= idx && i < rows.size(); i++) y += heightOf(rows.get(i));
        return y;
    }

    private boolean overflows() {
        int h = 0;
        for (Row r : rows) h += heightOf(r);
        return h > BODY_BOTTOM - BODY_TOP;
    }

    // ------------------------------------------------------------ drawing: primitives
    private int W;

    private void font(Typeface t, float size, int color) {
        p.setTypeface(t);
        p.setTextSize(size);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private float tw(String s) { return p.measureText(s); }

    /** Text with its vertical centre at cy; align -1 left, 0 centre, 1 right. Returns its width. */
    private float text(Canvas c, String s, float x, float cy, int align) {
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = cy - (fm.ascent + fm.descent) / 2f;
        float w = tw(s);
        c.drawText(s, align < 0 ? x : align == 0 ? x - w / 2f : x - w, y, p);
        return w;
    }

    private String fit(String s, float max) {
        return ellipsize(p, s, Math.max(0, max));
    }

    private void fill(Canvas c, float l, float t, float r, float b, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRect(l, t, r, b, p);
    }

    private void round(Canvas c, float l, float t, float r, float b, float rad, int color, boolean filled,
            float stroke) {
        p.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas c, float x0, float y0, float x1, float y1, int color, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawLine(x0, y0, x1, y1, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** A mark, drawn in a square of side s at x, y (2 px strokes, like the camera's own). */
    private void icon(Canvas c, int kind, float x, float y, float s, int color) {
        float cx = x + s / 2f, cy = y + s / 2f, r = s / 2f - 1;
        float sw = Math.max(2f, s / 12f);
        p.setColor(color);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case I_APERTURE: {
                c.drawCircle(cx, cy, r, p);
                // six blades: from the inner hexagon out to the rim
                float ri = r * 0.42f;
                for (int k = 0; k < 6; k++) {
                    double a = Math.toRadians(k * 60 + 30), b = a + Math.toRadians(68);
                    c.drawLine(cx + (float) (ri * Math.cos(a)), cy + (float) (ri * Math.sin(a)),
                            cx + (float) (r * Math.cos(b)), cy + (float) (r * Math.sin(b)), p);
                }
                break;
            }
            case I_STAR: case I_STAR_O: {
                float r1 = r + 0.5f, r2 = r1 * 0.45f;
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = i % 2 == 0 ? r1 : r2;
                    float px = cx + (float) (rr * Math.cos(ang)), py = cy + 1 + (float) (rr * Math.sin(ang));
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                path.close();
                if (kind == I_STAR) p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case I_CHECK:
                p.setStrokeWidth(sw * 1.3f);
                path.moveTo(x + s * 0.14f, cy + s * 0.02f);
                path.lineTo(x + s * 0.4f, y + s * 0.78f);
                path.lineTo(x + s * 0.88f, y + s * 0.22f);
                c.drawPath(path, p);
                break;
            case I_CROSS:
                p.setStrokeWidth(sw * 1.3f);
                c.drawLine(x + s * 0.22f, y + s * 0.22f, x + s * 0.78f, y + s * 0.78f, p);
                c.drawLine(x + s * 0.78f, y + s * 0.22f, x + s * 0.22f, y + s * 0.78f, p);
                break;
            case I_CHEV:
                path.moveTo(x + s * 0.35f, y + s * 0.18f);
                path.lineTo(x + s * 0.68f, cy);
                path.lineTo(x + s * 0.35f, y + s * 0.82f);
                c.drawPath(path, p);
                break;
            case I_LEFT: case I_RIGHT: case I_UP: case I_DOWN: {
                float h = s * 0.36f;
                if (kind == I_UP) { path.moveTo(cx, cy - h); path.lineTo(cx - h, cy + h * 0.6f); path.lineTo(cx + h, cy + h * 0.6f); }
                else if (kind == I_DOWN) { path.moveTo(cx, cy + h); path.lineTo(cx - h, cy - h * 0.6f); path.lineTo(cx + h, cy - h * 0.6f); }
                else if (kind == I_LEFT) { path.moveTo(cx - h, cy); path.lineTo(cx + h * 0.6f, cy - h); path.lineTo(cx + h * 0.6f, cy + h); }
                else { path.moveTo(cx + h, cy); path.lineTo(cx - h * 0.6f, cy - h); path.lineTo(cx - h * 0.6f, cy + h); }
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case I_CENTER:
                c.drawCircle(cx, cy, r - 1, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.17f, p);
                break;
            case I_CLOCK:
                c.drawCircle(cx, cy, r - 1, p);
                c.drawLine(cx, cy, cx, cy - r * 0.55f, p);
                c.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, p);
                break;
            case I_PHOTO:
                rf.set(x + 1, y + s * 0.16f, x + s - 1, y + s * 0.86f);
                c.drawRoundRect(rf, 3, 3, p);
                path.moveTo(x + s * 0.12f, y + s * 0.78f);
                path.lineTo(x + s * 0.4f, y + s * 0.48f);
                path.lineTo(x + s * 0.58f, y + s * 0.66f);
                path.lineTo(x + s * 0.7f, y + s * 0.56f);
                path.lineTo(x + s * 0.88f, y + s * 0.76f);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x + s * 0.7f, y + s * 0.35f, s * 0.07f, p);
                break;
            case I_PENCIL:
                p.setStrokeWidth(sw * 1.6f);
                c.drawLine(x + s * 0.3f, y + s * 0.7f, x + s * 0.8f, y + s * 0.2f, p);
                p.setStrokeWidth(sw);
                path.moveTo(x + s * 0.2f, y + s * 0.8f);
                path.lineTo(x + s * 0.23f, y + s * 0.6f);
                path.lineTo(x + s * 0.4f, y + s * 0.77f);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(x + s * 0.12f, y + s * 0.92f, x + s * 0.88f, y + s * 0.92f, p);
                break;
            case I_INFO:
                c.drawCircle(cx, cy, r - 1, p);
                c.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.24f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - s * 0.2f, sw * 0.8f, p);
                break;
            case I_DOC:
                rf.set(x + s * 0.18f, y + 1, x + s * 0.82f, y + s - 1);
                c.drawRoundRect(rf, 2, 2, p);
                for (int k = 0; k < 3; k++) {
                    float ly = y + s * (0.32f + k * 0.18f);
                    c.drawLine(x + s * 0.32f, ly, x + s * (k == 2 ? 0.55f : 0.68f), ly, p);
                }
                break;
            case I_POWER:
                rf.set(cx - r + 2, cy - r + 3, cx + r - 2, cy + r - 1);
                c.drawArc(rf, -55, 290, false, p);
                c.drawLine(cx, y + 1, cx, cy, p);
                break;
            case I_HALF:
                c.drawCircle(cx, cy, r - 1, p);
                rf.set(cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1);
                p.setStyle(Paint.Style.FILL);
                c.drawArc(rf, 90, 180, true, p);
                break;
            case I_SLIDERS:
                for (int k = 0; k < 3; k++) {
                    float ly = y + s * (0.22f + k * 0.28f), kx = x + s * (k == 1 ? 0.66f : k == 0 ? 0.32f : 0.5f);
                    c.drawLine(x + 1, ly, x + s - 1, ly, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(kx, ly, s * 0.11f, p);
                    p.setStyle(Paint.Style.STROKE);
                }
                break;
            case I_RESET: {
                rf.set(cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2);
                c.drawArc(rf, -80, 300, false, p);
                // the arrowhead at the arc's start (top, pointing right)
                float ax = cx + 1, ay = cy - r + 2;
                path.moveTo(ax + s * 0.18f, ay);
                path.lineTo(ax - s * 0.06f, ay - s * 0.15f);
                path.lineTo(ax - s * 0.06f, ay + s * 0.15f);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            }
            case I_ALERT:
                path.moveTo(cx, y + 1);
                path.lineTo(x + s - 1, y + s - 2);
                path.lineTo(x + 1, y + s - 2);
                path.close();
                c.drawPath(path, p);
                c.drawLine(cx, y + s * 0.38f, cx, y + s * 0.62f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, y + s * 0.76f, sw * 0.7f, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    /** An ON/OFF switch, its right edge at `right`; returns its left edge. */
    private float drawSwitch(Canvas c, float right, float cy, boolean on, boolean selected, String label) {
        float w = 40, h = 22, l = right - w;
        int track = selected ? ON_ACCENT : on ? ACCENT : TEXT3;
        if (on) round(c, l, cy - h / 2, right, cy + h / 2, h / 2, track, true, 2);
        else round(c, l, cy - h / 2, right, cy + h / 2, h / 2, track, false, 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? (selected ? ACCENT : ON_ACCENT) : track);
        c.drawCircle(on ? right - h / 2 : l + h / 2, cy, on ? h / 2 - 4 : h / 2 - 5, p);
        font(medium, T_SMALL, selected ? ON_ACCENT : on ? ACCENT : TEXT3);
        float lw = text(c, label, l - 10, cy, 1);
        return l - 10 - lw;
    }

    /** A label in an outlined box; returns its right edge. */
    private float chip(Canvas c, float x, float cy, String s, int color) {
        font(medium, T_SMALL, color);
        float w = tw(s) + 18;
        round(c, x, cy - 13, x + w, cy + 13, 4, color, false, 2);
        font(medium, T_SMALL, color);
        text(c, s, x + 9, cy, -1);
        return x + w;
    }

    // ------------------------------------------------------------ drawing: chrome
    private boolean simulator() {
        return state != ST_CHECKING && sony != null && !sony.isCamera();
    }

    /** The header: the screen's mark, its title, and on the right where you are (and SIM in the simulator). */
    private void drawHeader(Canvas c, int mark, String t, String right) {
        float cy = HEADER_H / 2f - 1;
        float x = 16;
        if (mark != I_NONE) {
            icon(c, mark, x, cy - 13, 26, ACCENT);
            x += 38;
        }
        float rx = W - 16;
        if (right != null && right.length() > 0) {
            font(regular, T_SUB, TEXT2);
            rx -= text(c, fit(right, W * 0.42f), rx, cy, 1) + 14;
        }
        if (simulator()) {
            font(medium, 13, TEXT3);
            float w = tw("SIM") + 14;
            round(c, rx - w, cy - 11, rx, cy + 11, 4, TEXT3, false, 2);
            font(medium, 13, TEXT3);
            text(c, "SIM", rx - w + 7, cy, -1);
            rx -= w + 14;
        }
        font(medium, T_TITLE, TEXT);
        text(c, fit(t, rx - x), x, cy, -1);
        fill(c, 0, HEADER_H - 2, W, HEADER_H, LINE);
    }

    /** The legend across the bottom: key, word, key, word… as many as fit; `right` at the far right. */
    private void drawLegend(Canvas c, String right, String... items) {
        fill(c, 0, FOOTER_TOP, W, H, CARD);
        fill(c, 0, FOOTER_TOP, W, FOOTER_TOP + 1, RULE);
        float cy = FOOTER_TOP + (H - FOOTER_TOP) / 2f, x = 14;
        float rx = W - 14;
        if (right != null && right.length() > 0) {
            font(regular, T_SMALL, TEXT3);
            rx -= text(c, right, rx, cy, 1) + 16;
        }
        for (int i = 0; i + 1 < items.length; i += 2) {
            font(regular, 16, TEXT2);
            float need = keyWidth(items[i]) + 8 + tw(items[i + 1]);
            if (x + need > rx) break;
            x = keycap(c, x, cy, items[i]) + 8;
            font(regular, 16, TEXT2);
            x += text(c, items[i + 1], x, cy, -1) + 20;
        }
    }

    private static final float KEY_H = 24, KEY_ICON = 14;

    private int[] keyIcons(String spec) {
        if (spec.equals("updown")) return new int[] { I_UP, I_DOWN };
        if (spec.equals("leftright")) return new int[] { I_LEFT, I_RIGHT };
        if (spec.equals("center")) return new int[] { I_CENTER };
        return null;
    }

    private float keyWidth(String spec) {
        int[] icons = keyIcons(spec);
        if (icons != null) return 10 + (KEY_ICON + 2) * icons.length;
        font(medium, 12, TEXT);
        return tw(spec) + 14;
    }

    /** A key: its marks in a box, or its name in a box. Returns the right edge. */
    private float keycap(Canvas c, float x, float cy, String spec) {
        int[] icons = keyIcons(spec);
        float w = keyWidth(spec);
        round(c, x, cy - KEY_H / 2, x + w, cy + KEY_H / 2, 5, TEXT3, false, 2);
        if (icons != null) {
            float xx = x + 6;
            for (int k : icons) {
                icon(c, k, xx, cy - KEY_ICON / 2, KEY_ICON, TEXT);
                xx += KEY_ICON + 2;
            }
        } else {
            font(medium, 12, TEXT);
            text(c, spec, x + 7, cy, -1);
        }
        return x + w;
    }

    private void drawScrollbar(Canvas c) {
        int total = 0, before = 0;
        for (int i = 0; i < rows.size(); i++) {
            int h = heightOf(rows.get(i));
            if (i < top) before += h;
            total += h;
        }
        float t0 = BODY_TOP + 2, t1 = BODY_BOTTOM - 2, span = t1 - t0;
        float view = BODY_BOTTOM - BODY_TOP;
        float th = Math.max(24, span * view / total);
        float ty = t0 + (span - th) * Math.min(1f, before / (float) Math.max(1, total - view));
        round(c, W - 7, t0, W - 3, t1, 2, RULE, true, 1);
        round(c, W - 7, ty, W - 3, ty + th, 2, TEXT3, true, 1);
    }

    // ------------------------------------------------------------ drawing: screens
    protected void onDraw(Canvas c) {
        int lw = Screen.logicalWidth(screen.aspect, getWidth(), getHeight(), H);
        W = lw > 0 ? lw : logicalW;
        logicalW = W;
        c.save();
        if (getWidth() > 0 && getHeight() > 0) c.scale(getWidth() / (float) W, getHeight() / (float) H);
        try {
            c.drawColor(BG);
            switch (state) {
                case ST_CHECKING: drawChecking(c); break;
                case ST_ELENS: drawELens(c); break;
                case ST_TOAST: drawResult(c); break;
                case ST_CONFIRM: drawConfirm(c); break;
                case ST_MANUAL: drawManual(c); break;
                default: drawListScreen(c); break;
            }
        } finally {
            c.restore();
        }
    }

    private void drawChecking(Canvas c) {
        drawHeader(c, I_APERTURE, title, "");
        icon(c, I_APERTURE, W / 2f - 36, 150, 72, ACCENT);
        font(regular, 20, TEXT2);
        text(c, Text.get("checking"), W / 2f, 268, 0);
        drawLegend(c, version);
    }

    private void drawELens(Canvas c) {
        drawHeader(c, I_APERTURE, title, "");
        float x0 = 28, x1 = W - 28, t = 92, b = 340;
        round(c, x0, t, x1, b, 8, CARD, true, 1);
        icon(c, I_APERTURE, W / 2f - 26, t + 26, 52, ACCENT);
        font(regular, 18, TEXT2);
        text(c, Text.get("e_lens"), W / 2f, t + 112, 0);
        font(medium, 26, TEXT);
        text(c, fit(eLensName, x1 - x0 - 40), W / 2f, t + 152, 0);
        font(regular, T_SUB, TEXT2);
        text(c, fit(Text.get("e_lens_hint"), x1 - x0 - 40), W / 2f, t + 206, 0);
        drawLegend(c, "", "center", Text.get("lg_exit"), "MENU", Text.get("lg_exit"));
    }

    private String headerRight() {
        switch (state) {
            case ST_HOME: {
                int n = catalog.lenses.size();
                return Text.fmt(n == 1 ? "n_lens" : "n_lenses", String.valueOf(n));
            }
            case ST_LOG:
                return Text.fmt("log_lines", String.valueOf(rows.size()));
            case ST_LCCORR:
                return lens != null ? lens.displayName() : "";
            default:
                return rows.isEmpty() ? "" : position();
        }
    }

    /** "12 / 104": the selected row among the selectable ones. */
    private String position() {
        int n = 0, at = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).header) continue;
            n++;
            if (i == sel) at = n;
        }
        return at + " / " + n;
    }

    private int headerMark() {
        switch (state) {
            case ST_FAVORITES: return I_STAR;
            case ST_DIAG: return I_INFO;
            case ST_LOG: return I_DOC;
            case ST_LCCORR: return I_SLIDERS;
            default: return I_APERTURE;
        }
    }

    private void drawListScreen(Canvas c) {
        drawHeader(c, headerMark(), title, headerRight());
        boolean bar = overflows();
        float right = W - (bar ? 14 : 8);
        int y = BODY_TOP;
        for (int i = top; i < rows.size(); i++) {
            Row r = rows.get(i);
            int h = heightOf(r);
            if (y + h > BODY_BOTTOM + 1) break;
            boolean s = i == sel;
            if (r.header) drawSection(c, r, y, h, right);
            else if (state == ST_FAVORITES || state == ST_MODELS) drawLensRow(c, r, s, y, h, right);
            else if (state == ST_LCCORR && r.index >= 0) drawLcRow(c, r, s, y, h, right);
            else if (state == ST_LOG) drawLogRow(c, r, s, y, h, right);
            else if (state == ST_DIAG && r.action == A_NOTHING) drawDiagRow(c, r, s, y, h, right);
            else drawMenuRow(c, r, s, y, h, right);
            // the three groups of the correction editor: shading, colour fringes, distortion
            if (state == ST_LCCORR && (r.index == 4 || r.index == 6 || r.index == 8) && sel != i && sel != i + 1)
                fill(c, 24, y + h - 1, right - 8, y + h, RULE);
            y += h;
        }
        if (bar) drawScrollbar(c);
        if (rows.isEmpty()) drawEmpty(c);
        drawListLegend(c);
    }

    private void drawEmpty(Canvas c) {
        if (state == ST_FAVORITES) {
            icon(c, I_STAR_O, W / 2f - 28, 140, 56, TEXT3);
            font(medium, 21, TEXT2);
            text(c, Text.get("no_favorites"), W / 2f, 236, 0);
            font(regular, T_SUB, TEXT3);
            text(c, fit(Text.get("no_favorites_hint"), W - 60), W / 2f, 270, 0);
        } else {
            font(regular, 19, TEXT3);
            text(c, Text.get("log_empty"), W / 2f, 230, 0);
        }
    }

    private void drawListLegend(Canvas c) {
        switch (state) {
            case ST_HOME:
                drawLegend(c, version.length() > 0 ? "v" + version : "", "updown", Text.get("lg_choose"),
                        "leftright", Text.get("lg_page"), "center", Text.get("lg_open"), "MENU", Text.get("lg_exit"));
                break;
            case ST_LCCORR:
                drawLegend(c, "", "updown", Text.get("lg_choose"), "leftright", Text.get("lg_adjust"),
                        "MENU", Text.get("lg_back"));
                break;
            case ST_DIAG: case ST_LOG:
                drawLegend(c, "", "updown", Text.get("lg_scroll"), "leftright", Text.get("lg_page"),
                        "MENU", Text.get("lg_back"));
                break;
            default:
                drawLegend(c, "", "updown", Text.get("lg_choose"), "leftright", Text.get("lg_page"),
                        "center", Text.get("lg_open"), "MENU", Text.get("lg_back"));
                break;
        }
    }

    private void drawSection(Canvas c, Row r, int y, int h, float right) {
        float cy = y + h / 2f + 3;
        font(medium, 14, ACCENT);
        float w = text(c, r.label.toUpperCase(), 24, cy, -1);
        if (r.sub.length() > 0) {
            font(regular, 14, TEXT3);
            text(c, r.sub, right - 12, cy, 1);
        }
        fill(c, 24 + w + 10, cy, right - (r.sub.length() > 0 ? 24 + tw(r.sub) : 12), cy + 1, RULE);
    }

    private void selection(Canvas c, int y, int h, float right) {
        round(c, 8, y + 2, right, y + h - 2, 6, ACCENT, true, 1);
    }

    /** A plain row: mark, label, value on the right, then a switch or a chevron. */
    private void drawMenuRow(Canvas c, Row r, boolean s, int y, int h, float right) {
        if (s) selection(c, y, h, right);
        float cy = y + h / 2f;
        float x = 24;
        if (r.icon != I_NONE) {
            icon(c, r.icon, 22, cy - 12, 24, s ? ON_ACCENT : r.iconColor != 0 ? r.iconColor : TEXT2);
            x = 62;
        }
        float rx = right - 14;
        if (r.chevron) {
            icon(c, I_CHEV, rx - 16, cy - 8, 16, s ? ON_ACCENT : TEXT3);
            rx -= 26;
        }
        if (r.sw >= 0) {
            String word = r.action == A_AUTOEXIT ? (r.sw == 1 ? Text.get("on") : Text.get("off"))
                    : (r.sw == 1 ? Text.get("lc_on") : Text.get("lc_off"));
            rx = drawSwitch(c, rx, cy, r.sw == 1, s, word) - 12;
        }
        font(s ? medium : regular, T_ROW, s ? ON_ACCENT : TEXT);
        float lw = tw(r.label);
        if (r.sub.length() > 0) {
            float room = rx - x;
            font(regular, 18, s ? ON_ACCENT2 : TEXT2);
            String sub = fit(r.sub, Math.max(room - lw - 20, room * 0.42f));
            rx -= text(c, sub, rx, cy, 1) + 18;
        }
        font(s ? medium : regular, T_ROW, s ? ON_ACCENT : TEXT);
        text(c, fit(r.label, rx - x), x, cy, -1);
    }

    /** A lens: its name, and under it focal, aperture and mount; a star when it is a favourite. */
    private void drawLensRow(Canvas c, Row r, boolean s, int y, int h, float right) {
        if (s) selection(c, y, h, right);
        float rx = right - 14;
        icon(c, I_CHEV, rx - 16, y + h / 2f - 8, 16, s ? ON_ACCENT : TEXT3);
        rx -= 28;
        if (r.fav) {
            icon(c, I_STAR, rx - 18, y + h / 2f - 9, 18, s ? ON_ACCENT : ACCENT);
            rx -= 28;
        }
        font(s ? medium : regular, 20, s ? ON_ACCENT : TEXT);
        text(c, fit(r.label, rx - 24), 24, y + 23, -1);
        font(regular, 16, s ? ON_ACCENT2 : TEXT2);
        text(c, fit(r.sub, rx - 24), 24, y + 45, -1);
    }

    /** A correction level: its name, a bar around zero, and the value. */
    private void drawLcRow(Canvas c, Row r, boolean s, int y, int h, float right) {
        if (s) selection(c, y, h, right);
        int i = r.index;
        int lo = lcRanges != null ? lcRanges[i][0] : -20, hi = lcRanges != null ? lcRanges[i][1] : 20;
        int v = lcLevels[i];
        float cy = y + h / 2f;
        float vx1 = right - 14, sx1 = vx1 - 52, sx0 = sx1 - Math.min(200, W * 0.32f);
        font(s ? medium : regular, 19, s ? ON_ACCENT : TEXT);
        text(c, fit(r.label, sx0 - 24 - 16), 24, cy, -1);
        float span = Math.max(1, hi - lo);
        float zx = sx0 + (sx1 - sx0) * Math.max(0, Math.min(1, (0 - lo) / span));
        float vx = sx0 + (sx1 - sx0) * Math.max(0, Math.min(1, (v - lo) / span));
        line(c, sx0, cy, sx1, cy, s ? TRACK_ON_ACCENT : LINE, 4);
        if (v != 0) line(c, zx, cy, vx, cy, s ? ON_ACCENT : ACCENT, 4);
        fill(c, zx - 1, cy - 7, zx + 1, cy + 7, s ? ON_ACCENT : TEXT3);
        p.setColor(s ? ON_ACCENT : v == 0 ? TEXT2 : ACCENT);
        c.drawCircle(vx, cy, 7, p);
        font(medium, 19, s ? ON_ACCENT : v == 0 ? TEXT2 : TEXT);
        text(c, fmtLevel(v), vx1, cy, 1);
    }

    private void drawLogRow(Canvas c, Row r, boolean s, int y, int h, float right) {
        if (s) {
            round(c, 8, y + 1, right, y + h - 1, 4, RULE, true, 1);
            fill(c, 8, y + 1, 11, y + h - 1, ACCENT);
        }
        float cy = y + h / 2f;
        String line = r.label, time = "", msg = line;
        // "yyyy-MM-dd HH:mm:ss.SSS  message"
        if (line.length() > 25 && line.charAt(4) == '-' && line.charAt(13) == ':') {
            time = line.substring(11, 19);
            msg = line.substring(23).trim();
        }
        float x = 20;
        if (time.length() > 0) {
            font(regular, T_SMALL, TEXT3);
            x += text(c, time, x, cy, -1) + 12;
        }
        font(regular, 16, s ? TEXT : 0xFFCCCCCC);
        text(c, fit(msg, right - 10 - x), x, cy, -1);
    }

    private void drawDiagRow(Canvas c, Row r, boolean s, int y, int h, float right) {
        if (s) selection(c, y, h, right);
        else fill(c, 24, y + h - 1, right - 8, y + h, RULE);
        float cy = y + h / 2f;
        float rx = right - 14;
        if (r.status != 0) {
            int col = r.status == 1 ? OK : r.status == 2 ? BAD : TEXT3;
            p.setStyle(Paint.Style.FILL);
            p.setColor(s ? ON_ACCENT : col);
            c.drawCircle(rx - 5, cy, 5, p);
            rx -= 18;
        }
        font(regular, 18, s ? ON_ACCENT : TEXT);
        float lw = Math.min(tw(r.label), (rx - 24) * 0.55f);
        String label = fit(r.label, lw + 1);
        text(c, label, 24, cy, -1);
        int col = r.status == 1 ? OK : r.status == 2 ? BAD : TEXT2;
        font(medium, 17, s ? ON_ACCENT : col);
        text(c, fit(r.sub, rx - 24 - lw - 20), rx, cy, 1);
    }

    /** The manual form: two pickers (◀ value ▶) and Apply, with what EXIF will say. */
    private void drawManual(Canvas c) {
        drawHeader(c, I_PENCIL, title, "");
        font(regular, T_SUB, TEXT2);
        text(c, fit(Text.get("manual_intro"), W - 48), 24, BODY_TOP + 20, -1);
        float right = W - 8;
        int y = BODY_TOP + 42;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean s = i == sel;
            if (i < 2) {
                int h = STEP_ROW_H;
                if (s) selection(c, y, h, right);
                float cy = y + h / 2f;
                font(s ? medium : regular, T_ROW, s ? ON_ACCENT : TEXT);
                text(c, r.label, 24, cy, -1);
                String v = i == 0 ? manFocal + " " + Text.get("mm")
                        : manApIdx == 0 ? Text.get("skip") : "f/" + APS[manApIdx];
                float bx1 = right - 16, bx0 = bx1 - 230;
                if (!s) round(c, bx0, cy - 21, bx1, cy + 21, 6, LINE, false, 2);
                icon(c, I_LEFT, bx0 + 8, cy - 10, 20, s ? ON_ACCENT : TEXT2);
                icon(c, I_RIGHT, bx1 - 28, cy - 10, 20, s ? ON_ACCENT : TEXT2);
                font(medium, 26, s ? ON_ACCENT : TEXT);
                text(c, v, (bx0 + bx1) / 2f, cy, 0);
                y += h;
            } else {
                y += 8;
                drawMenuRow(c, r, s, y, ROW_H, right);
                y += ROW_H;
            }
        }
        font(regular, 16, TEXT3);
        text(c, fit(Text.fmt("manual_preview", "Manual " + manualModel()), W - 48), 24, y + 24, -1);
        if (sel < 2) drawLegend(c, "", "updown", Text.get("lg_choose"), "leftright", Text.get("lg_adjust"),
                "MENU", Text.get("lg_back"));
        else drawLegend(c, "", "updown", Text.get("lg_choose"), "center", Text.get("apply"),
                "MENU", Text.get("lg_back"));
    }

    /** The lens's page: what it is, what Apply will set, and the actions. */
    private void drawConfirm(Canvas c) {
        drawHeader(c, I_APERTURE, title, "");
        float x0 = 12, x1 = W - 12, pad = 16;
        float y = BODY_TOP + 2;
        String name = lens.model;
        float room = x1 - x0 - pad * 2;
        // the name: one line from 26 px down to 21, else two lines at 21
        float size = 26;
        font(medium, size, TEXT);
        while (size > 21 && tw(name) > room) {
            size -= 1;
            font(medium, size, TEXT);
        }
        List<String> nameLines = new ArrayList<String>();
        if (tw(name) <= room) {
            nameLines.add(name);
        } else {
            int cut = name.length();
            while (cut > 0 && (name.charAt(cut - 1) != ' ' || tw(name.substring(0, cut - 1)) > room)) cut--;
            if (cut <= 1) cut = name.length() / 2 + 1;
            nameLines.add(name.substring(0, cut).trim());
            nameLines.add(fit(name.substring(cut).trim(), room));
        }
        boolean zoom = lens.isZoom();
        int specLines = 2 + (zoom ? 1 : 0);
        float lineH = size + 6;
        float cardH = 14 + nameLines.size() * lineH + 8 + 26 + 12 + specLines * 27 + 8;
        round(c, x0, y, x1, y + cardH, 8, CARD, true, 1);
        float ty = y + 14 + lineH / 2f;
        for (String s : nameLines) {
            font(medium, size, TEXT);
            text(c, s, x0 + pad, ty, -1);
            ty += lineH;
        }
        // chips: kind, focal, aperture, mount
        float cyChips = ty - lineH / 2f + 8 + 13;
        float cx = x0 + pad;
        cx = chip(c, cx, cyChips, zoom ? Text.get("zoom") : Text.get("prime"), zoom ? ACCENT : TEXT2) + 8;
        cx = chip(c, cx, cyChips, zoom ? lens.focalMin + "–" + lens.focalMax + " mm" : lens.focal + " mm", TEXT2) + 8;
        if (lens.maxAperture > 0) cx = chip(c, cx, cyChips, "f/" + fmtF(lens.maxAperture), TEXT2) + 8;
        if (lens.mount != null && lens.mount.length() > 0) chip(c, cx, cyChips, lens.mount, TEXT2);
        // what Apply sets
        float sy = cyChips + 13 + 12 + 13;
        float vx = x0 + pad + 64;
        font(medium, T_SMALL, TEXT3);
        text(c, Text.get("summary_ibis"), x0 + pad, sy, -1);
        font(regular, 18, TEXT);
        text(c, fit(ibisText(), x1 - pad - vx),
                vx, sy, -1);
        sy += 27;
        font(medium, T_SMALL, TEXT3);
        text(c, Text.get("summary_exif"), x0 + pad, sy, -1);
        font(regular, 18, TEXT);
        text(c, fit(lens.displayName() + (lens.maxAperture > 0 ? " · f/" + fmtF(lens.maxAperture) : ""),
                x1 - pad - vx), vx, sy, -1);
        if (zoom) {
            sy += 27;
            icon(c, I_ALERT, x0 + pad, sy - 9, 18, WARN);
            font(regular, 16, WARN);
            text(c, fit(Text.get("zoom_warn"), x1 - pad - x0 - pad - 28), x0 + pad + 28, sy, -1);
        }
        // the actions, in what is left
        int ry = (int) (y + cardH + 6);
        int n = rows.size();
        int h = Math.min(ROW_H, (BODY_BOTTOM - ry) / Math.max(1, n));
        for (int i = 0; i < n; i++) {
            drawMenuRow(c, rows.get(i), i == sel, ry, h, W - 8);
            ry += h;
        }
        drawLegend(c, "", "updown", Text.get("lg_choose"), "center", Text.get("lg_ok"),
                "MENU", Text.get("lg_back"));
    }

    /** Centered result card: a mark, a title, labelled lines, and the count-down to exit. */
    private void drawResult(Canvas c) {
        drawHeader(c, I_APERTURE, Text.get("title"), "");
        float cw = Math.min(W - 56, 560), x0 = (W - cw) / 2f, x1 = x0 + cw;
        float t = BODY_TOP + 12;
        int n = resLines.size();
        float b = t + 132 + n * 32 + (resNote != null ? 34 : 0) + 18;
        if (b > BODY_BOTTOM - 4) b = BODY_BOTTOM - 4;
        round(c, x0, t, x1, b, 10, CARD, true, 1);
        // the badge
        float bcx = W / 2f, bcy = t + 44, br = 27;
        p.setStyle(Paint.Style.FILL);
        if (resKind == RES_BUSY) {
            p.setColor(LINE);
            c.drawCircle(bcx, bcy, br, p);
            icon(c, I_PHOTO, bcx - 15, bcy - 15, 30, TEXT);
        } else {
            p.setColor(resKind == RES_OK ? ACCENT : WARN);
            c.drawCircle(bcx, bcy, br, p);
            icon(c, resKind == RES_OK ? I_CHECK : I_ALERT, bcx - 15, bcy - 15, 30, ON_ACCENT);
        }
        font(medium, 25, TEXT);
        text(c, fit(toastTitle, cw - 40), W / 2f, t + 100, 0);
        float y = t + 140, lx = x0 + 28, vx = x0 + Math.min(190, cw * 0.36f);
        for (String[] ln : resLines) {
            font(medium, T_SMALL, TEXT3);
            text(c, fit(ln[0].toUpperCase(), vx - lx - 10), lx, y, -1);
            String st = ln.length > 2 ? ln[2] : "";
            int col = st.equals("ok") ? OK : st.equals("bad") ? BAD : st.equals("warn") ? WARN
                    : st.equals("on") ? ACCENT : TEXT;
            font(regular, 19, col);
            text(c, fit(ln[1], x1 - 24 - vx), vx, y, -1);
            y += 32;
        }
        if (resNote != null) {
            font(regular, 16, TEXT2);
            text(c, fit(resNote, cw - 40), W / 2f, y + 4, 0);
        }
        // count-down to the auto-exit, along the card's foot
        long now = System.currentTimeMillis();
        if (toastUntil > now) {
            float f = (toastUntil - now) / (float) TOAST_MS;
            fill(c, x0 + 10, b - 5, x0 + 10 + (cw - 20) * Math.max(0, Math.min(1, f)), b - 2, ACCENT);
            postInvalidateDelayed(40);
        }
        if (resKind == RES_BUSY) drawLegend(c, "");
        else if (toastUntil > 0) drawLegend(c, Text.get("exit"));
        else drawLegend(c, "", "MENU", Text.get("lg_home"), "center", Text.get("lg_home"));
    }
}
