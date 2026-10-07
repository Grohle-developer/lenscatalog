package com.lenscatalog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Post-capture EXIF tagging: walks the card's DCIM folders, matches each
 * untagged JPEG and raw file (ARW) to the lens session active when it was shot
 * (by timestamp), and writes LensModel/FocalLength/FNumber: into the JPEG's
 * EXIF; into the ARW's EXIF in place and into an XMP sidecar next to it
 * (DSC01837.XMP), so a raw converter has the lens whichever it reads. Electronic-lens sessions are
 * skipped (the camera already wrote their EXIF). Only photos newer than
 * the last run are considered, so a run over hundreds of files is a fast
 * stat walk plus EXIF writes for the new ones only.
 *
 * Nothing here throws; the result counts what happened.
 */
final class PhotoTagger {
    static final class Result {
        int scanned;
        int tagged;
        int skippedElectronic;
        int alreadyTagged;
        int failed;
        /** Photographs no lens session covers: taken before any Apply. */
        int noSession;
        long millis;
    }

    private final Context ctx;
    private final LensLog log;

    PhotoTagger(Context ctx, LensLog log) {
        this.ctx = ctx;
        this.log = log;
    }

    Result tagNewPhotos() {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        SharedPreferences p = ctx.getSharedPreferences("lenscatalog", Context.MODE_PRIVATE);
        long lastRun = p.getLong("tag_last_run", 0);
        AppLog.i("tagger: start, lastRun=" + lastRun);

        // Close the current session NOW: this freezes the time windows.
        // Photos are then matched strictly by [start, end) from the log.
        // A fresh session with the same lens starts immediately, so the
        // log stays continuous for photos taken after this run.
        String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        LensLog.Session cur = log.current();
        if (cur != null) {
            long next = CardSeq.next(ext);
            log.endCurrentSession(t0, next);
            log.startSession(cur.id, cur.displayName, cur.focal, cur.aperture, cur.electronic, next);
            AppLog.i("tagger: session breakpoint at " + t0 + ", photo " + next);
        }

        List<File> photos = listPhotos();
        r.scanned = photos.size();
        AppLog.i("tagger: scanned " + r.scanned + " photos");
        // Oldest first, so session photo counts stay chronological.
        Collections.sort(photos, new Comparator<File>() {
            public int compare(File a, File b) {
                long d = a.lastModified() - b.lastModified();
                return d < 0 ? -1 : d > 0 ? 1 : 0;
            }
        });

        long newest = lastRun;
        for (File f : photos) {
            long mtime = f.lastModified();
            if (mtime <= lastRun) continue;
            String name = f.getName().toLowerCase();
            if (!isPhoto(name)) continue;
            String key = "tagged_" + f.getName();
            if (p.getBoolean(key, false)) {
                r.alreadyTagged++;
                if (mtime > newest) newest = mtime;
                continue;
            }
            AppLog.i("tagger: candidate " + f.getAbsolutePath() + " mtime=" + mtime);
            // by the camera's numbering (clock-free); by time only for
            // sessions logged before 0.3.2, which have no numbers
            long k = CardSeq.key(f);
            LensLog.Session s = log.sessionAtKey(k);
            if (s == null) s = log.sessionAt(mtime);
            if (s == null) {
                r.noSession++;
                AppLog.i("tagger: no session for " + f.getName() + " (photo " + k + ")");
                // No session: don't advance lastRun past it, retry next time.
                continue;
            }
            AppLog.i("tagger: session " + s.displayName + " electronic=" + s.electronic);
            log.countPhotoIn(s);
            if (s.electronic) {
                r.skippedElectronic++;
                p.edit().putBoolean(key, true).commit();
                if (mtime > newest) newest = mtime;
                continue;
            }
            String err;
            if (isRaw(name)) {
                err = ExifWriter.writeLensExifRaw(f.getAbsolutePath(), s.displayName, s.focal, s.aperture);
                String side = XmpSidecar.write(f, s.displayName, s.focal, s.aperture);
                AppLog.i("tagger: raw " + f.getName() + " exif=" + (err == null ? "ok" : err)
                        + " sidecar=" + (side == null ? XmpSidecar.fileFor(f).getName() : side));
                // the lens is on record if either one took it
                if (side == null) err = null;
            } else {
                err = ExifWriter.writeLensExif(f.getAbsolutePath(), s.displayName, s.focal, s.aperture);
            }
            if (err == null) {
                r.tagged++;
                p.edit().putBoolean(key, true).commit();
                AppLog.i("tagger: OK " + f.getName());
                if (mtime > newest) newest = mtime;
            } else {
                r.failed++;
                AppLog.i("tagger: FAIL " + f.getName() + ": " + err);
                // Don't advance lastRun: retry next time.
            }
        }
        p.edit().putLong("tag_last_run", newest).commit();
        r.millis = System.currentTimeMillis() - t0;
        AppLog.i("tagger: done tagged=" + r.tagged + " already=" + r.alreadyTagged
                + " failed=" + r.failed + " skippedEl=" + r.skippedElectronic + " noSession=" + r.noSession
                + " ms=" + r.millis);
        return r;
    }

    /** A file the tagger writes: a JPEG or a raw file. */
    static boolean isPhoto(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || isRaw(lowerName);
    }

    /** Sony's raw files (TIFF-based). */
    static boolean isRaw(String lowerName) {
        return lowerName.endsWith(".arw");
    }

    /** All JPEGs and raw files under the card's DCIM tree (100MSDCF and friends). */
    private List<File> listPhotos() {
        List<File> out = new ArrayList<File>();
        // Sony A7 II: /DCIM/100MSDCF on the card. Try several roots.
        String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] roots = CardSeq.roots(ext);
        for (String root : roots) {
            File d = new File(root);
            boolean ok = d.isDirectory();
            AppLog.i("tagger: root " + root + " dir=" + ok);
            if (ok) {
                walk(d, out);
                if (!out.isEmpty()) break;
            }
        }
        return out;
    }

    private void walk(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, out);
            } else {
                if (isPhoto(f.getName().toLowerCase())) out.add(f);
            }
        }
    }
}
