package com.lenscatalog;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Last-used lens and favourites, in SharedPreferences. */
final class Store {
    private final SharedPreferences p;

    Store(Context ctx) {
        p = ctx.getSharedPreferences("lenscatalog", Context.MODE_PRIVATE);
    }

    String lastUsed() {
        return p.getString("last_used", null);
    }

    void setLastUsed(String id) {
        p.edit().putString("last_used", id).commit();
    }

    List<String> favorites() {
        List<String> out = new ArrayList<String>();
        String s = p.getString("favorites", "");
        for (String id : s.split(",")) {
            id = id.trim();
            if (id.length() > 0) out.add(id);
        }
        return out;
    }

    boolean isFavorite(String id) {
        return favorites().contains(id);
    }

    void toggleFavorite(String id) {
        List<String> f = favorites();
        if (f.contains(id)) f.remove(id);
        else f.add(id);
        StringBuilder sb = new StringBuilder();
        for (String x : f) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        p.edit().putString("favorites", sb.toString()).commit();
    }

    // ------------------------------------------------ lens correction
    /** Number of manual correction levels (Sony's Lens Compensation model). */
    static final int LC_N = 9;

    /** The 9 stored levels for a lens id; all zero when never tuned. */
    int[] lensCorrectionLevels(String id) {
        int[] d = new int[LC_N];
        String s = p.getString("lc_" + id, null);
        if (s == null) return d;
        String[] parts = s.split(",");
        for (int i = 0; i < LC_N && i + 1 < parts.length; i++) {
            try { d[i] = Integer.parseInt(parts[i + 1].trim()); }
            catch (NumberFormatException e) { d[i] = 0; }
        }
        return d;
    }

    /** Whether correction is enabled for a lens id (default: off). */
    boolean lensCorrectionEnabled(String id) {
        String s = p.getString("lc_" + id, null);
        return s != null && s.startsWith("1,");
    }

    /** Stored as "enabled,v0,..,v8". Write-through: safe, the camera is
     * only touched when the user hits Apply on the confirm screen. */
    void setLensCorrection(String id, boolean enabled, int[] levels) {
        StringBuilder sb = new StringBuilder(enabled ? "1" : "0");
        for (int i = 0; i < LC_N; i++) sb.append(',').append(levels[i]);
        p.edit().putString("lc_" + id, sb.toString()).commit();
    }

    /** Whether the app exits automatically after Apply/Tag (default: true). */
    boolean autoExit() {
        return p.getBoolean("auto_exit", true);
    }

    void setAutoExit(boolean v) {
        p.edit().putBoolean("auto_exit", v).commit();
    }
}
