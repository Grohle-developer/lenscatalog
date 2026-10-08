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
    assets/lenses.json       catálogo: 1956 objetivos, 62 marcas (lensfun + ampliación propia)
    assets/fonts/            Roboto Regular/Medium recortadas + licencia Apache-2.0
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
      ExifWriter.java        EXIF post-captura (Exif IFD añadido, TIFF intacto)
      PhotoTagger.java       etiquetado de fotos nuevas por sesión de objetivo
      LensLog.java           historial de sesiones de objetivo
    tools/                   api-check.py + strip-params.py (plantilla), icon.py
    test/                    exif-test.sh: test de host de ExifWriter
    out/lenscatalog.apk      ~120 KB, v1-only, API check OK
  shots/                     pantallazos del sim (v0.3.0); shots/v0.2/ los anteriores
  tour.sh                    workflow completo en el sim de aintfilm-sony, con checks
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
  Pantallazos `shots/v0.2/24-zoom-range-{models,confirm,toast}.png`.

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
  pantallazos nuevos (`shots/v0.2/25–29`).

Pendiente en cámara: confirmar que los valores persisten tras salir de la
app (el diseño los re-escribe en cada "Aplicar", así que el peor caso es
re-aplicar al cambiar de objetivo); calibrar rangos reales por clave;
formato `LENSxxxx.BIN` de la app oficial no revertido (import/export no
implementado).

## v0.3.0 — interfaz profesional + flujo completo verificado (2026-10-07)

Petición de Berto: interfaz de nivel profesional y comprobar el workflow
completo en el simulador A7 II de `aintfilm-sony` (`sim/sim.sh`).

### Interfaz
- Fuente propia: Roboto Regular/Medium (Apache-2.0, `assets/fonts`,
  recortada a Latin-1 + Latin Ext-A + cirílico + puntuación, ~28 KB cada
  una). La fuente de la cámara no tiene ★ ✓ ↑↓ ◀ ▶ (v0.2.6): ahora **todas
  las marcas se dibujan como formas** (diafragma, estrella, check, flechas,
  reloj, foto, lápiz, info, documento, encendido, ◐, deslizadores, ↺, aviso).
  Quedaban glifos sin fuente en v0.2.7 (✔ ◐ ↑↓ ◀ ▶ ↺) que en cámara salían
  como cuadros.
- Paleta: cada color es un nivel exacto del framebuffer RGBA4444 de la
  cámara (#RGB duplicado): nada de bandas ni grises que desaparecen.
- Cabecera (marca + título + posición "12 / 104" o recuento, pastilla SIM
  en el simulador), secciones en inicio (Acceso rápido / Catálogo /
  Herramientas, no seleccionables), filas con icono, valor y chevron,
  interruptores ON/OFF dibujados, barra de desplazamiento, leyenda de teclas
  dibujada en el pie (como aintfilm-sony) y versión en inicio.
- Listas de objetivos en dos líneas (nombre + "Zoom · 100–200 mm · f/4.5 ·
  EF"), estrella en los favoritos. f/2.0 se muestra "f/2".
- Ficha del objetivo: tarjeta con nombre, chips (ZOOM/FIJO, focal,
  apertura, montura), IBIS, EXIF y aviso de zoom; las acciones ocupan el
  resto (antes, en zooms, "Añadir a favoritos" quedaba oculta bajo el pie).
- Editor de corrección: los 9 valores en una pantalla, con barra centrada en
  0 y separadores por grupo.
- Resultado: tarjeta con check, líneas etiquetadas y cuenta atrás de la
  salida automática. Muestra el estado **real** de `Sony.apply()` (EXIF
  "Listo" / "No admitido" / "Simulado"; antes siempre decía OK).
- Datos manuales: selectores ◀ valor ▶ y vista previa del nombre EXIF;
  focal 4–1000 mm (como dice el README; el código tenía 8).
- Icono de la app: el diafragma de la cabecera en blanco sobre negro
  (`app/tools/icon.py` lo dibuja con la misma geometría).

### Teclas
- Rueda de control y diales (scan codes 522/523/525/526, los mismos que usa
  aintfilm-sony): mueven en listas y ajustan en el editor y en datos
  manuales. ◀ ▶ en listas = página. Mantener pulsado repite (focal: 1, luego
  5, luego 10 mm); OK y MENU siguen siendo de una pulsación.
- Tras un toggle (corrección, favorito, salida auto) la selección se queda
  en la fila; al volver (MENU) se recupera la posición en la lista.

### Bugs encontrados en el recorrido y corregidos
1. **ExifWriter destruía el EXIF de la cámara**: reconstruía IFD0 desde
   cero, perdía el Exif IFD entero (exposición, ISO, fechas), la miniatura
   (IFD1) y metía LensModel/FocalLength/FNumber en IFD0 (exiftool: "Bad
   ExifIFD directory", "Wrong IFD"). Reescrito: el TIFF original no se toca
   (todos sus offsets, MakerNote incluida, siguen válidos); se añade al final
   un Exif IFD nuevo (entradas antiguas + las nuestras, ordenadas) y se
   reapunta 0x8769. Conserva la fecha del fichero. Test de host:
   `app/test/exif-test.sh` (7 casos: LE, BE, sin Exif IFD, sin EXIF, zoom,
   dos pasadas, UTF-8); el escritor antiguo fallaba 68 comprobaciones.
2. **Modelos con el mismo nombre en varias monturas** (Zeiss ZF.2 F/EF/K,
   Tamron F/EF, Voigtländer K/EF/F…): `findLens()` buscaba por etiqueta y
   abría siempre el primero. Ahora cada fila lleva su `Lens`.
3. Etiquetado de zooms: escribía FocalLength = extremo angular, contra la
   decisión de no escribir focal numérica en zooms. La sesión de un zoom
   guarda focal 0 y el escritor deja el FocalLength de la cámara.
4. "Escribir EXIF" con salida auto: el aviso "Etiquetando…" programaba la
   salida a los 2,4 s y podía cerrar la app antes de ver el resultado.
5. `AppLog` nunca enviaba a logcat (faltaba el sink); el contador de
   Favoritos contaba ids que no están en el catálogo cargado.

### Simulador
- `tour.sh` (reescrito, rutas por variables): recorrido completo en
  español con 27 pantallazos y comprobaciones (logcat, preferencias, EXIF de
  las fotos con exiftool). "Dispara" escribiendo JPEG tipo A7 II en
  `DCIM/100MSDCF` con la hora actual.
- Objetivo electrónico en el sim: `/AINTFILM/SIM/LENS.TXT` con su nombre
  (solo sin framework Sony).
- Android 2.3 no tiene `am force-stop`: el tour mata el proceso por pid.
- Pantallazos nuevos en `shots/`; los de v0.2 en `shots/v0.2/` (las
  referencias de arriba a `shots/NN-…` son a esa carpeta).

### Pendiente en cámara
- Que `Typeface.createFromAsset` cargue Roboto (aintfilm-sony ya lo hace
  con sus fuentes en la A7 II) y que la rueda llegue con 522/523.
- Que el mtime que ve Android en la tarjeta coincida con
  `System.currentTimeMillis()` (zona horaria del kernel vs. reloj de la
  cámara): si no, el etiquetado no casa fotos con sesiones.

## v0.3.1 — EXIF también en los RAW (2026-10-07)

Pregunta de Berto: ¿escribe también en el raw? No lo hacía: el etiquetador
solo miraba `.JPG`. Con un ARW real de la A7 II con objetivo manual
(DSC01837.ARW: la cámara deja `LensModel "----"`, `FocalLength 0`,
`FNumber 0`, `Sony:LensType 65535`) se implementan las dos vías:

- **Dentro del ARW** (`ExifWriter.writeLensExifRaw`): el ARW es un TIFF
  (IFD0 → 0x8769 Exif IFD → MakerNote Sony; SR2; datos del sensor). Se
  añade al final del fichero un Exif IFD nuevo (entradas antiguas tal cual +
  las nuestras) y se cambian solo los 4 bytes del puntero 0x8769, con
  `RandomAccessFile`: no se leen ni reescriben los 24 MB (la cámara tiene
  poca memoria) y si se corta a medias el fichero sigue siendo el de la
  cámara (el puntero se cambia lo último, tras `sync`). Conserva la fecha.
  Verificado con el ARW real: 4 bytes cambiados + 488 añadidos, las 2107
  etiquetas restantes idénticas, decodificación rawpy/LibRaw bit a bit igual,
  `raw-identify` (LibRaw: darktable, RawTherapee) ya lee
  `Lens: Helios 44-2 58mm f/2`.
- **Sidecar XMP** (`XmpSidecar`): `DSC01837.XMP` (8.3) con
  `exifEX:LensModel`, `aux:Lens`, `exif:FocalLength` (solo fijos) y
  `exif:FNumber`; es lo que Lightroom/Bridge/Capture One leen al importar,
  aunque tomen el objetivo del MakerNote. Un `.XMP` que no escribió
  LensCatalog (`x:xmptk`) no se sobrescribe.
- El tagger cuenta la foto como etiquetada si cualquiera de las dos vías
  funciona; el registro dice el resultado de cada una
  (`tagger: raw DSC00001.ARW exif=ok sidecar=DSC00001.XMP`).
- Test: `app/test/exif-test.sh` con ARW sintético (+ `ARW_SAMPLE=` para uno
  real); `tour.sh` dispara RAW+JPEG (`LC_ARW=` para usar uno real).

Pendiente en cámara: `exiftool` deja `Composite:LensID` en 65535 porque lo
saca del `LensType` del MakerNote de Sony, que no se toca a propósito (es un
bloque binario cifrado en parte); qué muestra Lightroom sin el sidecar
depende de si prefiere el MakerNote o el EXIF — con el sidecar, el XMP manda.

## v0.3.2 — lo que dijo la cámara (AINTFILM.LOG de Berto, 2026-10-08)

Berto, en la A7 II: el etiquetado da 0/0/0 (ni JPG ni ARW) y la focal del
SteadyShot no se aplica nunca, ni con la app abierta.

### Etiquetado: el reloj de Android está en 1970
El registro de la cámara lo deja claro: todas las líneas de LensCatalog llevan
`1970-01-01 00:0x` (el Android de la cámara cuenta desde el encendido, no
tiene la hora), mientras que las fotos llevan la fecha real
(`DSC02070.JPG mtime=1791485510000` = 8-oct-2026). Las sesiones se marcaban
con `System.currentTimeMillis()` → ninguna foto caía en ninguna sesión → las
46 candidatas acababan en `no session for …`, que no se contaba en pantalla.

Arreglo (`CardSeq`): las sesiones se delimitan por la **numeración de la
cámara**, no por el tiempo. Clave de una foto = carpeta·100000 + número
(`100MSDCF/DSC02070` → 10002070). Al aplicar un objetivo (y al detectar uno
electrónico, y en el corte del etiquetador) se anota `CardSeq.next()` = la
siguiente foto que hará la cámara; una sesión cubre `[startKey, endKey)`. Un
RAW+JPEG comparten número. Las sesiones de antes (sin claves) siguen casando
por tiempo. El resultado muestra ahora "Sin objetivo: N" (fotos hechas antes de
aplicar ninguno). El tour reproduce la cámara: pone el reloj del sim en 1970 y
dispara con `adb push` (que conserva la fecha real del fichero).

### SteadyShot: nunca se pasaba a manual
Los stubs de OpenMemories (`CameraEx$ParametersModifier`) tienen
`setAntiHandBlurInfo("manual" | "from-lens")` (= menú Ajuste SteadyShot:
Manual/Auto) además de la focal; la app solo ponía la focal, que con
"from-lens" y un objetivo sin contactos se ignora. Ahora: info=manual + focal
redondeada a la lista del menú de la A7 II (8…1000 mm; empate → la más
corta), confirmado con su propio `setParameters` (antes iba dentro del bloque
de corrección de lente: si este fallaba, no se confirmaba nada) y **leído de
vuelta**: el registro dice lo que la cámara tiene
(`SteadyShot: asked manual 60 mm (lens 58 mm); camera holds info=… focal=… mode=…`)
y el resultado avisa si no cuadra o si el SteadyShot está apagado. Diagnóstico
muestra info/modo/focal y los valores admitidos. Los mensajes de `Sony` van
ahora también al registro de la tarjeta (antes solo a logcat).

### Almacén de ajustes (librería nativa)
`liblcstore.so` (jni/, el driver de aintfilm-sony/OpenMemories, MIT; NDK
r16b, armeabi): Diagnóstico → "Volcar ajustes de la cámara" escribe el
almacén en `/AINTFILM/STORE001.TXT` (solo lectura). Si la focal no
sobreviviera al salir de la app: volcar, cambiar en el menú Ajuste SteadyShot
→ focal (p. ej. 50 → 85), volcar otra vez, y `app/tools/store-diff.py`
dice el slot; entonces LensCatalog lo escribiría como aintfilm escribe los
suyos. La app deja de ser "Java puro": `build.sh` compila la librería si hay
NDK (sin NDK, construye sin ella).

## v0.3.3 — segundo registro de la cámara (2026-10-08): JPG, nombre del objetivo, reloj

Con la 0.3.2 en la A7 II (AINTFILM.LOG de Berto): el SteadyShot ya entra
(`SteadyShot: asked manual 60 mm (lens 58 mm); camera holds info=manual
focal=60 mode=onetime`), `setExifInfo ok`, las fotos ya casan con la sesión
por numeración (`tagger: session Helios 44M-4 58mm 1:2`), el ARW se
etiqueta… y el JPG falla:
`FAIL DSC02073.JPG: FileNotFoundException: …/DSC02073.JPG.exiftmp: open
failed: ENOENT`. La tarjeta de la cámara solo admite nombres 8.3 (regla de
aintfilm-sony, confirmada): el temporal del JPG no podía crearse.

- **JPG**: el temporal es ahora `DSC02073.TMP` (`ExifWriter.tempFor`, 8.3
  mayúsculas) y la reescritura es en *streaming* (solo las cabeceras en
  memoria, copia por búfer de 64 KB): antes se cargaba el JPG entero (10-15
  MB) más una copia, en un heap pequeño. Si falla a medias, el `.TMP` se
  borra y el original queda intacto.
- **Fabricante y modelo**: Berto ve el ARW etiquetado pero "sin fabricante ni
  modelo". Se escribía solo `LensModel` (0xA434). Ahora también `LensMake`
  (0xA433, la marca del catálogo; vacío en datos manuales),
  `LensSpecification` (0xA432: rango focal + f) y `MaxApertureValue` (0x9205,
  APEX) — lo que leen Windows ("Fabricante/Modelo del objetivo"), Lightroom
  ("Lens") y exiftool. El XMP lleva `exifEX:LensMake` y `aux:LensInfo`. Las
  sesiones (`LensLog`) guardan marca y rango (columnas 10-12; los ficheros
  antiguos siguen cargando). Qué programa usa Berto y qué campo lee
  (MakerNote de Sony vs EXIF) sigue pendiente: ver la investigación en el PR.
- **Reloj**: la fecha de la cámara es correcta (las fotos la llevan), pero el
  Android de dentro arranca en 1970. El registro se marcaba con ese reloj.
  `com.sony.scalar.sysutil.TimeUtil.getCurrentCalendar()` (stubs de
  OpenMemories: `PlainCalendar` con year/month/day/hour/minute/second) da la
  hora real de la cámara: `AppLog` la usa por reflexión
  (`Sony.cameraLocalMillis`, mes tomado como 1-12; si saliera un mes de menos,
  es 0-11), y sin ella marca `boot+HH:MM:SS` (tiempo desde el encendido) en
  vez de una fecha falsa. Diagnóstico muestra "Reloj de la cámara".
- **Catálogo propio**: `/DCIM/LENSES/lenses.json` nunca pudo verse en la
  cámara (no es 8.3): se acepta `LENSES.JSN`, y la forma en que la tarjeta
  muestra un `lenses.json` copiado desde el PC (`LENSES~1.JSO`).
- El `msdos` de Linux no sirve para simular la tarjeta (trunca los nombres
  largos en vez de rechazarlos, y los devuelve en minúsculas); el tour audita
  al final que todo lo que la app dejó en la tarjeta es 8.3, y el test de host
  cubre el nombre temporal.

### Revisión adversaria, XMP incrustado y qué lee cada programa

Antes de cerrar la 0.3.3 pasó por una revisión adversaria (workflow) y una
investigación de qué campo lee cada programa. Resultado:

- **Bloqueante confirmado y corregido**: si la tarjeta rechazaba
  `renameTo(TMP → JPG)`, el *fallback* borraba el original antes de volver a
  intentar el renombrado, y el `finally` borraba el temporal: con un segundo
  fallo la foto desaparecía. Ahora el original no se toca hasta que el nuevo
  está en su sitio: si el renombrado por encima falla, el original pasa a
  `DSC02073.OLD`, el nuevo entra, y solo entonces se borra el `.OLD`. Si ni
  eso puede, el `.OLD` vuelve a su nombre; y si tampoco, el error dice
  «left as DSC02073.OLD» y la siguiente pasada lo recupera antes de
  etiquetar. `app/test/rename_shim.c` (LD_PRELOAD sobre `rename`/`renameat`)
  inyecta los tres fallos en `exif-test.sh`: etiquetado vía `.OLD`; fallo
  total con foto byte-idéntica y sin restos; y recuperación en la pasada
  siguiente.
- **Quién lee qué** (documentación y foros de cada programa, 2026-10-08):
  Lightroom muestra `LensModel` del EXIF del propio raw (no tiene campo para
  el fabricante; una foto ya importada necesita *Metadatos › Leer metadatos
  del archivo*; el `aux:Lens` del sidecar es su respaldo). Capture One nombra
  los objetivos Sony por su propia base de datos a partir del `LensType` del
  MakerNote, y para uno adaptado compone «58mm f/2» con `FocalLength` y
  `MaxApertureValue` (por eso se escriben). **El Explorador de Windows** —
  lo más probable, por el «fabricante y modelo» que Berto echa en falta —
  lee «Modelo del objetivo» y «Fabricante del objetivo» *solo* del XMP
  incrustado en el archivo, espacio de nombres `MicrosoftPhoto`
  (`http://ns.microsoft.com/photo/1.0/`): ni del EXIF ni del sidecar. macOS
  (ImageIO) lee `LensModel` del EXIF. Imaging Edge de Sony puede negarse a
  abrir un ARW tocado por otro programa (sin verificar; el sensor y el
  MakerNote están byte a byte).
- **XMP incrustado**: `XmpSidecar.packet(lens)` es ahora un solo paquete
  con `exifEX:*`, `aux:*`, `MicrosoftPhoto:*`, `exif:FocalLength` y
  `exif:FNumber`, marcado `x:xmptk="LensCatalog"` para que una pasada
  posterior sepa que puede sustituirlo. En el **JPG** va como APP1
  (`http://ns.adobe.com/xap/1.0/\0`) detrás del Exif, sustituyendo el nuestro
  de una pasada anterior y respetando uno ajeno (entonces no se añade). En el
  **ARW** va como etiqueta 0x02BC (BYTE) en IFD0: como IFD0 crece una entrada,
  se añade al final del archivo una copia del IFD0 (con su enlace al IFD
  siguiente), el nuevo Exif IFD y el paquete, y lo único que cambia de los
  bytes de la cámara son los 4 del desplazamiento del IFD0 en la cabecera
  (bytes 4-7; se escriben los últimos, tras `sync`, para que un corte deje el
  archivo como estaba). El test comprueba que el cambio en el ARW se limita a
  esos bytes y que rawpy decodifica lo mismo.
- Menores de la misma revisión: la clave `tagged_` de PhotoTagger lleva la
  carpeta (`100MSDCF/DSC00001.JPG`), que antes chocaba entre carpetas;
  `LensLog` guarda en temporal y renombra, y conserva 60 sesiones en vez de
  20; la auditoría 8.3 del tour comprueba que la lista no está vacía.
- Pendiente de Berto: qué programa usa (si es el Explorador, con la 0.3.3 ya
  debería verse; si es Lightroom, «Leer metadatos del archivo»).

## Catálogo ampliado (2026-10-08) y logos de marca

- `tools/extend_catalog.py` añade 769 objetivos y 14 marcas nuevas a
  `lenses.json` (1187 → 1956 lentes, 48 → 62 marcas, 185 → 296 KB). Solo
  datos factuales (marca, modelo, montura, focal, apertura máx.); las
  entradas existentes no se tocan y el script es idempotente (salta lo que
  ya existe por marca+modelo+montura). Monturas nuevas: AR, Fujica X, QBM,
  Praktica B, Topcon RE, R. Probar en cámara que 296 KB de JSON cargan bien.
- `tools/gen_brand_logos.py` genera `docs/brand-logos/<marca>.svg` (62 logos
  originales 64×64, colores RGBA4444 exactos) + `index.json`. Pendiente:
  dibujarlos a la izquierda de cada marca en `MenuView` (la cámara no
  renderiza SVG: habrá que rasterizarlos a PNG o replicar las formas).
- Datos dudosos heredados de lensfun (no corregidos): p. ej. Leica
  `Elmarit-M 1:2.8/28 Asph.` figura con `max_aperture` 2.0.

## v0.4.0 — objetivos propios desde la cámara, teclado en pantalla, carpeta propia (2026-10-08)

Petición de Berto: añadir un modelo nuevo desde la propia app, con un teclado
para escribir nombre y fabricante; guardarlo en la app (no solo en la SD, por
si cambia de tarjeta) y también en la SD, para que esos modelos lleguen a la
app; y que el registro sea solo de LensCatalog, no compartido con aintfilm.

### Teclado (`Keyboard.java`, `MenuView.drawKeyboard`)
- La cámara no tiene pantalla táctil ni entrada de texto: el teclado es una
  rejilla QWERTY de 11 unidades que se recorre con la cruceta (con vuelta en
  los bordes), la rueda avanza tecla a tecla, y el botón central pulsa. Arriba
  los dígitos y el retroceso; tres filas de letras con los signos que llevan
  los nombres de objetivos (- . / : ( ) ' +); mayúsculas abajo a la izquierda
  como en un teclado real, espacio ancho y «Listo» ancho. MENU también acepta.
- Mayúsculas en tres estados, como en un móvil: apagado / una vez / fijo. Un
  campo vacío empieza en «una vez», para que el nombre salga con su capital.
- Al cambiar de fila, el cursor conserva su columna (el *anchor*): bajar por
  la barra espaciadora y volver a subir devuelve a la misma letra.
- El modelo es Java puro, sin `android.*`: `app/test/unit-test.sh` prueba
  la rejilla, los movimientos, mayúsculas, el límite de longitud y «Listo»,
  más la limpieza de `UserLenses`. La vista solo dibuja.

### Formulario «Añadir objetivo» (ST_ADDLENS)
- Filas: marca, modelo, montura, tipo (fijo/zoom), focal (o mín. y máx.),
  apertura máxima, Guardar. Marca y montura: ◀ ▶ recorren las que conoce el
  catálogo, OK abre el teclado; el modelo, OK abre el teclado. Focal con la
  misma aceleración del formulario manual. La apertura, de una lista más
  fina que la del manual (0.95 … 22).
- Guardar comprueba: sin modelo o sin marca, selecciona el campo y lo dice
  bajo el formulario. Una marca escrita con otra caja (`canon`) se escribe
  como la del catálogo (`Canon`), para que el objetivo quede bajo ella.
- El objetivo propio sale en la lista de su marca con un lápiz, lleva el chip
  PROPIO en su ficha, y allí tiene «Editar objetivo» (mismo id: favoritos y
  corrección se conservan) y «Borrar objetivo», que pide OK dos veces (la
  primera cambia la fila a «¿Borrar? Pulsa OK otra vez»; moverse la desarma).
  Al borrar se limpian también su favorito, su último usado y su corrección
  (`Store.forget`).

### Dónde se guardan (`UserLenses.java`)
- Copia de referencia: `files/mylenses.json` en el almacenamiento de la app
  (sobrevive al cambio de tarjeta). Copia en la tarjeta:
  `/LENSCAT/MYLENSES.JSN`, 8.3, en el formato exacto de `assets/lenses.json`
  (`brands` › `models`, con `_source` que dice de dónde viene), para leerlo en
  el PC y pasar las entradas a la siguiente versión de la app.
- Al arrancar se lee la de la app y se le suma lo que la tarjeta tenga y la
  app no (ids nuevos): una reinstalación, o un segundo cuerpo con la misma
  tarjeta, recupera los objetivos. Si falta la copia de la tarjeta (tarjeta
  nueva), se vuelve a escribir. Si la de la tarjeta no se puede leer (una
  edición a mano rota), se aparta como `MYLENSES.BAD` y se escribe la nuestra:
  nada de nadie se pierde.
- Escritura atómica (temporal + renombrado, con el *fallback* de la tarjeta
  que no renombra encima). Límite de 500 objetivos; campos limpiados (espacios,
  caracteres de control, longitudes 24/60/12, focal 1-3000, rango ordenado,
  apertura a dos decimales). Los ids son `my-<marca>-<modelo>-<montura>`.
- Diagnóstico muestra «Objetivos propios: N · tarjeta OK» (o el error).
- Nota: al editar un objetivo cuya apertura viniera de un archivo editado a
  mano con un valor que no está en la lista (f/2.9), el selector la ajusta al
  más cercano.

### Carpeta y registro propios
- Todo lo que la app escribe en la tarjeta va a `/LENSCAT`: `LENSCAT.LOG` (y
  `LENSCAT.OLD` al rotar), `MYLENSES.JSN`, los volcados `STORE001.TXT`… y, en
  el simulador, `SIM/LENS.TXT`. Antes iba a `/AINTFILM`, la carpeta de
  aintfilm. No se migra nada: aintfilm sigue escribiendo allí lo suyo.
- El tour comprueba al final que `/AINTFILM` no existe y que
  `/LENSCAT/LENSCAT.LOG` lleva el arranque de la app.

### Tour
- Paso 12 nuevo: escribe «Meyer-Optik» / «Oreston 50mm f/1.8» / «M42» con el
  teclado (la secuencia de teclas se calcula en el propio tour a partir de la
  misma rejilla y las mismas reglas del cursor: una línea de teclas por
  carácter), elige f/1.8, guarda, comprueba el JSON de la tarjeta y la copia de
  la app, aplica el objetivo, dispara y etiqueta (LensMake/LensModel en el
  JPG), simula tarjeta nueva (borra el JSN: sigue y se reescribe), simula
  reinstalación (`pm clear`: vuelve desde la tarjeta), y lo borra con OK dos
  veces. La auditoría 8.3 es el paso 13 e incluye `/LENSCAT`.
