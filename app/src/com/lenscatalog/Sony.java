package com.lenscatalog;

import android.hardware.Camera;
import android.util.Log;

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
    private static final String TAG = "LensCatalog";

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
        if (!isCamera()) return "";
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
            i("SIM setAntiHandBlurFocalLength(" + focal + ")");
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
        try {
            Camera.Parameters p = camera.getParameters();
            Object mod = cameraEx.getClass()
                    .getMethod("createParametersModifier", Camera.Parameters.class)
                    .invoke(cameraEx, p);
            // IBIS focal length
            try {
                mod.getClass().getMethod("setAntiHandBlurFocalLength", int.class).invoke(mod, focal);
                done.append("IBIS=").append(focal).append("mm ");
                i("setAntiHandBlurFocalLength(" + focal + ")");
            } catch (Throwable t) {
                e("setAntiHandBlurFocalLength", t);
            }
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

    private static void setLensCorrection(Object mod, boolean on) throws Exception {
        mod.getClass().getMethod("setLensCorrection", boolean.class).invoke(mod, on);
    }

    private static void setLensCorrectionLevel(Object mod, String key, int level)
            throws Exception {
        mod.getClass().getMethod("setLensCorrectionLevel", String.class, int.class)
                .invoke(mod, key, level);
    }

    /** Diagnostic lines: which Sony EXIF/correction APIs exist on this body. */
    List<String> diagnose() {
        List<String> out = new ArrayList<String>();
        out.add("Build.MODEL=" + android.os.Build.MODEL);
        if (!isCamera()) {
            out.add("SIM: no Sony framework");
            out.add("setExifInfo: SKIPPED");
            out.add("isSupportedExifInfo: SKIPPED");
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
            out.add("setLensCorrection: "
                    + (findMethod(mod.getClass(), "setLensCorrection", 1) != null
                            ? "EXISTS" : "MISSING"));
            out.add("setLensCorrectionLevel: "
                    + (findMethod(mod.getClass(), "setLensCorrectionLevel", 2) != null
                            ? "EXISTS" : "MISSING"));
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

    private void i(String s) {
        Log.i(TAG, s);
        log.append("I ").append(s).append('\n');
    }

    private void e(String what, Throwable t) {
        Log.e(TAG, what + ": " + t);
        log.append("E ").append(what).append(": ").append(t).append('\n');
    }
}
