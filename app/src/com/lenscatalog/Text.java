package com.lenscatalog;

import java.util.Locale;

/** UI strings: Spanish and English. */
final class Text {
    private Text() {}

    private static final String[][] T = {
        // { key, es, en }
        { "title", "LensCatalog", "LensCatalog" },
        { "checking", "Comprobando objetivo…", "Checking lens…" },
        { "e_lens", "Objetivo electrónico detectado:", "Electronic lens detected:" },
        { "e_lens_hint", "Nada que hacer. Pulsa MENU para salir.", "Nothing to do. Press MENU to exit." },
        { "no_e_lens", "Sin objetivo electrónico", "No electronic lens" },
        { "no_e_lens_hint", "Elige el objetivo manual conectado.", "Choose the attached manual lens." },
        { "brands", "Marcas", "Brands" },
        { "models", "Modelos", "Models" },
        { "favorites", "Favoritos", "Favourites" },
        { "no_favorites", "(sin favoritos)", "(no favourites)" },
        { "last_used", "Último utilizado", "Last used" },
        { "manual", "Datos manuales", "Manual data" },
        { "manual_brand", "Marca: Manual", "Brand: Manual" },
        { "manual_model", "Modelo: Manual", "Model: Manual" },
        { "focal", "Focal", "Focal length" },
        { "focal_min", "Focal mín.", "Min. focal" },
        { "focal_max", "Focal máx.", "Max. focal" },
        { "max_aperture", "Apertura máx.", "Max. aperture" },
        { "skip", "(omitir)", "(skip)" },
        { "mm", "mm", "mm" },
        { "range", "Rango", "Range" },
        { "wide_end", "(angular)", "(wide end)" },
        { "apply", "Aplicar", "Apply" },
        { "add_fav", "Añadir a favoritos", "Add to favourites" },
        { "in_fav", "En favoritos ✓", "In favourites ✓" },
        { "summary_ibis", "IBIS", "IBIS" },
        { "summary_exif", "EXIF", "EXIF" },
        { "confirm_hint", "OK aplica · MENU atrás", "OK applies · MENU back" },
        { "list_hint", "↑↓ elegir · OK entrar · MENU atrás", "↑↓ choose · OK enter · MENU back" },
        { "applied", "{0} · IBIS {1} · EXIF ✓", "{0} · IBIS {1} · EXIF ✓" },
        { "applied_title", "✓ Aplicado", "✓ Applied" },
        { "sim_note", "[SIM] sin framework Sony: llamada registrada, nada aplicado", "[SIM] no Sony framework: call logged, nothing applied" },
        { "zoom_warn", "Si mueves el zoom, vuelve a aplicar.", "If you move the zoom, re-apply." },
        { "exit", "Saliendo…", "Exiting…" },
        { "fav_added", "Añadido a favoritos", "Added to favourites" },
        { "fav_removed", "Quitado de favoritos", "Removed from favourites" },
        { "lens_correction", "Corrección de lente", "Lens correction" },
        { "lc_adjust", "Ajustar corrección", "Adjust correction" },
        { "lc_reset", "↺ Restablecer valores", "↺ Reset values" },
        { "lc_on", "ON", "ON" },
        { "lc_off", "OFF", "OFF" },
        { "lc_applied", "LC ✓", "LC ✓" },
        { "lc_hint", "↑↓ elegir · ◀ ▶ ajustar · MENU atrás", "↑↓ choose · ◀ ▶ adjust · MENU back" },
        { "lc_shading_w", "Viñeteo · brillo", "Shading · brightness" },
        { "lc_shading_wm", "Viñeteo · brillo medio", "Shading · mid brightness" },
        { "lc_shading_cr", "Viñeteo · rojo", "Shading · red" },
        { "lc_shading_cb", "Viñeteo · azul", "Shading · blue" },
        { "lc_shading_cm", "Viñeteo · color medio", "Shading · mid color" },
        { "lc_chroma_r", "Aberr. crom. · rojo", "Chr. aberr. · red" },
        { "lc_chroma_b", "Aberr. crom. · azul", "Chr. aberr. · blue" },
        { "lc_dist", "Distorsión", "Distortion" },
        { "lc_dist_m", "Distorsión · media", "Distortion · mid" },
    };

    private static boolean es() {
        return Locale.getDefault().getLanguage().equals("es");
    }

    static String get(String key) {
        for (String[] r : T) if (r[0].equals(key)) return es() ? r[1] : r[2];
        return key;
    }

    /** get(key) with {0}, {1}… replaced. */
    static String fmt(String key, String... args) {
        String s = get(key);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", args[i]);
        return s;
    }
}
