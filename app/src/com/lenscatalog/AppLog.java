package com.lenscatalog;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A log on the memory card (/LENSCAT/LENSCAT.LOG: the app's own folder, shared
 * with no other app), so that what happened on a camera nobody can attach a
 * debugger to can be read on a computer afterwards. Kept under 512 KB by
 * rolling once to LENSCAT.OLD. Both names are 8.3 and upper case: the camera's
 * card takes no other kind.
 *
 * Every line also goes to the {@link Sink} the activity installs (logcat), so
 * this class compiles and is tested on a bare JDK.
 *
 * The stamp: on the camera, Android's own clock starts at 1970 at every
 * power-on (the camera keeps the real date elsewhere, and writes it into the
 * photographs), so lines are stamped with the camera's clock when the activity
 * has read it (Sony's TimeUtil, {@link #setCameraClock}), and otherwise, while
 * Android's clock still says 1970, with the time since power-on ("boot+").
 */
final class AppLog {
    private AppLog() {}

    /** Where a line goes besides the card. */
    interface Sink {
        void line(String msg);
    }

    /** The app's folder on the card, and the log in it. */
    static final String DIR = "LENSCAT", NAME = "LENSCAT.LOG", OLD = "LENSCAT.OLD";
    static volatile Sink sink;
    private static File file;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    /** The camera's clock is local time with no zone: formatted as it is. */
    private static final SimpleDateFormat CAMERA_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    static {
        CAMERA_TIME.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    /** 2000-01-01: an Android clock before it has not been set (the camera's counts from power-on). */
    private static final long CLOCK_SET = 946684800000L;
    /** The camera's clock (its local time, as millis since 1970 UTC) at androidBase, or Long.MIN_VALUE. */
    private static long cameraBase = Long.MIN_VALUE, androidBase;

    static synchronized void init(File dir) {
        dir.mkdirs();
        file = new File(dir, NAME);
        if (file.length() > 512 * 1024) {
            File old = new File(dir, OLD);
            old.delete();
            file.renameTo(old);
            file = new File(dir, NAME);
        }
    }

    /**
     * The camera's clock, as the activity read it: its local time, as millis
     * since 1970 UTC (Sony.cameraLocalMillis). Long.MIN_VALUE: not available.
     * From here on, lines are stamped with it, advanced by Android's clock.
     */
    static synchronized void setCameraClock(long cameraLocalMillis) {
        cameraBase = cameraLocalMillis;
        androidBase = System.currentTimeMillis();
    }

    /** Whether the camera's clock is known. */
    static synchronized boolean hasCameraClock() { return cameraBase != Long.MIN_VALUE; }

    /** The stamp of a line written now. */
    static synchronized String stamp() {
        long now = System.currentTimeMillis();
        if (cameraBase != Long.MIN_VALUE) return CAMERA_TIME.format(new Date(cameraBase + (now - androidBase)));
        if (now < CLOCK_SET) {
            long s = now / 1000;
            return String.format(Locale.US, "boot+%02d:%02d:%02d.%03d", s / 3600, (s / 60) % 60, s % 60, now % 1000);
        }
        return TIME.format(new Date(now));
    }

    /** What the clock says now, for the diagnostics screen. */
    static synchronized String clockText() {
        String s = stamp();
        return s.substring(0, s.length() - 4) + (cameraBase != Long.MIN_VALUE ? "" : s.startsWith("boot+") ? "" : " (Android)");
    }

    static synchronized void i(String msg) {
        Sink k = sink;
        if (k != null) k.line(msg);
        if (file == null) return;
        try {
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write((stamp() + "  " + msg + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            // a card that is gone or full must not take the app with it
        }
    }

    static void e(String msg, Throwable t) { i(msg + ": " + t); }

    /** Last n lines of the log file (for the in-app viewer). */
    static synchronized java.util.List<String> tail(int n) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (file == null || !file.exists()) return out;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            String line;
            // Keep only the last n: simple ring buffer.
            java.util.LinkedList<String> buf = new java.util.LinkedList<String>();
            while ((line = r.readLine()) != null) {
                buf.add(line);
                if (buf.size() > n) buf.removeFirst();
            }
            r.close();
            out.addAll(buf);
        } catch (Throwable t) {
            // ignore
        }
        return out;
    }
}
