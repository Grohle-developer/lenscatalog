package com.lenscatalog;

import android.hardware.Camera;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
     * Apply the chosen lens: IBIS focal length + pre-capture EXIF.
     * {@code numericExifFocal}: write the numeric EXIF FocalLength tag.
     * False for zooms: a range is not a rational, and writing the wide end
     * would be misleading; the range travels in lensName/LensModel instead
     * (e.g. "Tokina 28-70mm f/2.8"). IBIS always gets a number (the wide
     * end for zooms: the safe direction, it under-corrects if the user
     * zooms in afterwards). Decision: Berto 2026-10-06.
     * Returns a one-line human summary of what happened (or would happen).
     */
    String apply(String lensDisplayName, int focal, double maxAperture, boolean numericExifFocal) {
        if (!isCamera()) {
            i("SIM setAntiHandBlurFocalLength(" + focal + ")");
            i("SIM setExifInfo(lensName=\"" + lensDisplayName + "\", focal="
                    + (numericExifFocal ? focal + "/1" : "SKIPPED (zoom range)") + ", writeMode=true"
                    + (maxAperture > 0 ? ", fNumber~" + maxAperture : "") + ")");
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
            camera.setParameters(p);
        } catch (Throwable t) {
            e("apply", t);
        }
        return done.toString();
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
