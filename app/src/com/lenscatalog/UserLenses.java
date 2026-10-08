package com.lenscatalog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The lenses added on the camera itself (MenuView's "Add a lens" form, typed
 * on the on-screen keyboard). They live in two places:
 *
 * - in the app's own storage (files/mylenses.json), the copy of record: it
 *   survives a change of memory card;
 * - on the card, as /LENSCAT/MYLENSES.JSN, in the very format of the
 *   catalogue (assets/lenses.json: brands > models), so a computer can read
 *   it and its entries can go into the next release of the app; and so that
 *   a reinstall, or a second body, gets them back: on load, entries the card
 *   has and the app does not are imported.
 *
 * Writes are atomic (a temporary file, then a rename) and the card copy is
 * never overwritten while it cannot be read: a file that fails to parse is
 * set aside as MYLENSES.BAD, and said so in the log. 8.3 upper-case names
 * only, as the camera's card wants. Plain org.json, as Catalog.
 */
final class UserLenses {
    static final String CARD_DIR = "LENSCAT", CARD_FILE = "MYLENSES.JSN", CARD_BAD = "MYLENSES.BAD";
    static final String INTERNAL_FILE = "mylenses.json";
    /** What the form accepts; the catalogue's longest model name is 62. */
    static final int MAX_BRAND = 24, MAX_MODEL = 60, MAX_MOUNT = 12, MAX_LENSES = 500;
    static final int FOCAL_MIN = 1, FOCAL_MAX = 3000;

    /** What a save did: where it went, and what failed. */
    static final class Result {
        /** Null when the app's copy was written. */
        String internalError;
        /** Null when the card's copy was written. */
        String cardError;
    }

    private final File internal;
    private final File cardDir;
    private final String version;
    private final List<Catalog.Lens> lenses = new ArrayList<Catalog.Lens>();
    /** How many came from the card at the last load (a reinstall, a second body). */
    int imported;
    /** The card copy's state after the last write or load, for diagnostics; null = fine. */
    String cardError;

    UserLenses(File filesDir, File cardRoot, String appVersion) {
        internal = new File(filesDir, INTERNAL_FILE);
        cardDir = new File(cardRoot, CARD_DIR);
        version = appVersion == null ? "" : appVersion;
    }

    /** The app's copy, then whatever the card has that the app does not (imported and kept). */
    synchronized void load() {
        lenses.clear();
        imported = 0;
        cardError = null;
        try {
            if (internal.isFile()) parseInto(readFile(internal), lenses);
        } catch (Throwable t) {
            AppLog.e("my lenses: app copy unreadable", t);
            lenses.clear();
        }
        File card = new File(cardDir, CARD_FILE);
        if (card.isFile()) {
            List<Catalog.Lens> onCard = new ArrayList<Catalog.Lens>();
            try {
                parseInto(readFile(card), onCard);
                for (Catalog.Lens l : onCard) {
                    if (byId(l.id) == null && lenses.size() < MAX_LENSES) {
                        lenses.add(l);
                        imported++;
                    }
                }
                if (imported > 0) {
                    String err = writeInternal();
                    if (err != null) AppLog.i("my lenses: imported " + imported + " from the card, not kept: " + err);
                }
            } catch (Throwable t) {
                // someone's edit broke it: set it aside, so nothing of theirs is lost, and write ours anew
                File bad = new File(cardDir, CARD_BAD);
                bad.delete();
                boolean aside = card.renameTo(bad);
                cardError = "card copy unreadable" + (aside ? ", set aside as " + CARD_BAD : "");
                AppLog.e("my lenses: " + cardError, t);
            }
        }
        if (lenses.size() > 0 || card.isFile()) {
            String err = export();
            if (err != null) cardError = err;
        }
        AppLog.i("my lenses: " + lenses.size() + (imported > 0 ? " (" + imported + " imported from the card)" : "")
                + (cardError != null ? "; " + cardError : ""));
    }

    synchronized List<Catalog.Lens> all() {
        return new ArrayList<Catalog.Lens>(lenses);
    }

    synchronized int size() { return lenses.size(); }

    synchronized Catalog.Lens byId(String id) {
        for (Catalog.Lens l : lenses) if (l.id.equals(id)) return l;
        return null;
    }

    /**
     * Add a lens, or replace the one with its id. The lens is cleaned here
     * (whitespace, control characters, lengths, focal range) and marked as
     * the user's. The app's copy is written first; the card's after.
     */
    synchronized Result save(Catalog.Lens l) {
        Result r = new Result();
        tidy(l);
        l.user = true;
        if (l.id == null || l.id.length() == 0) l.id = newId(l.brand, l.model, l.mount);
        Catalog.Lens old = byId(l.id);
        if (old != null) lenses.set(lenses.indexOf(old), l);
        else {
            if (lenses.size() >= MAX_LENSES) {
                r.internalError = "already " + MAX_LENSES + " lenses";
                return r;
            }
            lenses.add(l);
        }
        r.internalError = writeInternal();
        r.cardError = export();
        cardError = r.cardError;
        AppLog.i("my lenses: saved " + l.id + " \"" + l.displayName() + "\""
                + (r.internalError != null ? "; app copy: " + r.internalError : "")
                + (r.cardError != null ? "; card copy: " + r.cardError : ""));
        return r;
    }

    /** Remove a lens by id. Both copies are rewritten; false when there was no such lens. */
    synchronized boolean remove(String id) {
        Catalog.Lens l = byId(id);
        if (l == null) return false;
        lenses.remove(l);
        String e1 = writeInternal(), e2 = export();
        cardError = e2;
        AppLog.i("my lenses: removed " + id + (e1 != null ? "; app copy: " + e1 : "") + (e2 != null ? "; card copy: " + e2 : ""));
        return true;
    }

    /** The card's copy, written anew from the app's. Null when written. */
    synchronized String export() {
        try {
            cardDir.mkdirs();
            if (!cardDir.isDirectory()) return "no /" + CARD_DIR + " on the card";
            return writeAtomic(new File(cardDir, CARD_FILE), new File(cardDir, "MYLENSES.TMP"), json(lenses, version));
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private String writeInternal() {
        try {
            File dir = internal.getParentFile();
            if (dir != null) dir.mkdirs();
            return writeAtomic(internal, new File(internal.getPath() + ".tmp"), json(lenses, version));
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** Write through a temporary file and rename it into place; the old file stays until the new one is complete. */
    private static String writeAtomic(File target, File tmp, String content) throws Exception {
        tmp.delete();
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (tmp.renameTo(target)) return null;
        // a card that will not rename over: the old file goes first, then the new one takes its name
        if (target.exists() && !target.delete()) {
            tmp.delete();
            return "cannot replace " + target.getName();
        }
        if (tmp.renameTo(target)) return null;
        tmp.delete();
        return "cannot rename " + tmp.getName() + " into place";
    }

    // ------------------------------------------------------------ the format
    /** The catalogue's own JSON: brands, each with its models, in the catalogue's order. */
    static String json(List<Catalog.Lens> lenses, String version) throws Exception {
        List<Catalog.Lens> sorted = new ArrayList<Catalog.Lens>(lenses);
        Collections.sort(sorted, new Comparator<Catalog.Lens>() {
            public int compare(Catalog.Lens a, Catalog.Lens b) {
                int c = a.brand.compareToIgnoreCase(b.brand);
                return c != 0 ? c : a.model.compareToIgnoreCase(b.model);
            }
        });
        JSONObject root = new JSONObject();
        root.put("_source", "LensCatalog " + version + ": lenses added on the camera. Same format as the app's "
                + "lenses.json; send them in so the next release carries them.");
        root.put("version", 2);
        JSONArray brands = new JSONArray();
        JSONArray models = null;
        String brand = null;
        for (Catalog.Lens l : sorted) {
            if (brand == null || !brand.equals(l.brand)) {
                brand = l.brand;
                JSONObject b = new JSONObject();
                b.put("brand", brand);
                models = new JSONArray();
                b.put("models", models);
                brands.put(b);
            }
            JSONObject m = new JSONObject();
            m.put("id", l.id);
            m.put("model", l.model);
            m.put("mount", l.mount == null ? "" : l.mount);
            m.put("type", l.isZoom() ? "zoom" : "prime");
            if (l.maxAperture > 0) m.put("max_aperture", l.maxAperture);
            if (l.isZoom()) {
                m.put("focal_min", l.focalMin);
                m.put("focal_max", l.focalMax);
            } else {
                m.put("focal", l.focal);
            }
            if (l.notes != null && l.notes.length() > 0) m.put("notes", l.notes);
            models.put(m);
        }
        root.put("brands", brands);
        return root.toString(1) + "\n";
    }

    /** Read the catalogue format into `out`; every lens is marked the user's and cleaned. */
    static void parseInto(String json, List<Catalog.Lens> out) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray bs = root.getJSONArray("brands");
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            String brand = b.getString("brand");
            JSONArray ms = b.getJSONArray("models");
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.getJSONObject(j);
                Catalog.Lens l = new Catalog.Lens();
                l.brand = brand;
                l.model = m.getString("model");
                l.mount = m.optString("mount", "");
                l.type = m.optString("type", "prime");
                l.notes = m.optString("notes", "");
                l.focal = m.optInt("focal", 50);
                l.focalMin = m.optInt("focal_min", l.focal);
                l.focalMax = m.optInt("focal_max", l.focal);
                l.maxAperture = m.optDouble("max_aperture", 0);
                l.user = true;
                tidy(l);
                l.id = m.optString("id", "");
                if (l.id.length() == 0) l.id = newId(l.brand, l.model, l.mount);
                if (l.model.length() == 0 || out.size() >= MAX_LENSES) continue;
                out.add(l);
            }
        }
    }

    // ------------------------------------------------------------ cleaning
    /** Trim, one space between words, no control characters, within length. */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = true; // drops leading spaces
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isWhitespace(c)) {
                if (!space) b.append(' ');
                space = true;
            } else {
                b.append(c);
                space = false;
            }
        }
        int n = b.length();
        while (n > 0 && b.charAt(n - 1) == ' ') n--;
        b.setLength(Math.min(n, max));
        return b.toString();
    }

    /** The fields within what the catalogue holds; a zoom's range the right way round. */
    static void tidy(Catalog.Lens l) {
        l.brand = clean(l.brand, MAX_BRAND);
        l.model = clean(l.model, MAX_MODEL);
        l.mount = clean(l.mount, MAX_MOUNT);
        l.notes = clean(l.notes, 200);
        if (l.brand.length() == 0) l.brand = "Manual";
        if (l.type == null || !(l.type.equals("zoom") || l.type.equals("prime"))) l.type = "prime";
        l.focal = clampFocal(l.focal);
        l.focalMin = clampFocal(l.focalMin);
        l.focalMax = clampFocal(l.focalMax);
        if (l.isZoom()) {
            if (l.focalMin > l.focalMax) { int t = l.focalMin; l.focalMin = l.focalMax; l.focalMax = t; }
            l.focal = l.focalMin;
        } else {
            l.focalMin = l.focalMax = l.focal;
        }
        if (!(l.maxAperture > 0) || l.maxAperture > 64) l.maxAperture = 0;
        else l.maxAperture = Math.round(l.maxAperture * 100) / 100.0;
    }

    static int clampFocal(int f) {
        return f < FOCAL_MIN ? FOCAL_MIN : f > FOCAL_MAX ? FOCAL_MAX : f;
    }

    /** An id in the catalogue's style, marked as the user's: "my-canon-fd-50mm-f-1-4-fd". */
    static String newId(String brand, String model, String mount) {
        String s = slug(brand) + "-" + slug(model);
        if (mount != null && mount.length() > 0) s += "-" + slug(mount);
        return "my-" + s;
    }

    private static String slug(String s) {
        StringBuilder b = new StringBuilder();
        boolean dash = true;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                b.append(c);
                dash = false;
            } else if (!dash) {
                b.append('-');
                dash = true;
            }
        }
        int n = b.length();
        while (n > 0 && b.charAt(n - 1) == '-') n--;
        b.setLength(n);
        return b.length() == 0 ? "x" : b.toString().toLowerCase(Locale.US);
    }

    private static String readFile(File f) throws Exception {
        if (f.length() > 4 * 1024 * 1024) throw new Exception("too large: " + f.length());
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }
}
