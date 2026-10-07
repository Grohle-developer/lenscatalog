package com.lenscatalog;

import java.util.Locale;

/**
 * UI strings: Spanish and English. Drawn in the bundled Roboto (assets/fonts),
 * which has Latin-1, Latin Extended-A, Cyrillic and the usual punctuation
 * (· – … « »); marks such as stars, ticks and arrows are drawn as shapes by
 * MenuView, never typed here (the camera's own font has none of them).
 */
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
        { "no_favorites", "Sin favoritos", "No favourites" },
        { "no_favorites_hint", "Márcalos con la estrella en la ficha del objetivo.", "Star them on a lens's page." },
        { "last_used", "Último utilizado", "Last used" },
        { "manual", "Datos manuales", "Manual data" },
        { "manual_intro", "Para un objetivo que no está en el catálogo.", "For a lens that is not in the catalogue." },
        { "manual_preview", "EXIF: {0}", "EXIF: {0}" },
        { "diagnostics", "Diagnóstico", "Diagnostics" },
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
        { "zoom", "ZOOM", "ZOOM" },
        { "prime", "FIJO", "PRIME" },
        { "apply", "Aplicar", "Apply" },
        { "add_fav", "Añadir a favoritos", "Add to favourites" },
        { "in_fav", "En favoritos", "In favourites" },
        { "summary_ibis", "IBIS", "IBIS" },
        { "summary_exif", "EXIF", "EXIF" },
        { "applied", "{0} · IBIS {1} · EXIF OK", "{0} · IBIS {1} · EXIF OK" },
        { "applied_title", "Aplicado", "Applied" },
        { "sim", "SIM", "SIM" },
        { "sim_note", "Simulador: llamadas registradas, nada aplicado.", "Simulator: calls logged, nothing applied." },
        { "zoom_warn", "Si mueves el zoom, vuelve a aplicar.", "If you move the zoom, re-apply." },
        { "exit", "Saliendo…", "Exiting…" },
        { "res_lens", "Objetivo", "Lens" },
        { "res_lc", "Corrección", "Correction" },
        { "res_ok", "Listo", "Ready" },
        { "res_sim", "Simulado", "Simulated" },
        { "res_failed", "Error", "Failed" },
        { "res_unsupported", "No admitido: usa «Escribir EXIF»", "Not supported: use “Write EXIF”" },
        { "tag_exif", "Escribir EXIF en fotos", "Write EXIF to photos" },
        { "tagging", "Etiquetando fotos…", "Tagging photos…" },
        { "tagging_hint", "Buscando fotos nuevas en la tarjeta.", "Looking for new photos on the card." },
        { "tag_done", "Fotos etiquetadas", "Photos tagged" },
        { "tag_result", "{0} etiquetadas - {1} ya estaban - {2} fallos ({3} ms)", "{0} tagged - {1} already - {2} failed ({3} ms)" },
        { "tag_tagged", "Etiquetadas", "Tagged" },
        { "tag_already", "Ya estaban", "Already tagged" },
        { "tag_electronic", "Obj. electrónico", "Electronic lens" },
        { "tag_failed", "Fallos", "Failed" },
        { "tag_time", "Tiempo", "Time" },
        { "tag_failed_hint", "Detalle en «Ver registro».", "Details in “View log”." },
        { "view_log", "Ver registro", "View log" },
        { "log_title", "Registro", "Log" },
        { "log_empty", "(vacío)", "(empty)" },
        { "log_lines", "{0} líneas", "{0} lines" },
        { "auto_exit", "Salir auto tras aplicar", "Auto-exit after apply" },
        { "on", "SÍ", "ON" },
        { "off", "NO", "OFF" },
        { "fav_added", "Añadido a favoritos", "Added to favourites" },
        { "fav_removed", "Quitado de favoritos", "Removed from favourites" },
        { "lens_correction", "Corrección de lente", "Lens correction" },
        { "lc_adjust", "Ajustar corrección", "Adjust correction" },
        { "lc_reset", "Restablecer valores", "Reset values" },
        { "lc_on", "ON", "ON" },
        { "lc_off", "OFF", "OFF" },
        { "lc_applied", "LC OK", "LC OK" },
        { "lc_none", "sin ajustar", "not set" },
        { "lc_count", "{0} de 9", "{0} of 9" },
        { "lc_shading_w", "Viñeteo · brillo", "Shading · brightness" },
        { "lc_shading_wm", "Viñeteo · brillo medio", "Shading · mid brightness" },
        { "lc_shading_cr", "Viñeteo · rojo", "Shading · red" },
        { "lc_shading_cb", "Viñeteo · azul", "Shading · blue" },
        { "lc_shading_cm", "Viñeteo · color medio", "Shading · mid color" },
        { "lc_chroma_r", "Aberr. crom. · rojo", "Chr. aberr. · red" },
        { "lc_chroma_b", "Aberr. crom. · azul", "Chr. aberr. · blue" },
        { "lc_dist", "Distorsión", "Distortion" },
        { "lc_dist_m", "Distorsión · media", "Distortion · mid" },
        { "sec_quick", "Acceso rápido", "Quick access" },
        { "sec_catalog", "Catálogo", "Catalogue" },
        { "sec_tools", "Herramientas", "Tools" },
        { "n_lenses", "{0} objetivos", "{0} lenses" },
        { "n_lens", "{0} objetivo", "{0} lens" },
        { "n_brands", "{0} marcas", "{0} brands" },
        { "n_brand", "{0} marca", "{0} brand" },
        { "catalog_src", "Catálogo", "Catalogue" },
        { "catalog_apk", "{0} objetivos · interno", "{0} lenses · built-in" },
        { "catalog_card", "{0} objetivos · tarjeta", "{0} lenses · memory card" },
        { "app_version", "Versión", "Version" },
        // the legend: what the keys do on this screen
        { "lg_choose", "Elegir", "Choose" },
        { "lg_page", "Página", "Page" },
        { "lg_open", "Entrar", "Open" },
        { "lg_ok", "OK", "OK" },
        { "lg_adjust", "Ajustar", "Adjust" },
        { "lg_back", "Atrás", "Back" },
        { "lg_exit", "Salir", "Exit" },
        { "lg_scroll", "Desplazar", "Scroll" },
        { "lg_home", "Inicio", "Home" },
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
