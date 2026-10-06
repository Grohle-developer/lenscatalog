# LensCatalog — notas de desarrollo

App PMCA para la Sony A7 II (Android 2.3.7, API 10): catálogo de lentes
manuales + inyección EXIF pre-captura + focal del IBIS. Java puro, sin JNI.
Especificación: `../DISENO-CATALOGO-LENTES.md` (vía A).

## Estructura

```
lenscatalog/
  app/
    AndroidManifest.xml      package com.lenscatalog, minSdk/targetSdk 10
    build.sh                 receta adaptada (javac + d8, sin NDK)
    res/values/strings.xml
    assets/lenses.json       semilla: 15 lentes (M42, K, MD, FD, C/Y, EF-manual)
    src/com/lenscatalog/
      MainActivity.java      activity + dispatchKeyEvent por scan codes
      MenuView.java          UI + máquina de estados (estilo menú nativo Sony)
      Screen.java            REUTILIZADA TAL CUAL de la plantilla (ver abajo)
      AppLog.java            copiada de la plantilla (la necesita Screen.java)
      Keys.java              scan codes Sony (recortada de la plantilla)
      Sony.java              puente CameraEx por reflexión, degradación en sim
      Catalog.java           modelo JSON (org.json)
      Store.java             last_used + favorites (SharedPreferences)
      Text.java              ES/EN
    tools/                   api-check.py + strip-params.py (plantilla, intactos)
    out/lenscatalog.apk      24 KB, v1-only, API check OK
  shots/                     20 pantallazos del sim
  tour.sh                    tour automatizado de pantallazos
```

## Reutilización de la plantilla (`aintfilm-sony-claude/aintfilm-aintfilm-sony/`)

- `Screen.java`: copiada verbatim (solo cambio de package). Funciona:
  en el sim `aspect=0` → canvas 640×480.
- `AppLog.java`: copiada (Screen.java la referencia; no se encontró
  hasta el primer error de compilación).
- `Keys.java`: recortada a lo necesario (UP/DOWN/LEFT/RIGHT/ENTER/MENU).
- `build.sh`: adaptada a Java puro (sin ndk-build, sin .so). Misma receta:
  aapt → javac `--release 8` → strip-params.py → d8 `--min-api 10` →
  api-check.py → aapt → zipalign → apksigner v1-only.
- `sim/sim.sh`: usado tal cual con `install <apk>` (acepta argumento).
  `sim.sh start` lleva hardcodeado `com.aintfilm.sony`, así que el arranque
  se hace con `adb shell am start -n com.lenscatalog/.MainActivity`.

## Desviaciones del diseño (documentadas, no inventadas)

1. **Datos manuales sin teclado**: la cámara no tiene entrada de texto.
   El formulario es: focal (picker 8–1000) + apertura máx (picker u omitir);
   marca/modelo quedan como "Manual". El nombrado custom completo se hace
   editando el JSON en el PC (como preveía el diseño).
2. **"Último utilizado"**: no se guarda para lentes manuales (id sintético
   `manual:<focal>`); solo para lentes del catálogo.
3. **Pantalla de gestión del catálogo** (añadir/editar/borrar/exportar en
   la app): NO implementada en v1. La vía es editar `/DCIM/LENSES/lenses.json`
   en el PC; la app prefiere esa copia si existe (implementado en
   `Catalog.load()`).
4. **EquipmentCallback**: no implementado (el diseño lo marcaba opcional;
   flujo bajo demanda como pidió Berto).
5. **Auto-salida**: implementada (toast 2,4 s → `finish()`).

## Hallazgos de plataforma (verificados en el sim)

- **Glifos**: Droid Sans de API 10 NO tiene ⭐ U+2B50 (tofu) ni los
  caracteres astrales como 🕘 U+1F558. Verificados OK en pantallazo:
  ★ U+2605, ✓ U+2713, ◀ U+25C0 / ▶ U+25B6, ✎ U+270E, ↑↓.
- **api-check.py de la plantilla casca** con `UnicodeDecodeError` si una
  constante string contiene un carácter astral (dexdump emite MUTF-8 con
  pares subrogados). No se tocó la herramienta: se eliminó el carácter.
- Sin framework Sony (sim), `Sony.java` degrada a líneas de log
  `I/LensCatalog: SIM setAntiHandBlurFocalLength(58)` etc. — visibles en
  logcat y resumidas en el toast.

## Catálogo ampliado (2026-10-06)

- Fuente: **lensfun database** (`data/` del repo lensfun, licencia
  CC BY-SA 3.0). Extraídos solo DATOS FACTUALES (marca, modelo, montura,
  focal, apertura) — las especificaciones no son copyrighteables.
  La fuente queda documentada en el campo `_source` de `lenses.json`.
- Conversor: `/tmp/lensdb/convert.py` (monturas, limpieza de nombres,
  parse de focal/apertura desde el nombre, dedupe, validación).
- Resultado: **1187 lentes, 48 marcas, 185 KB** de JSON
  (`app/assets/lenses.json`, esquema compatible con la app).
- Monturas cubiertas (solo adaptables a Sony E, brida > 18 mm):
  M42, M39, K (todas las variantes), MD, FD, EF (manual), C/Y, G, F
  (AI/AI-S/AF), OM, M, DKL, Exakta, T2, Adaptall, M645, 4/3, SA, A,
  Kiev88, 6x6. Excluidas las mirrorless modernas no adaptables
  (RF, Z, X, E, EF-M, CX, L, NX, G, Q, M4/3, C) y pseudomonturas de
  compactas.
- Monturas sin entradas en la fuente: Konica AR, Fujica X, Rollei QBM,
  Praktica B, Topcon, Leica R (no existen lentes en la DB de lensfun;
  solo definiciones de montura/cuerpo).
- Desviaciones del brief: límite inferior de focal 4 mm (no 8 mm) para
  no descartar fisheyes reales (Sigma 4.5mm, Laowa 7.5mm, Nikkor 6mm…);
  M4/3 excluido aunque su brida (19,25 mm) supera los 18 mm —
  tratado como mirrorless de brida corta sin uso real en la A7 II.
- La pantalla de confirmación muestra la apertura máxima
  (`EXIF: … · f/4.5`, verificado en sim).
- Bug 7 (vía pantallazo): los nombres largos del catálogo grande
  solapaban la columna de focal en la lista de modelos. Añadido
  `ellipsize()` en `MenuView` (trunca con … antes de la columna).

## Flujo de zooms (cambio 2026-10-06, petición de Berto)

Eliminado el paso de ajuste manual de focal (◀ ▶) para zooms: elegir el
zoom en el catálogo → directo a confirmación. Decisiones:

- **IBIS** = extremo angular del rango (`focalMin`). Es la opción segura:
  si luego alarga el zoom, el IBIS subcorrige en vez de sobrecorregir.
  La confirmación lo muestra como "IBIS: 28 mm (angular)".
- **EXIF**: el tag numérico `FocalLength` NO se escribe para zooms (un
  rango no cabe en un racional; escribir el angular sería engañoso). El
  rango viaja en `lensName`/`LensModel` (p. ej. "Tokina 28-70mm f/2.8"),
  que ya lo incluye. Implementado como `numericExifFocal=false` en
  `Sony.apply()` / `writeExif()`.
- El aviso "Si mueves el zoom, vuelve a aplicar." se mantiene.
- Estado `ST_FOCAL` eliminado de `MenuView` (pantalla, `drawFocal`,
  `showFocal`, strings `zoom_focal`/`zoom_hint`); la confirmación muestra
  la línea "Rango: 28–70 mm" para zooms. Las focales fijas no cambian.
- Verificado en el sim: Canon EF 100-200mm f/4.5A → confirmación con
  rango + IBIS 100 mm (angular) → toast; logcat:
  `setAntiHandBlurFocalLength(100)`,
  `setExifInfo(lensName="Canon EF 100-200mm f/4.5A", focal=SKIPPED (zoom range), …)`.
  Pantallazos `shots/24-zoom-range-{models,confirm,toast}.png`.

## Bugs encontrados y corregidos (vía pantallazos)

1. Sin scroll: las marcas 7–11 + "datos manuales" eran inalcanzables.
   Añadido scroll de 6 filas visibles.
2. Solape del highlight naranja con el título (primera fila).
3. `findLens()` comparaba `displayName()` con la etiqueta del modelo
   (`l.model`) → ENTER no hacía nada en la lista de modelos.
4. En el formulario manual, ↑/↓ ajustaban en vez de navegar → la fila de
   apertura era inalcanzable.
5. NPE en `drawConfirm` con lente manual (`mount` null).
6. Tour: tras toggle-favorito, `up` + `enter` desactivaba el favorito en
   vez de aplicar (sel ya estaba en "Aplicar").

7. (v0.2.1) La ventana de resultado mostraba el string debug crudo
   (`IBIS=80mm EXIF=ok LC=on ...`) en texto centrado poco legible.
   Rediseñada como ventana de información: título \"✓ Aplicado\" + líneas
   etiquetadas (objetivo, IBIS, EXIF, corrección ON/OFF); el detalle debug
   queda solo en logcat.

## v0.2.1 — re-aplicación de la corrección (2026-10-06)

Reportado por Berto en cámara: tras aplicar una corrección y volver a
cambiar un valor, el nuevo valor no se aplicaba (solo funcionaba volver a
entrar y poner el toggle en OFF). Causa: el driver solo re-lee los niveles
manuales en la transición off→on; re-escribir niveles con la corrección ya
activada se ignora. Fix en `Sony.apply()`: commit 1 con IBIS+EXIF y
`setLensCorrection(false)` explícito, y commit 2 (parámetros frescos,
niveles primero, `setLensCorrection(true)` después) que fuerza la
transición en cada \"Aplicar\". Pendiente de confirmación en cámara real.

## Pendiente (requiere cámara real)

- H1–H7 del diseño: `setExifInfo` → ¿`LensModel` 0xA434? ¿persiste entre
  capturas? ¿`writeMode` activa el override? `getLensInfo()` con adaptador
  tonto; `APP_LAUNCHER` abre lista o app directa.
- Medir que `setAntiHandBlurFocalLength` + `setParameters` no necesita
  nada más en la A7 II real.
- Probar `/DCIM/LENSES/lenses.json` de usuario en la cámara.

## Corrección de lente manual (v0.2.0, 2026-10-06)

Mismo modelo que la app oficial de Sony *Lens Compensation* (PlayMemories,
v2.41, de pago): valores manuales por objetivo para shading periférico
(brillo/rojo/azul, zona completa + media), aberración cromática lateral
(rojo/azul) y distorsión (completa + media). Verificado en los stubs
públicos de OpenMemories (`CameraEx$ParametersModifier`):
`setLensCorrection(boolean)`, `setLensCorrectionLevel(String,int)`,
`getMin/MaxLensCorrectionLevel(String)` y 9 claves
(`shading-w/-wm/-cr/-cb/-cm`, `chroma-r/-b`, `distotion/-m` — el typo es
de Sony). Los parámetros existen en el blob de la A7 II (LiroSeq:
`shading_correction`, `distortion_correction`, `lens_shading_data`,
`lens_distortion_data`).

Diseño:
- Toggle "◐ Corrección de lente ON/OFF" en la pantalla de confirmación +
  editor "✎ Ajustar corrección" con los 9 valores (◀ ▶, write-through a
  `Store`; rangos consultados en vivo a la cámara, fallback ±20 en sim).
- Al aplicar: `setLensCorrection(true)` + los 9 niveles; con el toggle en
  OFF se escribe `setLensCorrection(false)` para que no queden valores de
  otro objetivo. El perfil vive por id de objetivo en SharedPreferences
  (`lc_<id>` = "enabled,v0,..,v8"): sobrevive a actualizaciones del JSON y
  vale para objetivos manuales.
- Verificado en sim: build OK, api-check OK, 0 errores en logcat, 5
  pantallazos nuevos (`shots/25–29`).

Pendiente en cámara: confirmar que los valores persisten tras salir de la
app (el diseño los re-escribe en cada "Aplicar", así que el peor caso es
re-aplicar al cambiar de objetivo); calibrar rangos reales por clave;
formato `LENSxxxx.BIN` de la app oficial no revertido (import/export no
implementado).
