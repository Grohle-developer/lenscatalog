package com.lenscatalog;

import java.io.File;

/**
 * Where a photograph is in the camera's own sequence: its folder number and
 * file number (100MSDCF/DSC02070.JPG -> 100 * 100000 + 2070). Lens sessions
 * are bounded by these, not by time: on the A7 II, Android's clock runs from
 * 1970 at every power-on (the camera's log, 0.3.1), while the photographs
 * carry the camera's real date, so no time taken in the app can be compared
 * with a file's. The numbers only grow as the camera shoots, and a RAW+JPEG
 * pair shares one. No android.* import but the card's path, given by the caller.
 */
final class CardSeq {
    private CardSeq() {}

    /** The card's DCIM, under the external storage first, then where other Sony cards mount. */
    static String[] roots(String externalStorage) {
        return new String[] {
            externalStorage + "/DCIM",
            "/mnt/sdcard/DCIM",
            "/sdcard/DCIM",
            "/mnt/sdcard/external_sd/DCIM",
            "/Removable/MicroSD/DCIM",
        };
    }

    /** The photograph's place in the sequence, or -1 when its folder or name are not the camera's. */
    static long key(File f) {
        File dir = f.getParentFile();
        if (dir == null) return -1;
        int folder = leadingNumber(dir.getName());
        int number = trailingNumber(f.getName());
        if (folder < 100 || folder > 999 || number < 0) return -1;
        return folder * 100000L + number;
    }

    /** One past the last photograph on the card: where the next one will be. 0 on an empty card. */
    static long next(String externalStorage) {
        long max = -1;
        for (String root : roots(externalStorage)) {
            File[] dirs = new File(root).listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String[] names = d.list();
                if (names == null) continue;
                for (String n : names) {
                    if (!PhotoTagger.isPhoto(n.toLowerCase())) continue;
                    long k = key(new File(d, n));
                    if (k > max) max = k;
                }
            }
            if (max >= 0) break;
        }
        return max + 1;
    }

    /** "100MSDCF" -> 100; -1 without three leading digits. */
    static int leadingNumber(String s) {
        if (s.length() < 3) return -1;
        for (int i = 0; i < 3; i++) if (!Character.isDigit(s.charAt(i))) return -1;
        return Integer.parseInt(s.substring(0, 3));
    }

    /** "DSC02070.JPG" -> 2070: the digits just before the extension; -1 without any. */
    static int trailingNumber(String name) {
        int dot = name.lastIndexOf('.');
        int end = dot > 0 ? dot : name.length(), start = end;
        while (start > 0 && Character.isDigit(name.charAt(start - 1)) && end - start < 5) start--;
        if (start == end) return -1;
        return Integer.parseInt(name.substring(start, end));
    }
}
