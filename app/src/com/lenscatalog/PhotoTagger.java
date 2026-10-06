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
 * untagged JPEG to the lens session active when it was shot (by timestamp),
 * and writes LensModel/FocalLength/FNumber. Electronic-lens sessions are
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

        List<File> photos = listPhotos();
        r.scanned = photos.size();
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
            if (mtime > newest) newest = mtime;
            if (mtime <= lastRun) continue;
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) continue;
            if (p.getBoolean("tagged_" + f.getName(), false)) {
                r.alreadyTagged++;
                continue;
            }
            LensLog.Session s = log.sessionAt(mtime);
            if (s == null) continue; // no lens recorded for that time
            boolean[] counted = new boolean[1];
            log.countPhoto(mtime, counted);
            if (s.electronic) {
                r.skippedElectronic++;
                p.edit().putBoolean("tagged_" + f.getName(), true).commit();
                continue;
            }
            String err = ExifWriter.writeLensExif(f.getAbsolutePath(),
                    s.displayName, s.focal, s.aperture);
            if (err == null) {
                r.tagged++;
                p.edit().putBoolean("tagged_" + f.getName(), true).commit();
            } else {
                r.failed++;
                AppLog.i("tagger: " + f.getName() + ": " + err);
            }
        }
        p.edit().putLong("tag_last_run", newest).commit();
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    /** All JPEGs under the card's DCIM tree (100MSDCF and friends). */
    private List<File> listPhotos() {
        List<File> out = new ArrayList<File>();
        // Primary: /DCIM on the external storage; Sony also uses /PRIVATE.
        String[] roots = {
            "/mnt/sdcard/DCIM",
            "/sdcard/DCIM",
            android.os.Environment.getExternalStorageDirectory() + "/DCIM",
        };
        for (String root : roots) {
            File d = new File(root);
            if (d.isDirectory()) {
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
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) out.add(f);
            }
        }
    }
}
