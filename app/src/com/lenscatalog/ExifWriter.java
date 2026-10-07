package com.lenscatalog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal pure-Java EXIF writer for JPEGs (API 10 has no LensModel tag).
 * Sets, in the Exif sub-IFD where the standard puts them:
 *   0xA434 LensModel (ASCII), 0x920A FocalLength (RATIONAL, only when
 *   focalMm > 0), 0x829D FNumber (RATIONAL, only when fNumber > 0).
 *
 * The camera's own EXIF is kept whole. Its TIFF block is never re-laid out:
 * the bytes stay where they are, so every offset in it (the Exif IFD's values,
 * IFD1 and its thumbnail, the GPS and interoperability IFDs, Sony's MakerNote)
 * stays valid. A new Exif IFD - the old entries, minus the tags written here,
 * plus these - is appended after the old bytes, and IFD0's pointer to the Exif
 * IFD is moved to it; when IFD0 has no such pointer, IFD0 itself is copied to
 * the end with one added. The old IFDs remain as a few hundred unreferenced
 * bytes. (0.2.x rebuilt IFD0 from scratch: it lost the whole Exif IFD -
 * exposure, ISO, dates - and the thumbnail, and put the lens tags in IFD0.)
 *
 * If the JPEG has no EXIF APP1 segment, one is created. Other segments are
 * preserved byte-for-byte. Returns null on success, an error string
 * otherwise. Nothing here throws. No android.* import: tested on a bare JDK.
 */
final class ExifWriter {
    private ExifWriter() {}

    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_EXIF_VERSION = 0x9000;
    private static final int TAG_LENS_MODEL = 0xA434;
    private static final int TAG_FOCAL_LENGTH = 0x920A;
    private static final int TAG_FNUMBER = 0x829D;
    private static final int TYPE_ASCII = 2, TYPE_LONG = 4, TYPE_RATIONAL = 5, TYPE_UNDEFINED = 7;
    /** An APP1 segment's length field counts itself: 65535 - 2 bytes of payload at most. */
    private static final int MAX_APP1_PAYLOAD = 65533;

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

            // The TIFF block: after the length field and "Exif\0\0".
            byte[] tiff = app1Pos >= 0 ? subarray(jpeg, app1Pos + 10, app1Len - 8) : emptyTiff();
            byte[] newTiff = setLensTags(tiff, lensModel, focalMm, fNumber);
            if (newTiff == null) return "unreadable EXIF";
            int payload = 6 + newTiff.length;
            if (payload > MAX_APP1_PAYLOAD) return "EXIF block full (" + payload + " bytes)";

            ByteArrayOutputStream b = new ByteArrayOutputStream(jpeg.length + newTiff.length + 16);
            int keepFrom;
            if (app1Pos >= 0) {
                // Replace the APP1 segment.
                b.write(jpeg, 0, app1Pos);
                keepFrom = app1Pos + 2 + app1Len;
            } else {
                // Insert APP1 right after SOI.
                b.write(jpeg, 0, 2);
                keepFrom = 2;
            }
            b.write(0xFF); b.write(0xE1);
            int segLen = payload + 2;
            b.write((segLen >> 8) & 0xFF); b.write(segLen & 0xFF);
            b.write('E'); b.write('x'); b.write('i'); b.write('f'); b.write(0); b.write(0);
            b.write(newTiff, 0, newTiff.length);
            b.write(jpeg, keepFrom, jpeg.length - keepFrom);
            byte[] out = b.toByteArray();

            // Write back atomically-ish: temp file then rename.
            File orig = new File(path);
            long mtime = orig.lastModified();
            File tmp = new File(path + ".exiftmp");
            FileOutputStream f = new FileOutputStream(tmp);
            f.write(out);
            f.close();
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
            // The file keeps the time it was taken: the tagger matches photos
            // to lens sessions by it, and so do the camera and a computer.
            if (mtime > 0) orig.setLastModified(mtime);
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    // ---- TIFF ----

    /** A TIFF block with an empty IFD0, little-endian: what a JPEG without EXIF starts from. */
    private static byte[] emptyTiff() {
        return new byte[] { 'I', 'I', 42, 0, 8, 0, 0, 0, /* IFD0: */ 0, 0, /* next: */ 0, 0, 0, 0 };
    }

    /**
     * The TIFF block with the lens tags set in its Exif IFD; null when the
     * block cannot be read. Everything of the original stays at its offset.
     */
    static byte[] setLensTags(byte[] tiff, String lensModel, int focalMm, double fNumber) {
        try {
            if (tiff == null || tiff.length < 14) return null;
            boolean le;
            if (tiff[0] == 'I' && tiff[1] == 'I') le = true;
            else if (tiff[0] == 'M' && tiff[1] == 'M') le = false;
            else return null;
            if (u16(tiff, 2, le) != 42) return null;
            int ifd0 = (int) u32(tiff, 4, le);
            if (ifd0 < 8 || ifd0 + 2 > tiff.length) return null;
            int n0 = u16(tiff, ifd0, le);
            if (ifd0 + 2 + n0 * 12 + 4 > tiff.length) return null;

            // IFD0's pointer to the Exif IFD, if it has one.
            int exifPtrEntry = -1, exifIfd = 0;
            for (int i = 0; i < n0; i++) {
                int e = ifd0 + 2 + i * 12;
                if (u16(tiff, e, le) == TAG_EXIF_IFD) {
                    exifPtrEntry = e;
                    exifIfd = (int) u32(tiff, e + 8, le);
                }
            }

            // The old Exif IFD's entries, verbatim (their offsets stay valid),
            // minus the tags written here.
            boolean writeFocal = focalMm > 0, writeF = fNumber > 0;
            List<byte[]> entries = new ArrayList<byte[]>();
            if (exifIfd <= 0) {
                // a new Exif IFD says which version of the standard it is
                entries.add(entry(TAG_EXIF_VERSION, TYPE_UNDEFINED, 4, new byte[] { '0', '2', '3', '0' }, le));
            } else {
                if (exifIfd + 2 > tiff.length) return null;
                int n = u16(tiff, exifIfd, le);
                if (exifIfd + 2 + n * 12 > tiff.length) return null;
                for (int i = 0; i < n; i++) {
                    int e = exifIfd + 2 + i * 12;
                    int tag = u16(tiff, e, le);
                    if (tag == TAG_LENS_MODEL || (writeFocal && tag == TAG_FOCAL_LENGTH)
                            || (writeF && tag == TAG_FNUMBER)) continue;
                    entries.add(subarray(tiff, e, 12));
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(tiff.length + 1024);
            out.write(tiff, 0, tiff.length);
            if (out.size() % 2 != 0) out.write(0);

            // The new Exif IFD; the values of the tags set here follow it.
            byte[] lensBytes = (lensModel + "\0").getBytes("UTF-8");
            int count = entries.size() + 1 + (writeFocal ? 1 : 0) + (writeF ? 1 : 0);
            int exifAt = out.size();
            int dp = exifAt + 2 + count * 12 + 4;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (lensBytes.length <= 4) {
                byte[] inline = new byte[4];
                System.arraycopy(lensBytes, 0, inline, 0, lensBytes.length);
                entries.add(entry(TAG_LENS_MODEL, TYPE_ASCII, lensBytes.length, inline, le));
            } else {
                entries.add(entry(TAG_LENS_MODEL, TYPE_ASCII, lensBytes.length, u32bytes(dp + data.size(), le), le));
                data.write(lensBytes, 0, lensBytes.length);
                if (data.size() % 2 != 0) data.write(0);
            }
            if (writeFocal) {
                entries.add(entry(TAG_FOCAL_LENGTH, TYPE_RATIONAL, 1, u32bytes(dp + data.size(), le), le));
                write32(data, focalMm, le); write32(data, 1, le);
            }
            if (writeF) {
                entries.add(entry(TAG_FNUMBER, TYPE_RATIONAL, 1, u32bytes(dp + data.size(), le), le));
                write32(data, (int) Math.round(fNumber * 100), le); write32(data, 100, le);
            }
            sortByTag(entries, le);
            write16(out, entries.size(), le);
            for (byte[] e : entries) out.write(e, 0, e.length);
            write32(out, 0, le); // the Exif IFD has no next IFD
            byte[] d = data.toByteArray();
            out.write(d, 0, d.length);
            if (out.size() % 2 != 0) out.write(0);

            byte[] res;
            if (exifPtrEntry >= 0) {
                // Point IFD0's existing entry at the new Exif IFD (its value field, in place).
                res = out.toByteArray();
                put32(res, exifPtrEntry + 8, exifAt, le);
            } else {
                // IFD0 has no Exif pointer: copy IFD0 to the end with one, keeping its next-IFD (IFD1) link.
                List<byte[]> e0 = new ArrayList<byte[]>();
                for (int i = 0; i < n0; i++) e0.add(subarray(tiff, ifd0 + 2 + i * 12, 12));
                e0.add(entry(TAG_EXIF_IFD, TYPE_LONG, 1, u32bytes(exifAt, le), le));
                sortByTag(e0, le);
                int ifd0At = out.size();
                write16(out, e0.size(), le);
                for (byte[] e : e0) out.write(e, 0, e.length);
                write32(out, (int) u32(tiff, ifd0 + 2 + n0 * 12, le), le);
                res = out.toByteArray();
                put32(res, 4, ifd0At, le);
            }
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- helpers ----

    private static byte[] entry(int tag, int type, int count, byte[] value4, boolean le) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(12);
        write16(o, tag, le);
        write16(o, type, le);
        write32(o, count, le);
        o.write(value4, 0, 4);
        return o.toByteArray();
    }

    private static void sortByTag(List<byte[]> entries, final boolean le) {
        Collections.sort(entries, new Comparator<byte[]>() {
            public int compare(byte[] a, byte[] b) {
                return u16(a, 0, le) - u16(b, 0, le);
            }
        });
    }

    private static byte[] u32bytes(int v, boolean le) {
        byte[] b = new byte[4];
        put32(b, 0, v, le);
        return b;
    }

    private static void put32(byte[] b, int p, int v, boolean le) {
        if (le) {
            b[p] = (byte) v; b[p + 1] = (byte) (v >> 8);
            b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24);
        } else {
            b[p] = (byte) (v >> 24); b[p + 1] = (byte) (v >> 16);
            b[p + 2] = (byte) (v >> 8); b[p + 3] = (byte) v;
        }
    }

    private static void write16(ByteArrayOutputStream o, int v, boolean le) {
        if (le) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
        else { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
    }

    private static void write32(ByteArrayOutputStream o, int v, boolean le) {
        byte[] b = u32bytes(v, le);
        o.write(b, 0, 4);
    }

    private static int u16(byte[] b, int p, boolean le) {
        if (le) return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
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
}
