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
}
