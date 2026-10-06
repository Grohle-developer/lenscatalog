package com.lenscatalog;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A log on the memory card (/AINTFILM/AINTFILM.LOG), so that what happened on a
 * camera nobody can attach a debugger to can be read on a computer afterwards.
 * Kept under 512 KB by rolling once to AINTFILM.OLD. Both names are 8.3 and
 * upper case: the camera's card takes no other kind (Names.is83).
 *
 * Every line also goes to the {@link Sink} the activity installs (logcat), so
 * this class, and the caches that log through it, compile and are tested on a
 * bare JDK (tools/test.sh).
 */
final class AppLog {
    private AppLog() {}

    /** Where a line goes besides the card. */
    interface Sink {
        void line(String msg);
    }

    static final String NAME = "AINTFILM.LOG";
    static volatile Sink sink;
    private static File file;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    static synchronized void init(File dir) {
        dir.mkdirs();
        file = new File(dir, NAME);
        if (file.length() > 512 * 1024) {
            File old = new File(dir, "AINTFILM.OLD");
            old.delete();
            file.renameTo(old);
            file = new File(dir, NAME);
        }
    }

    static synchronized void i(String msg) {
        Sink k = sink;
        if (k != null) k.line(msg);
        if (file == null) return;
        try {
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write((TIME.format(new Date()) + "  " + msg + "\n").getBytes("UTF-8"));
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
