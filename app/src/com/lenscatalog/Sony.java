package com.lenscatalog;

import android.hardware.Camera;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Sony's CameraEx by reflection (as Recipe Lab does). On the camera every
 * call below is real; in the simulator none of the classes exist, so the
 * calls degrade to logged "would call" lines and the UI keeps working.
 */
final class Sony {

    private Object cameraEx;
    private Camera camera;
    /** null: not tried yet; false: no Sony framework (the simulator). */
    private Boolean present;
    private final StringBuilder log = new StringBuilder();

    // -------------------------------------------- lens correction (manual)
    // Parameter keys of CameraEx$ParametersModifier (public constants in the
    // OpenMemories stubs; the values are the camera's own parameter names —
    // note Sony's "distotion" typo, kept verbatim). Order: shading (5),
    // chromatic aberration (2), distortion (2), as in Sony's Lens
    // Compensation app manual.
    static final String[] LC_KEYS = {
        "shading-w", "shading-wm", "shading-cr", "shading-cb", "shading-cm",
        "chroma-r", "chroma-b", "distotion", "distotion-m",
    };
    static final int LC_N = 9;
    /** Simulator fallback range; on the camera the real min/max is queried
     * per key via getMin/MaxLensCorrectionLevel. */
    private static final int LC_SIM_MIN = -20, LC_SIM_MAX = 20;

    // -------------------------------------------- SteadyShot (IBIS)
    // CameraEx$ParametersModifier (OpenMemories stubs): the focal length only
    // counts when SteadyShot takes it from the user, AntiHandBlurInfo
    // "manual" (the menu's SteadyShot Adjust: Manual); with "from-lens" the
    // body asks the lens, which an adapted manual lens cannot answer. 0.2-0.3
    // never set it, so the focal was ignored.
    static final String IBIS_MANUAL = "manual";
    /** The focal lengths the A7 II's SteadyShot Adjust: Manual offers. */
    static final int[] IBIS_FOCALS = { 8, 10, 12, 16, 18, 20, 24, 28, 30, 35, 40, 50, 60, 70, 85, 100,
        120, 135, 150, 180, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 1000 };

    /** The SteadyShot focal nearest a lens's; on a tie the shorter one (it under-corrects, the safe side). */
    static int ibisFocal(int focal) {
        int best = IBIS_FOCALS[0];
        for (int f : IBIS_FOCALS) {
            if (Math.abs(f - focal) < Math.abs(best - focal)) best = f;
        }
        return best;
    }

    /** Open the camera's framework. Returns false in the simulator. */
    boolean open() {
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Method open = cx.getMethod("open", int.class,
                    Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions"));
            cameraEx = open.invoke(null, 0, null);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
            present = Boolean.TRUE;
            i("CameraEx open");
            return true;
        } catch (ClassNotFoundException e) {
            present = Boolean.FALSE; // the simulator
            i("no Sony framework: simulator mode");
            return false;
        } catch (Throwable t) {
            present = Boolean.FALSE;
            e("open", t);
            return false;
        }
    }

    boolean isCamera() { return Boolean.TRUE.equals(present); }

    /**
     * The electronic lens's name, "" when no electronic lens is attached
     * (dumb adapter), null when unknown (simulator: treated as none).
     */
    String lensName() {
        if (!isCamera()) return simLensName();
        try {
            Object info = cameraEx.getClass().getMethod("getLensInfo").invoke(cameraEx);
            if (info == null) return "";
            String name = strOf(info, "getLensName", "LensName");
            return name == null ? "" : name;
        } catch (Throwable t) {
            e("getLensInfo", t);
            return "";
        }
    }

    /**
     * Simulator only (no Sony framework): an electronic lens is "mounted" when
     * /AINTFILM/SIM/LENS.TXT on the card holds its name, so the electronic-lens
     * screen and session can be tested off the camera. Never read on the camera.
     */
    private String simLensName() {
        try {
            java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "AINTFILM/SIM/LENS.TXT");
            if (!f.isFile() || f.length() > 256) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            String name = new String(b, 0, n, "UTF-8").trim();
            if (name.length() > 0) i("SIM electronic lens from LENS.TXT: " + name);
            return name;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Per-key [min,max] for the 9 correction levels. On the camera the real
     * range is queried per key (getMin/MaxLensCorrectionLevel); in the
     * simulator a fixed fallback is returned (UI testing only).
     */
    int[][] lensCorrectionRanges() {
        int[][] r = new int[LC_N][2];
        if (!isCamera()) {
            for (int i = 0; i < LC_N; i++) { r[i][0] = LC_SIM_MIN; r[i][1] = LC_SIM_MAX; }
            return r;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            Object mod = cameraEx.getClass()
                    .getMethod("createParametersModifier", Camera.Parameters.class)
                    .invoke(cameraEx, p);
            Method getMin = mod.getClass().getMethod("getMinLensCorrectionLevel", String.class);
            Method getMax = mod.getClass().getMethod("getMaxLensCorrectionLevel", String.class);
            for (int i = 0; i < LC_N; i++) {
                try {
                    r[i][0] = ((Integer) getMin.invoke(mod, LC_KEYS[i])).intValue();
                    r[i][1] = ((Integer) getMax.invoke(mod, LC_KEYS[i])).intValue();
                } catch (Throwable t) {
                    r[i][0] = LC_SIM_MIN; r[i][1] = LC_SIM_MAX;
                }
            }
            i("lensCorrectionRanges queried");
        } catch (Throwable t) {
            e("lensCorrectionRanges", t);
            for (int i = 0; i < LC_N; i++) { r[i][0] = LC_SIM_MIN; r[i][1] = LC_SIM_MAX; }
        }
        return r;
    }

    /**
     * Apply the chosen lens: IBIS focal length + pre-capture EXIF.
     * {@code numericExifFocal}: write the numeric EXIF FocalLength tag.
     * False for zooms: a range is not a rational, and writing the wide end
     * would be misleading; the range travels in lensName/LensModel instead
     * (e.g. "Tokina 28-70mm f/2.8"). IBIS always gets a number (the wide
     * end for zooms: the safe direction, it under-corrects if the user
     * zooms in afterwards). Decision: Berto 2026-10-06.
     * {@code lcEnabled}: manual lens correction on/off (Sony's Lens
     * Compensation model); {@code lcLevels}: the 9 stored levels, applied
     * only when enabled. Disabled writes setLensCorrection(false) so a
     * previous lens's values cannot linger.
     * Returns a one-line human summary of what happened (or would happen).
     */
    String apply(String lensDisplayName, int focal, double maxAperture, boolean numericExifFocal,
            boolean lcEnabled, int[] lcLevels) {
        if (!isCamera()) {
            i("SIM setAntiHandBlurInfo(manual) setAntiHandBlurFocalLength(" + ibisFocal(focal) + ") // lens " + focal + " mm");
            i("SIM setExifInfo(lensName=\"" + lensDisplayName + "\", focal="
                    + (numericExifFocal ? focal + "/1" : "SKIPPED (zoom range)") + ", writeMode=true"
                    + (maxAperture > 0 ? ", fNumber~" + maxAperture : "") + ")");
            i("SIM commit 1: setLensCorrection(false) // drop latch");
            if (lcEnabled && lcLevels != null) {
                StringBuilder sb = new StringBuilder("SIM commit 2: levels then setLensCorrection(true):");
                for (int i = 0; i < LC_N && i < lcLevels.length; i++)
                    sb.append(' ').append(LC_KEYS[i]).append('=').append(lcLevels[i]);
                i(sb.toString());
            } else {
                i("SIM correction stays OFF");
            }
            return "SIM";
        }
        StringBuilder done = new StringBuilder();
        // SteadyShot first, committed on its own: whatever the EXIF or the
        // lens correction does after, the focal length is in.
        int ibis = ibisFocal(focal);
        try {
            Camera.Parameters p0 = camera.getParameters();
            Object m0 = createModifier(p0);
            try {
                m0.getClass().getMethod("setAntiHandBlurInfo", String.class).invoke(m0, IBIS_MANUAL);
            } catch (Throwable t) {
                e("setAntiHandBlurInfo(manual)", t);
            }
            m0.getClass().getMethod("setAntiHandBlurFocalLength", int.class).invoke(m0, ibis);
            camera.setParameters(p0);
            // what the camera holds now
            Object mr = createModifier(camera.getParameters());
            String info = String.valueOf(call(mr, "getAntiHandBlurInfo"));
            String mode = String.valueOf(call(mr, "getAntiHandBlurMode"));
            Object got = call(mr, "getAntiHandBlurFocalLength");
            i("SteadyShot: asked manual " + ibis + " mm (lens " + focal + " mm); camera holds info=" + info
                    + " focal=" + got + " mode=" + mode);
            if (IBIS_MANUAL.equals(info) && got != null && ((Integer) got).intValue() == ibis) {
                done.append("IBIS=").append(ibis).append("mm ");
            } else {
                done.append("IBISNO=").append(info).append('/').append(got).append(' ');
            }
            if ("off".equals(mode)) done.append("IBISOFF ");
        } catch (Throwable t) {
            e("SteadyShot", t);
        }
        try {
            Camera.Parameters p = camera.getParameters();
            Object mod = cameraEx.getClass()
                    .getMethod("createParametersModifier", Camera.Parameters.class)
                    .invoke(cameraEx, p);
            // EXIF, guarded
            boolean supported = false;
            try {
                supported = Boolean.TRUE.equals(
                        mod.getClass().getMethod("isSupportedExifInfo").invoke(mod));
            } catch (Throwable t) {
                e("isSupportedExifInfo", t);
            }
            if (supported) {
                try {
                    writeExif(mod, lensDisplayName, focal, maxAperture, numericExifFocal);
                    done.append("EXIF=ok");
                    i("setExifInfo ok");
                } catch (Throwable t) {
                    e("setExifInfo", t);
                }
            } else {
                i("ExifInfo not supported: skipped");
            }
            // Manual lens correction (Sony's Lens Compensation model).
            // The driver only re-reads the manual levels on the off->on
            // transition: re-setting levels while the correction is already
            // ON is ignored (only toggling OFF took effect). So commit 1
            // below goes out with the correction OFF, and commit 2 (fresh
            // params, levels first, then ON) forces the transition every time.
            try {
                setLensCorrection(mod, false);
                camera.setParameters(p);
                if (lcEnabled && lcLevels != null) {
                    Camera.Parameters p2 = camera.getParameters();
                    Object mod2 = createModifier(p2);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < LC_N && i < lcLevels.length; i++) {
                        setLensCorrectionLevel(mod2, LC_KEYS[i], lcLevels[i]);
                        sb.append(LC_KEYS[i]).append('=').append(lcLevels[i]).append(' ');
                    }
                    setLensCorrection(mod2, true);
                    camera.setParameters(p2);
                    done.append("LC=on ");
                    i("setLensCorrection off->on: " + sb.toString().trim());
                } else {
                    done.append("LC=off ");
                    i("setLensCorrection(false)");
                }
            } catch (Throwable t) {
                e("setLensCorrection", t);
            }
        } catch (Throwable t) {
            e("apply", t);
        }
        return done.toString();
    }

    private Object createModifier(Camera.Parameters p) throws Exception {
        return cameraEx.getClass()
                .getMethod("createParametersModifier", Camera.Parameters.class)
                .invoke(cameraEx, p);
    }

    /** A getter by name, or null when the camera has not got it. */
    private Object call(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            e(name, t);
            return null;
        }
    }

    private static void setLensCorrection(Object mod, boolean on) throws Exception {
        mod.getClass().getMethod("setLensCorrection", boolean.class).invoke(mod, on);
    }

    private static void setLensCorrectionLevel(Object mod, String key, int level)
            throws Exception {
        mod.getClass().getMethod("setLensCorrectionLevel", String.class, int.class)
                .invoke(mod, key, level);
    }

    /** SteadyShot as the camera holds it now, one line (for the store dumps); "" off the camera. */
    String steadyShot() {
        if (!isCamera()) return "";
        try {
            Object m = createModifier(camera.getParameters());
            return "SteadyShot info=" + call(m, "getAntiHandBlurInfo") + " focal=" + call(m, "getAntiHandBlurFocalLength")
                    + " mode=" + call(m, "getAntiHandBlurMode");
        } catch (Throwable t) {
            return "SteadyShot: " + t;
        }
    }

    /** Diagnostic lines: which Sony EXIF/correction APIs exist on this body. */
    List<String> diagnose() {
        List<String> out = new ArrayList<String>();
        out.add("Build.MODEL=" + android.os.Build.MODEL);
        if (!isCamera()) {
            out.add("SIM: no Sony framework");
            out.add("setExifInfo: SKIPPED");
            out.add("isSupportedExifInfo: SKIPPED");
            out.add("SteadyShot: SKIPPED");
            return out;
        }
        try {
            out.add("CameraEx.setExifInfo: "
                    + (findMethod(cameraEx.getClass(), "setExifInfo", 1) != null
                            ? "EXISTS" : "MISSING"));
            Camera.Parameters p = camera.getParameters();
            Object mod = createModifier(p);
            Method sup = findMethod(mod.getClass(), "isSupportedExifInfo", 0);
            if (sup == null) {
                out.add("isSupportedExifInfo: MISSING");
            } else {
                out.add("isSupportedExifInfo: " + sup.invoke(mod));
            }
            out.add("setAntiHandBlurFocalLength: "
                    + (findMethod(mod.getClass(), "setAntiHandBlurFocalLength", 1) != null
                            ? "EXISTS" : "MISSING"));
            out.add("SteadyShot info: " + call(mod, "getAntiHandBlurInfo")
                    + " of " + call(mod, "getSupportedAntiHandBlurInfos"));
            out.add("SteadyShot mode: " + call(mod, "getAntiHandBlurMode")
                    + " of " + call(mod, "getSupportedAntiHandBlurModes"));
            out.add("SteadyShot focal: " + call(mod, "getAntiHandBlurFocalLength") + " mm");
            out.add("setLensCorrection: "
                    + (findMethod(mod.getClass(), "setLensCorrection", 1) != null
                            ? "EXISTS" : "MISSING"));
            out.add("setLensCorrectionLevel: "
                    + (findMethod(mod.getClass(), "setLensCorrectionLevel", 2) != null
                            ? "EXISTS" : "MISSING"));
            // Try a real setExifInfo invocation with dummy values and report
            // whether the reflection chain itself works (not whether it persists).
            try {
                writeExif(mod, "DiagTest", 50, 1.8, true);
                out.add("setExifInfo invoke: OK (no exception)");
            } catch (Throwable t) {
                out.add("setExifInfo invoke: FAILED: " + t.toString());
            }
        } catch (Throwable t) {
            out.add("ERROR: " + t.toString());
        }
        return out;
    }

    private static Method findMethod(Class<?> c, String name, int nParams) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == nParams)
                return m;
        }
        return null;
    }

    private void writeExif(Object mod, String lensName, int focal, double maxAperture,
            boolean numericFocal) throws Exception {
        Method setExif = null;
        Object target = null;
        for (Object o : new Object[] { cameraEx, mod }) {
            for (Method m : o.getClass().getMethods()) {
                if (m.getName().equals("setExifInfo") && m.getParameterTypes().length == 1) {
                    setExif = m;
                    target = o;
                    break;
                }
            }
            if (setExif != null) break;
        }
        if (setExif == null) throw new NoSuchMethodException("setExifInfo");
        Class<?> exifType = setExif.getParameterTypes()[0];
        Object exif = exifType.newInstance();
        if (numericFocal) {
            setField(exif, "focalLengthNumer", focal);
            setField(exif, "focalLengthDenom", 1);
        }
        // else: zoom — numeric FocalLength deliberately unwritten (see apply()).
        setField(exif, "lensName", lensName);
        setField(exif, "writeMode", true);
        if (maxAperture > 0) {
            int num = (int) Math.round(maxAperture * 10);
            setField(exif, "fNumberNumer", num);
            setField(exif, "fNumberDenom", 10);
            setField(exif, "fNumberMinNumer", num);
            setField(exif, "fNumberMinDenom", 10);
        }
        setExif.invoke(target, exif);
    }

    /** Field first, then a JavaBean setter. */
    private static void setField(Object o, String name, Object value) {
        try {
            Field f = o.getClass().getField(name);
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(o, ((Number) value).intValue());
            else if (t == boolean.class) f.setBoolean(o, (Boolean) value);
            else f.set(o, value);
            return;
        } catch (Throwable ignored) { }
        try {
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (Method m : o.getClass().getMethods()) {
                if (m.getName().equals(setter) && m.getParameterTypes().length == 1) {
                    Class<?> t = m.getParameterTypes()[0];
                    Object v = value;
                    if (t == int.class || t == Integer.class) v = ((Number) value).intValue();
                    else if (t == boolean.class || t == Boolean.class) v = (Boolean) value;
                    m.invoke(o, v);
                    return;
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String strOf(Object o, String getter, String field) {
        try {
            Object v = o.getClass().getMethod(getter).invoke(o);
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) { }
        try {
            Object v = o.getClass().getField(field).get(o);
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) { }
        return null;
    }

    void close() {
        if (cameraEx == null) return;
        try {
            cameraEx.getClass().getMethod("release").invoke(cameraEx);
        } catch (Throwable t) {
            e("release", t);
        }
        cameraEx = null;
        camera = null;
    }

    String logText() { return log.toString(); }

    // Through AppLog: the card's /AINTFILM/AINTFILM.LOG (what the camera can
    // tell us) and logcat (its sink).
    private void i(String s) {
        AppLog.i(s);
        log.append("I ").append(s).append('\n');
    }

    private void e(String what, Throwable t) {
        AppLog.i("ERROR " + what + ": " + t);
        log.append("E ").append(what).append(": ").append(t).append('\n');
    }
}
