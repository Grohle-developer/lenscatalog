package com.lenscatalog;

/**
 * ExifWriter from the command line, for the host test (exif-test.sh):
 *   java com.lenscatalog.ExifCli PHOTO.JPG "Lens model" FOCAL_MM F_NUMBER
 * Prints OK, or ERR and the reason. FOCAL_MM 0 leaves FocalLength alone (a zoom).
 */
public class ExifCli {
    public static void main(String[] a) {
        String err = ExifWriter.writeLensExif(a[0], a[1], Integer.parseInt(a[2]), Double.parseDouble(a[3]));
        System.out.println(err == null ? "OK" : "ERR " + err);
    }
}
