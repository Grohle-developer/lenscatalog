package com.lenscatalog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/**
 * The screen the app is drawn on. The camera's framebuffer is 640x480 on every
 * screen it drives, but the screens are not all 4:3: a 16:9 or 3:2 panel shows
 * those pixels wider than they are tall, and a UI drawn for square pixels comes
 * out stretched. Sony's DisplayManager says the active screen's shape (the LCD,
 * the viewfinder, HDMI), and tells when it changes; the UI is then laid out
 * on a canvas as wide as that shape at 480 high (853 for 16:9, 720 for 3:2,
 * 640 for 4:3) and squeezed into the framebuffer, so that a circle is round on
 * the glass. Reflection, as for CameraEx: in the simulator none of it exists,
 * and pixels are square. No android.* import: the arithmetic is tested on a
 * bare JDK.
 */
final class Screen {
    interface Listener {
        void onScreenChanged();
    }

    private static final String DM = "com.sony.scalar.hardware.avio.DisplayManager";

    private Object manager;
    /** Physical width / height of the active screen; 0 when the camera does not say. */
    volatile float aspect;
    /** Where the live image sits, in framebuffer pixels {left, top, right, bottom}; null when unknown. */
    volatile int[] video;
    String device = "";

    /** Ask the camera, and listen for the viewfinder taking over from the LCD and back. */
    void open(final Listener listener, final Executor ui) {
        try {
            Class<?> dm = Class.forName(DM);
            manager = dm.newInstance();
            final Class<?> li = Class.forName(DM + "$DisplayEventListener");
            Object proxy = Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[] { li }, new InvocationHandler() {
                public Object invoke(Object self, Method m, Object[] args) {
                    String n = m.getName();
                    if (n.equals("hashCode")) return System.identityHashCode(self);
                    if (n.equals("equals")) return self == args[0];
                    if (n.equals("toString")) return "aintfilm screen listener";
                    if (n.equals("onDeviceStatusChanged")) {
                        ui.execute(new Runnable() {
                            public void run() {
                                read();
                                listener.onScreenChanged();
                            }
                        });
                    }
                    return null;
                }
            });
            dm.getMethod("setDisplayStatusListener", li).invoke(manager, proxy);
            read();
        } catch (ClassNotFoundException e) {
            manager = null; // the simulator
        } catch (Throwable t) {
            manager = null;
            AppLog.e("display", t);
        }
    }

    /** The active screen's shape and the live image's place on it, logged: what the UI is laid out for. */
    void read() {
        if (manager == null) return;
        try {
            Class<?> dm = manager.getClass();
            device = String.valueOf(dm.getMethod("getActiveDevice").invoke(manager));
            Object info = dm.getMethod("getDeviceInfo", String.class).invoke(manager, device);
            int w = info.getClass().getField("res_w").getInt(info), h = info.getClass().getField("res_h").getInt(info);
            int a = info.getClass().getField("aspect").getInt(info);
            aspect = aspectOf(a);
            int[] v = null;
            try {
                Object r = dm.getMethod("getDisplayedVideoRect").invoke(manager);
                if (r != null) {
                    Class<?> rc = r.getClass();
                    v = new int[] { rc.getField("pxLeft").getInt(r), rc.getField("pxTop").getInt(r),
                            rc.getField("pxRight").getInt(r), rc.getField("pxBottom").getInt(r) };
                }
            } catch (Throwable t) {
                v = null;
            }
            video = v != null && v[2] > v[0] && v[3] > v[1] ? v : null;
            AppLog.i("screen " + device + ": " + w + "x" + h + ", aspect code " + a + " (" + aspect + "), live image "
                    + (v == null ? "?" : v[0] + "," + v[1] + "-" + v[2] + "," + v[3]));
        } catch (Throwable t) {
            AppLog.e("display info", t);
        }
    }

    /** DisplayManager.ASPECT_RATIO_*: 1 3:2, 2 16:9, 3 4:3, 4 5:3, 5 11:9. */
    static float aspectOf(int code) {
        switch (code) {
            case 1: return 3f / 2f;
            case 2: return 16f / 9f;
            case 3: return 4f / 3f;
            case 4: return 5f / 3f;
            case 5: return 11f / 9f;
            default: return 0f;
        }
    }

    /**
     * The width of the canvas the UI is laid out on, at `logicalH` high, for a
     * framebuffer of fbW x fbH: as wide as the glass is, so that what is drawn
     * keeps its shape. Square pixels when the camera does not say.
     */
    static int logicalWidth(float aspect, int fbW, int fbH, int logicalH) {
        if (fbW <= 0 || fbH <= 0) return logicalH * 4 / 3;
        float a = aspect > 0.5f && aspect < 3f ? aspect : (float) fbW / fbH;
        return Math.round(logicalH * a);
    }

    // ------------------------------------------------------------ colour depth
    /*
     * Apps are shown through a framebuffer of four bits a channel (RGBA4444)
     * unless they ask Sony's Gpelibrary for eight (ABGR8888). Sixteen levels a
     * channel turn a photograph into bands, and the camera dithers nothing. The
     * screens that show photographs ask for eight bits (PMCADemo asks for them
     * in every activity it has, its live view's included); the live view, where
     * the app draws only its own marks over the camera's image, stays on what
     * the camera set, which is what Recipe Lab leaves alone. Leaving the app puts
     * the four bits back. With eight bits refused, or turned off in the menu,
     * the views are dithered onto the sixteen levels instead (Native.screenDither).
     */
    private static final String GPE = "com.sony.scalar.sysutil.didep.Gpelibrary";
    /** The levels a channel the photographs are drawn for: 256, or 16 on the camera's own four bits. */
    static final int LEVELS_HIGH = 256, LEVELS_LOW = 16;
    private Boolean high; // null: never asked
    private boolean refused;

    /**
     * Ask for eight bits a channel (true) or the camera's own four. Returns
     * whether the screen has eight now. Off the camera nothing is asked, and
     * the answer is the one wanted: the simulator then draws what each depth
     * would show (sixteen levels being exactly what the camera's four bits keep).
     */
    boolean depth(boolean wantHigh, boolean camera) {
        if (!camera) {
            high = wantHigh;
            return wantHigh;
        }
        if (refused) return false;
        if (high != null && high == wantHigh) return wantHigh;
        try {
            Class<?> g = Class.forName(GPE);
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Class<Enum> t = (Class<Enum>) Class.forName(GPE + "$GS_FRAMEBUFFER_TYPE");
            @SuppressWarnings("unchecked")
            Object v = Enum.valueOf(t, wantHigh ? "ABGR8888" : "RGBA4444");
            g.getMethod("changeFrameBufferPixel", t).invoke(null, v);
            high = wantHigh;
            AppLog.i("framebuffer " + (wantHigh ? "ABGR8888" : "RGBA4444"));
            return wantHigh;
        } catch (Throwable t) {
            refused = true;
            high = false;
            AppLog.e("framebuffer depth", t);
            return false;
        }
    }

    /** Whether the camera refused eight bits a channel (it is not asked again). */
    boolean refused() { return refused; }

    /** The levels a channel the screen has now. */
    int levels() { return Boolean.TRUE.equals(high) ? LEVELS_HIGH : LEVELS_LOW; }

    void close() {
        if (manager == null) return;
        try {
            manager.getClass().getMethod("releaseDisplayStatusListener").invoke(manager);
            manager.getClass().getMethod("finish").invoke(manager);
        } catch (Throwable t) {
            AppLog.e("display close", t);
        }
        manager = null;
    }
}
