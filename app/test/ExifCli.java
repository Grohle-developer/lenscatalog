package com.lenscatalog;

/**
 * ExifWriter from the command line, for the host test (exif-test.sh):
 *   java com.lenscatalog.ExifCli PHOTO "Lens model" FOCAL_MM F_NUMBER
 * A .JPG gets its EXIF rewritten; an .ARW gets its EXIF set in place and an
 * XMP sidecar, as the tagger does. Prints OK, or ERR and the reason.
 * FOCAL_MM 0 leaves FocalLength alone (a zoom).
 */
public class ExifCli {
    public static void main(String[] a) {
        int focal = Integer.parseInt(a[2]);
        double f = Double.parseDouble(a[3]);
        String err;
        if (a[0].toLowerCase().endsWith(".arw")) {
            err = ExifWriter.writeLensExifRaw(a[0], a[1], focal, f);
            String side = XmpSidecar.write(new java.io.File(a[0]), a[1], focal, f);
            if (err == null && side != null) err = "sidecar: " + side;
        } else {
            err = ExifWriter.writeLensExif(a[0], a[1], focal, f);
        }
        System.out.println(err == null ? "OK" : "ERR " + err);
    }
}
