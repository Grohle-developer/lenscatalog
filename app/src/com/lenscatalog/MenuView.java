package com.lenscatalog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole UI: a native-Sony-menu style list navigator (d-pad + centre,
 * no touch). Drawn on a plain View at the framebuffer's 640x480; the layout
 * width follows Screen.logicalWidth so shapes stay true on 16:9 glass.
 *
 * Screens: CHECKING -> HOME -> FAVORITES | MODELS -> CONFIRM -> TOAST
 * (auto-exit); MANUAL (form); ELENS (electronic lens: nothing to do).
 * LCCORR (lens-correction editor, from CONFIRM).
 * Zooms go straight to CONFIRM with the wide end: no manual focal step
 * (Berto 2026-10-06).
 */
final class MenuView extends View {
    interface Listener { void onExit(); }

    static final int ST_CHECKING = 0, ST_HOME = 1, ST_FAVORITES = 2, ST_MODELS = 3,
            ST_MANUAL = 4, ST_CONFIRM = 5, ST_TOAST = 6, ST_ELENS = 7, ST_LCCORR = 8,
            ST_DIAG = 9;

    // Sony menu palette
    private static final int BLACK = 0xFF000000, WHITE = 0xFFFFFFFF, GRAY = 0xFF999999,
            DARK = 0xFF141414, ORANGE = 0xFFFF8C00, LINE = 0xFF333333;

    private final Listener listener;
    private final Handler handler = new Handler();
    private final Screen screen;
    private int logicalW = 640;

    private int state = ST_CHECKING;
    private Catalog catalog;
    private Store store;
    private Sony sony;

    /** Rows of the current list screen. */
    private final List<Row> rows = new ArrayList<Row>();
    private int sel;
    /** First visible row (list scrolling). */
    private int top;
    private static final int VISIBLE_ROWS = 6;
    private String title = "", hint = "";
    private String eLensName;

    /** The lens being confirmed / applied. */
    private Catalog.Lens lens;
    private int chosenFocal;
    private String brand; // ST_MODELS
    private String toastTitle = "", toastBody = "";
    private long toastUntil;

    // manual form state
    private int manFocal = 50, manApIdx = 0;
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

    private static final class Row {
        final String label, sub;
        final int action;
        Row(String label, String sub, int action) { this.label = label; this.sub = sub; this.action = action; }
    }

    // row actions
    private static final int A_FAVORITES = 1, A_LAST = 2, A_BRAND = 3, A_MANUAL = 4,
            A_MODEL = 5, A_FAVLENS = 6, A_APPLY = 7, A_TOGGLEFAV = 8, A_NOTHING = 9,
            A_LCTOGGLE = 10, A_LCCORR = 11, A_LCRESET = 12, A_DIAG = 13;

    private final Paint pTitle = new Paint(), pRow = new Paint(), pSub = new Paint(),
            pFoot = new Paint(), pSel = new Paint(), pSelT = new Paint(), pLine = new Paint(),
            pBoxFill = new Paint(), pBox = new Paint();

    MenuView(Context ctx, Listener listener, Screen screen) {
        super(ctx);
        this.listener = listener;
        this.screen = screen;
        pTitle.setColor(WHITE); pTitle.setTextSize(30); pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setAntiAlias(true);
        pRow.setColor(WHITE); pRow.setTextSize(26); pRow.setAntiAlias(true);
        pSub.setColor(GRAY); pSub.setTextSize(20); pSub.setAntiAlias(true);
        pFoot.setColor(GRAY); pFoot.setTextSize(20); pFoot.setAntiAlias(true);
        pSel.setColor(ORANGE);
        pSelT.setColor(BLACK); pSelT.setTextSize(26); pSelT.setTypeface(Typeface.DEFAULT_BOLD);
        pSelT.setAntiAlias(true);
        pLine.setColor(LINE); pLine.setStrokeWidth(2);
        pBoxFill.setColor(0xFF1A1A1A);
        pBox.setColor(GRAY); pBox.setStyle(Paint.Style.STROKE); pBox.setStrokeWidth(3);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void init(Catalog catalog, Store store, Sony sony) {
        this.catalog = catalog;
        this.store = store;
        this.sony = sony;
        logicalW = Screen.logicalWidth(screen.aspect, 640, 480, 480);
    }

    // ------------------------------------------------------------ screens
    void showChecking() {
        state = ST_CHECKING;
        title = Text.get("title");
        hint = "";
        rows.clear();
        invalidate();
    }

    /** eLens: "" = none attached; null = unknown; else its name. */
    void showStart(String eLens) {
        if (eLens != null && eLens.length() > 0) {
            state = ST_ELENS;
            eLensName = eLens;
            title = Text.get("title");
            hint = Text.get("list_hint");
            invalidate();
            return;
        }
        showHome();
    }

    void showHome() {
        state = ST_HOME;
        title = Text.get("title");
        hint = Text.get("list_hint");
        rows.clear();
        top = 0;
        rows.add(new Row("★ " + Text.get("favorites"),
                store.favorites().size() + " ", A_FAVORITES));
        String last = store.lastUsed();
        Catalog.Lens ll = last == null ? null : catalog.byId(last);
        if (ll != null) rows.add(new Row("" + Text.get("last_used"), ll.displayName(), A_LAST));
        for (String b : catalog.brands) {
            int n = catalog.modelsOf(b).size();
            rows.add(new Row(b, n + " ", A_BRAND));
        }
        rows.add(new Row("✎ " + Text.get("manual"), "", A_MANUAL));
        rows.add(new Row("? " + Text.get("diagnostics"), "", A_DIAG));
        sel = 0;
        invalidate();
    }

    /** Diagnostic screen: which Sony EXIF/correction APIs exist on this body. */
    void showDiag() {
        state = ST_DIAG;
        title = Text.get("diagnostics");
        hint = Text.get("list_hint");
        rows.clear();
        top = 0;
        for (String line : sony.diagnose()) {
            rows.add(new Row(line, "", A_NOTHING));
        }
        sel = 0;
        invalidate();
    }

    private void showFavorites() {
        state = ST_FAVORITES;
        title = "★ " + Text.get("favorites");
        hint = Text.get("list_hint");
        rows.clear();
        for (String id : store.favorites()) {
            Catalog.Lens l = catalog.byId(id);
            if (l != null) rows.add(new Row(l.displayName(), focalSub(l), A_FAVLENS));
        }
        sel = 0;
        invalidate();
    }

    private void showModels(String brand) {
        this.brand = brand;
        state = ST_MODELS;
        title = brand;
        hint = Text.get("list_hint");
        rows.clear();
        top = 0;
        for (Catalog.Lens l : catalog.modelsOf(brand))
            rows.add(new Row(l.model, focalSub(l), A_MODEL));
        sel = 0;
        invalidate();
    }

    private void showManual() {
        state = ST_MANUAL;
        title = "✎ " + Text.get("manual");
        hint = Text.get("list_hint");
        rows.clear();
        top = 0;
        rows.add(new Row(Text.get("focal") + ": " + manFocal + " " + Text.get("mm"), "", A_NOTHING));
        rows.add(new Row(Text.get("max_aperture") + ": "
                + (manApIdx == 0 ? Text.get("skip") : "f/" + APS[manApIdx]), "", A_NOTHING));
        rows.add(new Row("✔ " + Text.get("apply"), "", A_APPLY));
        sel = 0;
        invalidate();
    }

    private void showConfirm(Catalog.Lens lens, int focal) {
        this.lens = lens;
        this.chosenFocal = focal;
        lcEnabled = store.lensCorrectionEnabled(lens.id);
        lcLevels = store.lensCorrectionLevels(lens.id);
        lcRanges = null; // queried lazily when the editor opens
        state = ST_CONFIRM;
        title = lens.displayName();
        hint = Text.get("confirm_hint");
        rows.clear();
        rows.add(new Row("✔ " + Text.get("apply"), "", A_APPLY));
        rows.add(new Row("◐ " + Text.get("lens_correction"),
                lcEnabled ? Text.get("lc_on") : Text.get("lc_off"), A_LCTOGGLE));
        rows.add(new Row("✎ " + Text.get("lc_adjust"), "", A_LCCORR));
        if (!lens.manual) {
            boolean fav = store.isFavorite(lens.id);
            rows.add(new Row(fav ? "★ " + Text.get("in_fav") : "★ " + Text.get("add_fav"), "", A_TOGGLEFAV));
        }
        sel = 0;
        invalidate();
    }

    /** Lens-correction editor: 9 level rows (◀ ▶ adjust, write-through to
     * the store) + reset. Ranges come from the camera when available. */
    private void showLcCorr() {
        state = ST_LCCORR;
        title = Text.get("lc_adjust");
        hint = Text.get("lc_hint");
        if (lcRanges == null) lcRanges = sony.lensCorrectionRanges();
        rows.clear();
        top = 0;
        // value embedded in the label (single column): the selected row
        // would otherwise hide its sub, and long labels fit full-width
        for (int i = 0; i < Store.LC_N; i++)
            rows.add(new Row(Text.get(LC_LABELS[i]) + ": " + fmtLevel(lcLevels[i]), "", A_NOTHING));
        rows.add(new Row(Text.get("lc_reset"), "", A_LCRESET));
        sel = 0;
        invalidate();
    }

    private static String fmtLevel(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    private void refreshLcRow(int i) {
        rows.set(i, new Row(Text.get(LC_LABELS[i]) + ": " + fmtLevel(lcLevels[i]), "", A_NOTHING));
        invalidate();
    }

    /** Result window: clear labeled lines, no debug text (that stays in logcat). */
    private void showResult(String title, String body) {
        state = ST_TOAST;
        toastTitle = title;
        toastBody = body;
        toastUntil = System.currentTimeMillis() + 2400;
        invalidate();
        handler.postDelayed(new Runnable() {
            public void run() { listener.onExit(); }
        }, 2400);
    }

    /** Shorten s with … so it fits maxW pixels in paint p. API 10 safe. */
    private static String ellipsize(Paint p, String s, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        final String ell = "…";
        final float ew = p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s, 0, n) + ew > maxW) n--;
        return s.substring(0, n) + ell;
    }

    private static String focalSub(Catalog.Lens l) {
        if (l.isZoom()) return l.focalMin + "–" + l.focalMax + " mm";
        String s = l.focal + " mm";
        if (l.maxAperture > 0) s += " · f/" + l.maxAperture;
        if (l.mount.length() > 0) s += " · " + l.mount;
        return s;
    }

    // ------------------------------------------------------------ keys
    void onKey(int k) {
        if (state == ST_TOAST) return; // let it finish
        switch (k) {
            case Keys.UP: move(-1); return;
            case Keys.DOWN: move(1); return;
            case Keys.LEFT: adjust(-1); return;
            case Keys.RIGHT: adjust(1); return;
            case Keys.ENTER: enter(); return;
            case Keys.MENU: back(); return;
        }
    }

    private void move(int d) {
        if (rows.isEmpty()) return;
        sel = (sel + d + rows.size()) % rows.size();
        if (sel < top) top = sel;
        if (sel >= top + VISIBLE_ROWS) top = sel - VISIBLE_ROWS + 1;
        invalidate();
    }

    private void adjust(int d) {
        if (state == ST_MANUAL) {
            if (sel == 0) {
                manFocal += d;
                if (manFocal < 8) manFocal = 8;
                if (manFocal > 1000) manFocal = 1000;
                refreshManualLabels();
            } else if (sel == 1) {
                manApIdx = (manApIdx + d + APS.length) % APS.length;
                refreshManualLabels();
            }
        } else if (state == ST_LCCORR) {
            if (sel >= 0 && sel < Store.LC_N && lcRanges != null) {
                int lo = lcRanges[sel][0], hi = lcRanges[sel][1];
                int v = lcLevels[sel] + d;
                if (v < lo) v = lo;
                if (v > hi) v = hi;
                if (v != lcLevels[sel]) {
                    lcLevels[sel] = v;
                    store.setLensCorrection(lens.id, lcEnabled, lcLevels);
                    refreshLcRow(sel);
                }
            }
        }
    }

    private void refreshManualLabels() {
        rows.set(0, new Row(Text.get("focal") + ": " + manFocal + " " + Text.get("mm"), "", A_NOTHING));
        rows.set(1, new Row(Text.get("max_aperture") + ": "
                + (manApIdx == 0 ? Text.get("skip") : "f/" + APS[manApIdx]), "", A_NOTHING));
        invalidate();
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
                    m.model = manFocal + "mm" + (manApIdx == 0 ? "" : " f/" + APS[manApIdx]);
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
                }
                return;
        }
        if (rows.isEmpty()) return;
        Row r = rows.get(sel);
        switch (r.action) {
            case A_FAVORITES: showFavorites(); break;
            case A_DIAG: showDiag(); break;
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
                Catalog.Lens l = findLens(r);
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
                    showConfirm(lens, chosenFocal);
                }
                break;
            case A_LCCORR:
                if (lens != null) showLcCorr();
                break;
            case A_LCRESET:
                for (int i = 0; i < Store.LC_N; i++) lcLevels[i] = 0;
                store.setLensCorrection(lens.id, lcEnabled, lcLevels);
                showLcCorr();
                break;
            case A_TOGGLEFAV:
                if (lens != null && !lens.manual) {
                    store.toggleFavorite(lens.id);
                    showConfirm(lens, chosenFocal);
                }
                break;
        }
    }

    private Catalog.Lens findLens(Row r) {
        // match by display name within the current context
        List<Catalog.Lens> pool;
        if (state == ST_FAVORITES) {
            pool = new ArrayList<Catalog.Lens>();
            for (String id : store.favorites()) {
                Catalog.Lens l = catalog.byId(id);
                if (l != null) pool.add(l);
            }
        } else {
            pool = catalog.modelsOf(brand);
        }
        for (Catalog.Lens l : pool) {
            // ST_FAVORITES rows show displayName(); ST_MODELS rows show model only
            String label = state == ST_FAVORITES ? l.displayName() : l.model;
            if (label.equals(r.label)) return l;
        }
        return null;
    }

    private void back() {
        switch (state) {
            case ST_HOME:
            case ST_ELENS:
            case ST_CHECKING:
                listener.onExit();
                break;
            case ST_FAVORITES:
            case ST_MANUAL:
            case ST_DIAG:
                showHome();
                break;
            case ST_MODELS:
                showHome();
                break;
            case ST_CONFIRM:
                switch (confirmBack) {
                    case ST_FAVORITES: showFavorites(); break;
                    case ST_MODELS: showModels(brand); break;
                    case ST_MANUAL: showManual(); break;
                    default: showHome(); break;
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

    private void apply() {
        if (lens == null) return;
        double ap = lens.maxAperture;
        boolean zoom = lens.isZoom();
        // Zooms: IBIS gets the wide end (safe direction: under-corrects if
        // the user zooms in afterwards); the numeric EXIF focal is skipped
        // (a range is not a rational; it travels in lensName instead).
        sony.apply(lens.displayName(), chosenFocal, ap, !zoom, lcEnabled, lcLevels);
        if (!lens.manual) store.setLastUsed(lens.id);
        String ibis = chosenFocal + " " + Text.get("mm")
                + (zoom ? " " + Text.get("wide_end") : "");
        StringBuilder body = new StringBuilder();
        body.append(lens.displayName()).append('\n');
        body.append(Text.get("summary_ibis")).append(": ").append(ibis).append('\n');
        body.append(Text.get("summary_exif")).append(": ✓").append('\n');
        body.append(Text.get("lens_correction")).append(": ")
            .append(lcEnabled ? Text.get("lc_on") : Text.get("lc_off"));
        showResult(Text.get("applied_title"), body.toString());
    }

    // ------------------------------------------------------------ drawing
    protected void onDraw(Canvas c) {
        c.drawColor(BLACK);
        int W = logicalW;
        // title bar
        pTitle.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, 24, 44, pTitle);
        c.drawLine(0, 56, W, 56, pLine);
        if (state == ST_CHECKING) {
            pRow.setTextAlign(Paint.Align.CENTER);
            c.drawText(Text.get("checking"), W / 2, 240, pRow);
            return;
        }
        if (state == ST_ELENS) {
            pRow.setTextAlign(Paint.Align.LEFT);
            c.drawText(Text.get("e_lens"), 24, 130, pRow);
            pTitle.setTextSize(26);
            c.drawText(eLensName, 24, 175, pTitle);
            pTitle.setTextSize(30);
            pSub.setTextAlign(Paint.Align.LEFT);
            c.drawText(Text.get("e_lens_hint"), 24, 230, pSub);
            drawFooter(c, W);
            return;
        }
        if (state == ST_TOAST) {
            drawResult(c, W);
            return;
        }
        if (state == ST_CONFIRM) {
            drawConfirm(c, W);
            return;
        }
        // list screens
        int y = 100;
        int rowH = 56;
        for (int i = top; i < rows.size() && i < top + VISIBLE_ROWS; i++) {
            Row r = rows.get(i);
            if (i == sel) {
                c.drawRect(0, y - 34, W, y + 14, pSel);
                pSelT.setTextAlign(Paint.Align.LEFT);
                c.drawText(r.label, 28, y, pSelT);
            } else {
                pRow.setTextAlign(Paint.Align.LEFT);
                String label = r.label;
                if (r.sub.length() > 0) {
                    // sub column starts at x=300: keep the label out of it
                    label = ellipsize(pRow, label, 300 - 28 - 14);
                    pSub.setTextAlign(Paint.Align.LEFT);
                    c.drawText(r.sub, 300, y, pSub);
                }
                c.drawText(label, 28, y, pRow);
            }
            y += rowH;
            if (y > 420) break;
        }
        if (rows.isEmpty()) {
            pSub.setTextAlign(Paint.Align.CENTER);
            c.drawText(Text.get("no_favorites"), W / 2, 200, pSub);
        }
        drawFooter(c, W);
    }

    private void drawConfirm(Canvas c, int W) {
        int y = 110;
        pRow.setTextAlign(Paint.Align.LEFT);
        c.drawText(lens.displayName(), 24, y, pRow);
        y += 36;
        pSub.setTextAlign(Paint.Align.LEFT);
        if (lens.mount.length() > 0) {
            c.drawText(lens.mount, 24, y, pSub);
            y += 32;
        }
        if (lens.isZoom()) {
            c.drawText(Text.get("range") + ": " + lens.focalMin + "–" + lens.focalMax + " "
                    + Text.get("mm"), 24, y, pSub);
            y += 32;
        }
        c.drawText(Text.get("summary_ibis") + ": " + chosenFocal + " " + Text.get("mm")
                + (lens.isZoom() ? " " + Text.get("wide_end") : ""), 24, y, pSub);
        y += 32;
        c.drawText(Text.get("summary_exif") + ": " + lens.displayName()
                + (lens.maxAperture > 0 ? " · f/" + lens.maxAperture : ""), 24, y, pSub);
        y += 32;
        if (lens.isZoom()) {
            c.drawText(Text.get("zoom_warn"), 24, y, pSub);
            y += 40;
        } else {
            y += 8;
        }
        int rowH = 56;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i == sel) {
                c.drawRect(0, y - 34, W, y + 14, pSel);
                pSelT.setTextAlign(Paint.Align.LEFT);
                String label = r.label;
                if (r.sub.length() > 0) {
                    label = ellipsize(pSelT, label, 300 - 28 - 14);
                    c.drawText(r.sub, 300, y, pSelT);
                }
                c.drawText(label, 28, y, pSelT);
            } else {
                pRow.setTextAlign(Paint.Align.LEFT);
                String label = r.label;
                if (r.sub.length() > 0) {
                    label = ellipsize(pRow, label, 300 - 28 - 14);
                    pSub.setTextAlign(Paint.Align.LEFT);
                    c.drawText(r.sub, 300, y, pSub);
                }
                c.drawText(label, 28, y, pRow);
            }
            y += rowH;
        }
        drawFooter(c, W);
    }

    /** Centered info window: title on top, labeled lines below. */
    private void drawResult(Canvas c, int W) {
        String[] lines = toastBody.split("\n");
        int pad = 26, lineH = 42;
        pRow.setTextAlign(Paint.Align.LEFT);
        float maxW = pTitle.measureText(toastTitle);
        for (String s : lines) maxW = Math.max(maxW, pRow.measureText(s));
        int boxW = (int) Math.min(W - 60, maxW + pad * 2);
        int boxL = (W - boxW) / 2, boxR = boxL + boxW;
        int boxT = 96;
        int boxB = boxT + 62 + lines.length * lineH + pad;
        if (boxB > 472) boxB = 472;
        c.drawRect(boxL, boxT, boxR, boxB, pBoxFill);
        c.drawRect(boxL, boxT, boxR, boxB, pBox);
        pTitle.setTextAlign(Paint.Align.CENTER);
        c.drawText(ellipsize(pTitle, toastTitle, boxW - pad * 2), W / 2, boxT + 44, pTitle);
        pTitle.setTextAlign(Paint.Align.LEFT);
        c.drawLine(boxL + pad, boxT + 60, boxR - pad, boxT + 60, pLine);
        int y = boxT + 60 + 40;
        for (String s : lines) {
            c.drawText(ellipsize(pRow, s, boxW - pad * 2), boxL + pad, y, pRow);
            y += lineH;
        }
    }

    private void drawFooter(Canvas c, int W) {
        pFoot.setColor(DARK);
        c.drawRect(0, 436, W, 480, pFoot);
        pFoot.setColor(GRAY);
        pFoot.setTextAlign(Paint.Align.CENTER);
        c.drawText(hint, W / 2, 464, pFoot);
    }

}
