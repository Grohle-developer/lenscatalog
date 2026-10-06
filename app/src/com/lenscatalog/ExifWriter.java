package com.lenscatalog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Minimal pure-Java EXIF writer for JPEGs (API 10 has no LensModel tag).
 * Inserts or updates in IFD0:
 *   0xA434 LensModel (ASCII), 0x920A FocalLength (RATIONAL),
 *   0x829D FNumber (RATIONAL).
 * If the JPEG has no EXIF APP1 segment, one is created. Other segments
 * are preserved byte-for-byte. Returns null on success, an error string
 * otherwise. Nothing here throws.
 */
final class ExifWriter {
    private ExifWriter() {}

    private static final int TAG_LENS_MODEL = 0xA434;
    private static final int TAG_FOCAL_LENGTH = 0x920A;
    private static final int TAG_FNUMBER = 0x829D;

    /** Write lens EXIF into the JPEG at path. Null = ok. */
    static String writeLensExif(String path, String lensModel, int focalMm,
                                double fNumber) {
        try {
            byte[] jpeg = readAll(path);
            if (jpeg == null || jpeg.length < 4) return "unreadable";
            if (jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) 0xD8) return "not a JPEG";

            // Find APP1 (Exif) segment, or the position to insert one (after SOI).
            int app1Pos = -1, app1Len = 0;
            int pos = 2;
            while (pos + 4 <= jpeg.length) {
                if (jpeg[pos] != (byte) 0xFF) break;
                int marker = jpeg[pos + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0xD9) { pos += 2; continue; }
                if (marker == 0xDA) break; // SOS: image data starts
                if (pos + 4 > jpeg.length) break;
                int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
                if (len < 2 || pos + 2 + len > jpeg.length) break;
                if (marker == 0xE1 && len >= 8
                        && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x'
                        && jpeg[pos + 6] == 'i' && jpeg[pos + 7] == 'f'
                        && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                    app1Pos = pos;
                    app1Len = len;
                    break;
                }
                pos += 2 + len;
            }

            byte[] exif;
            int tiffStart;
            if (app1Pos >= 0) {
                // Existing EXIF: TIFF starts after "Exif\0\0".
                tiffStart = app1Pos + 4 + 6;
                exif = subarray(jpeg, app1Pos + 4, app1Len - 2);
            } else {
                exif = null;
                tiffStart = 0;
            }

            byte[] newExif = (exif == null)
                    ? buildExif(lensModel, focalMm, fNumber)
                    : updateExif(exif, lensModel, focalMm, fNumber);
            if (newExif == null) return "exif build failed";

            byte[] out;
            if (app1Pos >= 0) {
                // Replace the APP1 segment.
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                b.write(jpeg, 0, app1Pos);
                b.write(0xFF); b.write(0xE1);
                int segLen = newExif.length + 2;
                b.write((segLen >> 8) & 0xFF); b.write(segLen & 0xFF);
                b.write(newExif, 0, newExif.length);
                b.write(jpeg, app1Pos + 2 + app1Len, jpeg.length - (app1Pos + 2 + app1Len));
                out = b.toByteArray();
            } else {
                // Insert APP1 right after SOI.
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                b.write(jpeg, 0, 2);
                b.write(0xFF); b.write(0xE1);
                int segLen = newExif.length + 2;
                b.write((segLen >> 8) & 0xFF); b.write(segLen & 0xFF);
                b.write(newExif, 0, newExif.length);
                b.write(jpeg, 2, jpeg.length - 2);
                out = b.toByteArray();
            }

            // Write back atomically-ish: temp file then rename.
            File tmp = new File(path + ".exiftmp");
            FileOutputStream f = new FileOutputStream(tmp);
            f.write(out);
            f.close();
            File orig = new File(path);
            if (!tmp.renameTo(orig)) {
                // renameTo can fail across volumes; fall back to copy.
                FileInputStream in = new FileInputStream(tmp);
                FileOutputStream o = new FileOutputStream(orig);
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                in.close(); o.close();
                tmp.delete();
            }
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    // ---- TIFF construction ----

    /** Build a minimal EXIF APP1 body ("Exif\0\0" + TIFF) with our tags. */
    private static byte[] buildExif(String lensModel, int focalMm, double fNumber) {
        try {
            ByteArrayOutputStream tiff = new ByteArrayOutputStream();
            // TIFF header: little-endian, IFD0 at offset 8.
            tiff.write('I'); tiff.write('I');
            write16(tiff, 42);
            write32(tiff, 8);
            // IFD0 with 3 entries.
            write16(tiff, 3);
            int dataOff = 8 + 2 + 3 * 12 + 4; // after IFD
            byte[] lensBytes = (lensModel + "\0").getBytes("UTF-8");
            // LensModel (ASCII)
            write16(tiff, TAG_LENS_MODEL); write16(tiff, 2);
            write32(tiff, lensBytes.length);
            write32(tiff, dataOff);
            int p = dataOff + lensBytes.length;
            // FocalLength (RATIONAL): focal/1
            write16(tiff, TAG_FOCAL_LENGTH); write16(tiff, 5);
            write32(tiff, 1);
            write32(tiff, p);
            p += 8;
            // FNumber (RATIONAL): fNumber*100/100, or skip if unknown
            boolean hasF = fNumber > 0;
            if (hasF) {
                write16(tiff, TAG_FNUMBER); write16(tiff, 5);
                write32(tiff, 1);
                write32(tiff, p);
            } else {
                // Rewrite entry count to 2.
                byte[] b = tiff.toByteArray();
                b[8] = 2; b[9] = 0;
                tiff = new ByteArrayOutputStream();
                tiff.write(b, 0, b.length);
            }
            write32(tiff, 0); // next IFD offset
            // Data area.
            tiff.write(lensBytes, 0, lensBytes.length);
            write32(tiff, focalMm); write32(tiff, 1);
            if (hasF) {
                write32(tiff, (int) Math.round(fNumber * 100)); write32(tiff, 100);
            }
            byte[] tiffBytes = tiff.toByteArray();
            ByteArrayOutputStream exif = new ByteArrayOutputStream();
            exif.write('E'); exif.write('x'); exif.write('i'); exif.write('f');
            exif.write(0); exif.write(0);
            exif.write(tiffBytes, 0, tiffBytes.length);
            return exif.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Update an existing EXIF body: replace LensModel/FocalLength/FNumber
     * in IFD0, or append them. Handles little- and big-endian TIFF.
     */
    private static byte[] updateExif(byte[] exif, String lensModel, int focalMm,
                                     double fNumber) {
        try {
            if (exif.length < 14) return null;
            // exif[0..5] = "Exif\0\0", TIFF starts at 6.
            int t = 6;
            boolean le;
            if (exif[t] == 'I' && exif[t + 1] == 'I') le = true;
            else if (exif[t] == 'M' && exif[t + 1] == 'M') le = false;
            else return null;
            int ifd0 = t + (int) u32(exif, t + 4, le);
            int count = (int) u16(exif, ifd0, le);
            // Collect existing entries, dropping ours.
            java.util.ArrayList<int[]> entries = new java.util.ArrayList<int[]>();
            for (int i = 0; i < count; i++) {
                int e = ifd0 + 2 + i * 12;
                if (e + 12 > exif.length) return null;
                int tag = (int) u16(exif, e, le);
                if (tag == TAG_LENS_MODEL || tag == TAG_FOCAL_LENGTH || tag == TAG_FNUMBER)
                    continue;
                entries.add(new int[]{ tag, (int) u16(exif, e + 2, le),
                        (int) u32(exif, e + 4, le), (int) u32(exif, e + 8, le) });
            }
            // Rebuild IFD0: kept entries + our 3 (or 2) tags, data appended.
            // Simplest: build a fresh EXIF with our tags, then merge kept
            // entries' data by copying their value blobs. To keep it small
            // and safe, we rebuild from scratch: header + new IFD + data.
            // Kept entries that used inline values (<=4 bytes) are preserved
            // inline; entries with offsets keep their data in place and we
            // reuse the offsets (data stays where it was).
            ByteArrayOutputStream tiff = new ByteArrayOutputStream();
            tiff.write(exif, t, 8); // TIFF header (endian + 42 + ifd offset placeholder)
            // We'll write IFD at offset 8.
            int newCount = entries.size() + (fNumber > 0 ? 3 : 2);
            // Data for our new tags goes at the end.
            // First, compute data size needed for our tags.
            byte[] lensBytes = (lensModel + "\0").getBytes("UTF-8");
            int ourData = lensBytes.length + 8 + (fNumber > 0 ? 8 : 0);
            // Layout: header(8) + count(2) + entries(newCount*12) + next(4) + ourData
            int dataStart = 8 + 2 + newCount * 12 + 4;
            // Fix the IFD offset in the header copy.
            byte[] hdr = tiff.toByteArray();
            if (le) { hdr[4] = 8; hdr[5] = 0; hdr[6] = 0; hdr[7] = 0; }
            else { hdr[4] = 0; hdr[5] = 0; hdr[6] = 0; hdr[7] = 8; }
            tiff = new ByteArrayOutputStream();
            tiff.write(hdr, 0, hdr.length);
            write16le(tiff, newCount, le);
            int dp = dataStart;
            // Kept entries first (offsets unchanged, data stays in old blob —
            // but we're rebuilding, so copy their data bytes if offset-based).
            // To avoid complexity: inline small values, and for offset-based
            // kept entries, append their data to our new data area.
            java.util.ArrayList<byte[]> extraData = new java.util.ArrayList<byte[]>();
            for (int[] en : entries) {
                int tag = en[0], type = en[1], cnt = en[2], val = en[3];
                int typeSize = typeSize(type);
                int byteLen = typeSize * cnt;
                write16le(tiff, tag, le);
                write16le(tiff, type, le);
                write32le(tiff, cnt, le);
                if (byteLen <= 4) {
                    write32le(tiff, val, le); // inline
                } else {
                    // Copy data from old exif.
                    int srcOff = t + val;
                    byte[] blob = subarray(exif, srcOff, Math.min(byteLen, exif.length - srcOff));
                    write32le(tiff, dp, le);
                    extraData.add(blob);
                    dp += blob.length;
                }
            }
            // Our tags.
            write16le(tiff, TAG_LENS_MODEL, le); write16le(tiff, 2, le);
            write32le(tiff, lensBytes.length, le); write32le(tiff, dp, le);
            dp += lensBytes.length;
            write16le(tiff, TAG_FOCAL_LENGTH, le); write16le(tiff, 5, le);
            write32le(tiff, 1, le); write32le(tiff, dp, le);
            dp += 8;
            if (fNumber > 0) {
                write16le(tiff, TAG_FNUMBER, le); write16le(tiff, 5, le);
                write32le(tiff, 1, le); write32le(tiff, dp, le);
                dp += 8;
            }
            write32le(tiff, 0, le); // next IFD
            // Data area: extra kept blobs, then ours.
            for (byte[] b : extraData) tiff.write(b, 0, b.length);
            tiff.write(lensBytes, 0, lensBytes.length);
            write32le(tiff, focalMm, le); write32le(tiff, 1, le);
            if (fNumber > 0) {
                write32le(tiff, (int) Math.round(fNumber * 100), le);
                write32le(tiff, 100, le);
            }
            byte[] tiffBytes = tiff.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('E'); out.write('x'); out.write('i'); out.write('f');
            out.write(0); out.write(0);
            out.write(tiffBytes, 0, tiffBytes.length);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- helpers ----

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 7: return 1;
            case 3: return 2;
            case 4: case 9: return 4;
            case 5: case 10: return 8;
            default: return 1;
        }
    }

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    private static void write16le(ByteArrayOutputStream o, int v, boolean le) {
        if (le) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
        else { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
    }

    private static void write32le(ByteArrayOutputStream o, int v, boolean le) {
        if (le) {
            o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
            o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
        } else {
            o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF);
            o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
        }
    }

    private static long u16(byte[] b, int p, boolean le) {
        if (le) return ((b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8));
        return (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
    }

    private static long u32(byte[] b, int p, boolean le) {
        if (le) return ((b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((long) (b[p + 3] & 0xFF) << 24));
        return (((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF));
    }

    private static byte[] subarray(byte[] b, int off, int len) {
        if (off < 0 || len <= 0 || off >= b.length) return new byte[0];
        int n = Math.min(len, b.length - off);
        byte[] r = new byte[n];
        System.arraycopy(b, off, r, 0, n);
        return r;
    }

    private static byte[] readAll(String path) {
        try {
            File f = new File(path);
            long len = f.length();
            if (len <= 0 || len > 64 * 1024 * 1024) return null;
            byte[] buf = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            return n == buf.length ? buf : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read a file that RandomAccessFile can't (kept for clarity). */
    @SuppressWarnings("unused")
    private static void unused(RandomAccessFile r) {}
}
