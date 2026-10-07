package com.lenscatalog;

/**
 * ExifWriter from the command line, for the host test (exif-test.sh):
 *   java com.lenscatalog.ExifCli PHOTO "Lens model" FOCAL_MM F_NUMBER [MAKE [FOCAL_MIN FOCAL_MAX]]
 * A .JPG gets its EXIF rewritten; an .ARW gets its EXIF set in place and an
 * XMP sidecar, as the tagger does. Prints OK, or ERR and the reason.
 * FOCAL_MM 0 leaves FocalLength alone (a zoom).
 *   java com.lenscatalog.ExifCli --tempname PHOTO     prints the temporary file a JPEG is rewritten through
 */
public class ExifCli {
    public static void main(String[] a) {
        if (a[0].equals("--tempname")) {
            System.out.println(ExifWriter.tempFor(new java.io.File(a[1])).getName());
            return;
        }
        int focal = Integer.parseInt(a[2]);
        double f = Double.parseDouble(a[3]);
        String make = a.length > 4 ? a[4] : "";
        int min = a.length > 6 ? Integer.parseInt(a[5]) : focal, max = a.length > 6 ? Integer.parseInt(a[6]) : focal;
        ExifWriter.Lens lens = new ExifWriter.Lens(make, a[1], focal, f, min, max);
        String err;
        if (a[0].toLowerCase().endsWith(".arw")) {
            err = ExifWriter.writeLensExifRaw(a[0], lens);
            String side = XmpSidecar.write(new java.io.File(a[0]), lens);
            if (err == null && side != null) err = "sidecar: " + side;
        } else {
            err = ExifWriter.writeLensExif(a[0], lens);
        }
        System.out.println(err == null ? "OK" : "ERR " + err);
    }
}
