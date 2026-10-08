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
    static final int MAX = 60;

    static final class Session {
        String id;           // catalog id, "manual:<focal>", or "electronic"
        String displayName;  // human-readable, or electronic lens name
        int focal;           // mm, 0 if unknown
        double aperture;     // max aperture, 0 if unknown
        long startTime;      // millis, when the session started
        long endTime;        // millis, 0 if this is the current session
        int photoCount;      // photos tagged during this session
        boolean electronic;  // true: native lens, EXIF already written by camera
        // Where the session is in the camera's photo sequence (CardSeq.key):
        // its photographs are startKey <= key < endKey. -1: not known (a
        // session logged before 0.3.2, or still open).
        long startKey = -1, endKey = -1;
        // The lens as the tags want it (0.3.3): its maker ("" when none or
        // unknown) and its focal range (0 when unknown).
        String make = "";
        int focalMin, focalMax;

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
        startSession(id, displayName, focal, aperture, electronic, -1);
    }

    /** A new session whose photographs start at `key` in the camera's sequence (CardSeq.next). */
    synchronized void startSession(String id, String displayName, int focal,
                                   double aperture, boolean electronic, long key) {
        startSession(id, displayName, focal, aperture, electronic, key, "", focal, focal);
    }

    /** The same, with the lens's maker and focal range (what EXIF's LensMake and LensSpecification say). */
    synchronized void startSession(String id, String displayName, int focal,
                                   double aperture, boolean electronic, long key,
                                   String make, int focalMin, int focalMax) {
        long now = System.currentTimeMillis();
        endCurrentSession(now, key);
        Session s = new Session();
        s.id = id;
        s.displayName = displayName;
        s.focal = focal;
        s.aperture = aperture;
        s.startTime = now;
        s.endTime = 0;
        s.photoCount = 0;
        s.electronic = electronic;
        s.startKey = key;
        s.make = make == null ? "" : make;
        s.focalMin = focalMin;
        s.focalMax = focalMax;
        sessions.add(s);
        prune();
        save();
    }

    /** End the current session (if any) at the given time. */
    synchronized void endCurrentSession(long now) {
        endCurrentSession(now, -1);
    }

    /** End the current session at `now`, its photographs ending before `key` in the camera's sequence. */
    synchronized void endCurrentSession(long now, long key) {
        for (Session s : sessions) {
            if (s.isCurrent()) {
                s.endTime = now;
                s.endKey = key;
            }
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

    /**
     * The session a photograph belongs to by its place in the camera's
     * sequence (CardSeq.key), or null: completed sessions only, the newest
     * first, startKey <= key < endKey. Clock-free, unlike sessionAt.
     */
    synchronized Session sessionAtKey(long key) {
        if (key < 0) return null;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session s = sessions.get(i);
            if (!s.isCurrent() && s.startKey >= 0 && s.endKey >= 0 && s.startKey <= key && key < s.endKey) return s;
        }
        return null;
    }

    /** One more photograph in a session. */
    synchronized void countPhotoIn(Session s) {
        s.photoCount++;
        save();
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
                if (f.length >= 10) {
                    s.startKey = Long.parseLong(f[8]);
                    s.endKey = Long.parseLong(f[9]);
                }
                if (f.length >= 13) {
                    s.make = f[10];
                    s.focalMin = Integer.parseInt(f[11]);
                    s.focalMax = Integer.parseInt(f[12]);
                }
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
                  .append(s.electronic ? "1" : "0").append('\t')
                  .append(s.startKey).append('\t')
                  .append(s.endKey).append('\t')
                  .append(s.make.replace("\t", " ").replace("\n", " ")).append('\t')
                  .append(s.focalMin).append('\t')
                  .append(s.focalMax).append('\n');
            }
            byte[] buf = sb.toString().getBytes("UTF-8");
            // through a temporary file, so a crash mid-write leaves the old log, not an empty one
            java.io.File tmp = new java.io.File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(buf);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            AppLog.e("LensLog.save", t);
        }
    }
}
