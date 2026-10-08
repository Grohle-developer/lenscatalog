package com.lenscatalog;

import android.content.Context;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The lens catalogue. Seed in the APK's assets/lenses.json; the user's own
 * copy at /DCIM/LENSES/lenses.json (editable on a PC) wins when it exists.
 * Plain org.json: on the camera and in the simulator alike.
 */
final class Catalog {
    static final class Lens {
        String id, brand, model, mount, type, notes;
        int focal;                 // prime
        int focalMin, focalMax;    // zoom
        double maxAperture;        // 0 = unknown
        boolean manual;            // from the manual-data branch

        String displayName() { return brand + " " + model; }
        boolean isZoom() { return "zoom".equals(type); }
    }

    final List<String> brands = new ArrayList<String>();
    final List<Lens> lenses = new ArrayList<Lens>();
    /** true when the user's /DCIM/LENSES/lenses.json was used. */
    boolean userCopy;

    static Catalog load(Context ctx) {
        Catalog c = new Catalog();
        String json = null;
        File user = userCopy(new File(Environment.getExternalStorageDirectory(), "DCIM/LENSES"));
        if (user != null && user.isFile()) {
            try {
                json = readFile(user);
                c.userCopy = true;
            } catch (Exception e) {
                c.userCopy = false;
            }
        }
        if (json == null) {
            try {
                InputStream in = ctx.getAssets().open("lenses.json");
                json = readStream(in);
                in.close();
            } catch (Exception e) {
                return c; // empty catalogue, still usable (manual branch)
            }
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray bs = root.getJSONArray("brands");
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                String brand = b.getString("brand");
                c.brands.add(brand);
                JSONArray ms = b.getJSONArray("models");
                for (int j = 0; j < ms.length(); j++) {
                    JSONObject m = ms.getJSONObject(j);
                    Lens l = new Lens();
                    l.id = m.optString("id", brand + j);
                    l.brand = brand;
                    l.model = m.getString("model");
                    l.mount = m.optString("mount", "");
                    l.type = m.optString("type", "prime");
                    l.notes = m.optString("notes", "");
                    l.focal = m.optInt("focal", 50);
                    l.focalMin = m.optInt("focal_min", l.focal);
                    l.focalMax = m.optInt("focal_max", l.focal);
                    l.maxAperture = m.optDouble("max_aperture", 0);
                    c.lenses.add(l);
                }
            }
        } catch (Exception e) {
            // malformed user JSON: fall back to the seed
            if (c.userCopy) {
                c.userCopy = false;
                return loadSeed(ctx);
            }
        }
        return c;
    }

    /**
     * The user's catalogue in /DCIM/LENSES: lenses.json as the README says, or
     * what that becomes on the camera's card, which holds 8.3 names only (a
     * computer's "lenses.json" shows there as LENSES~1.JSO), or LENSES.JSN, a
     * name that fits as it is. Null when there is none.
     */
    static File userCopy(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        File found = null;
        for (String n : names) {
            String l = n.toLowerCase();
            if (l.equals("lenses.json")) return new File(dir, n);
            if (l.startsWith("lenses") && (l.endsWith(".json") || l.endsWith(".jso") || l.endsWith(".jsn")))
                found = new File(dir, n);
        }
        return found;
    }

    private static Catalog loadSeed(Context ctx) {
        Catalog c = new Catalog();
        try {
            InputStream in = ctx.getAssets().open("lenses.json");
            String json = readStream(in);
            in.close();
            JSONObject root = new JSONObject(json);
            JSONArray bs = root.getJSONArray("brands");
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                String brand = b.getString("brand");
                c.brands.add(brand);
                JSONArray ms = b.getJSONArray("models");
                for (int j = 0; j < ms.length(); j++) {
                    JSONObject m = ms.getJSONObject(j);
                    Lens l = new Lens();
                    l.id = m.optString("id", brand + j);
                    l.brand = brand;
                    l.model = m.getString("model");
                    l.mount = m.optString("mount", "");
                    l.type = m.optString("type", "prime");
                    l.focal = m.optInt("focal", 50);
                    l.focalMin = m.optInt("focal_min", l.focal);
                    l.focalMax = m.optInt("focal_max", l.focal);
                    l.maxAperture = m.optDouble("max_aperture", 0);
                    c.lenses.add(l);
                }
            }
        } catch (Exception e) { /* empty */ }
        return c;
    }

    List<Lens> modelsOf(String brand) {
        List<Lens> out = new ArrayList<Lens>();
        for (Lens l : lenses) if (l.brand.equals(brand)) out.add(l);
        return out;
    }

    Lens byId(String id) {
        for (Lens l : lenses) if (l.id.equals(id)) return l;
        return null;
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readStream(in);
        } finally {
            in.close();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
