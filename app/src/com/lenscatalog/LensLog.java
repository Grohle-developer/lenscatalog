package com.lenscatalog;

import android.content.Context;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * History of lens sessions: the 20 most recent plus the current one.
 * Each session records when a lens was mounted/selected and when it was
 * replaced, so photos can later be matched to the lens by timestamp.
 * Electronic lenses are logged too (and skipped for EXIF writing, as the
 * camera already writes their EXIF). Sessions with zero photos are kept;
 * the caller can count them as "mounted but unused".
 *
 * Stored as a simple line-based format in the app's private files dir
 * (no JSON library on API 10): one session per line, fields separated
 * by \t, newlines stripped from names.
 */
final class LensLog {
    /** Maximum sessions kept (plus the current one). */
    static final int MAX = 20;

    static final class Session {
        String id;           // catalog id, "manual:<focal>", or "electronic"
        String displayName;  // human-readable, or electronic lens name
        int focal;           // mm, 0 if unknown
        double aperture;     // max aperture, 0 if unknown
        long startTime;      // millis, when the session started
        long endTime;        // millis, 0 if this is the current session
        int photoCount;      // photos tagged during this session
        boolean electronic;  // true: native lens, EXIF already written by camera

        boolean isCurrent() { return endTime == 0; }
    }

    private final java.io.File file;
    private final List<Session> sessions = new ArrayList<Session>();

    LensLog(Context ctx) {
        file = new java.io.File(ctx.getFilesDir(), "lenslog.txt");
        load();
    }

    /** Start a new session, ending the current one. */
    synchronized void startSession(String id, String displayName, int focal,
                                   double aperture, boolean electronic) {
        long now = System.currentTimeMillis();
        endCurrentSession(now);
        Session s = new Session();
        s.id = id;
        s.displayName = displayName;
        s.focal = focal;
        s.aperture = aperture;
        s.startTime = now;
        s.endTime = 0;
        s.photoCount = 0;
        s.electronic = electronic;
        sessions.add(s);
        prune();
        save();
    }

    /** End the current session (if any) at the given time. */
    synchronized void endCurrentSession(long now) {
        for (Session s : sessions) {
            if (s.isCurrent()) s.endTime = now;
        }
        save();
    }

    /** End the current session now. */
    void endCurrentSession() {
        endCurrentSession(System.currentTimeMillis());
    }

    /** The current session, or null. */
    synchronized Session current() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session s = sessions.get(i);
            if (s.isCurrent()) return s;
        }
        return null;
    }

    /** All sessions, oldest first. */
    synchronized List<Session> all() {
        return new ArrayList<Session>(sessions);
    }

    /** Sessions that had no photos taken (mounted but unused). */
    synchronized int unusedCount() {
        int n = 0;
        for (Session s : sessions) {
            if (!s.isCurrent() && s.photoCount == 0 && !s.electronic) n++;
        }
        return n;
    }

    /** Increment the photo count of the session active at the given time. */
    synchronized void countPhoto(long photoTime, boolean[] tagged) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session s = sessions.get(i);
            if (s.startTime <= photoTime && (s.isCurrent() || photoTime < s.endTime)) {
                s.photoCount++;
                if (tagged != null && tagged.length > 0) tagged[0] = true;
                save();
                return;
            }
        }
    }

    /**
     * Find the session active at the given time, or null.
     * STRICT: only completed sessions (endTime != 0) with
     * startTime <= time < endTime. The tagger closes the current
     * session before matching, so open-ended sessions never match.
     * Electronic sessions are returned too; the caller decides to skip them.
     */
    synchronized Session sessionAt(long time) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session s = sessions.get(i);
            if (!s.isCurrent() && s.startTime <= time && time < s.endTime) return s;
        }
        return null;
    }

    private void prune() {
        // Keep MAX finished sessions plus the current one.
        int finished = 0;
        for (Session s : sessions) if (!s.isCurrent()) finished++;
        for (int i = 0; finished > MAX && i < sessions.size(); i++) {
            if (!sessions.get(i).isCurrent()) {
                sessions.remove(i);
                i--;
                finished--;
            }
        }
    }

    private void load() {
        sessions.clear();
        if (!file.exists()) return;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            String data = new String(buf, 0, n, "UTF-8");
            for (String line : data.split("\n")) {
                line = line.trim();
                if (line.length() == 0) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 8) continue;
                Session s = new Session();
                s.id = f[0];
                s.displayName = f[1];
                s.focal = Integer.parseInt(f[2]);
                s.aperture = Double.parseDouble(f[3]);
                s.startTime = Long.parseLong(f[4]);
                s.endTime = Long.parseLong(f[5]);
                s.photoCount = Integer.parseInt(f[6]);
                s.electronic = "1".equals(f[7]);
                sessions.add(s);
            }
        } catch (Throwable t) {
            AppLog.e("LensLog.load", t);
        }
        prune();
    }

    private void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Session s : sessions) {
                sb.append(s.id.replace("\t", " ")).append('\t')
                  .append(s.displayName.replace("\t", " ").replace("\n", " ")).append('\t')
                  .append(s.focal).append('\t')
                  .append(s.aperture).append('\t')
                  .append(s.startTime).append('\t')
                  .append(s.endTime).append('\t')
                  .append(s.photoCount).append('\t')
                  .append(s.electronic ? "1" : "0").append('\n');
            }
            byte[] buf = sb.toString().getBytes("UTF-8");
            FileOutputStream out = new FileOutputStream(file);
            out.write(buf);
            out.close();
        } catch (Throwable t) {
            AppLog.e("LensLog.save", t);
        }
    }
}
