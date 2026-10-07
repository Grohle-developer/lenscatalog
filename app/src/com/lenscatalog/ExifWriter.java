package com.lenscatalog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal pure-Java EXIF writer for JPEGs and Sony's ARW raw files (API 10
 * has no LensModel tag). Sets, in the Exif sub-IFD where the standard puts
 * them, what the camera cannot know about an adapted lens:
 *   0xA434 LensModel (ASCII), 0xA433 LensMake (ASCII, when known),
 *   0x920A FocalLength (RATIONAL, primes only), 0x829D FNumber (RATIONAL),
 *   0x9205 MaxApertureValue (RATIONAL, APEX, from the f-number) and
 *   0xA432 LensSpecification (4 RATIONALs: the focal range and its f-number).
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
 * A JPEG is rewritten through a temporary file next to it, streamed: only its
 * header segments are held in memory, never the image (the camera's heap is
 * small). The temporary file has an 8.3 name (DSC02073.TMP): the camera's card
 * takes no other kind, and 0.3.2's DSC02073.JPG.exiftmp could not be created
 * there. A raw file is written in place (writeLensExifRaw). If the JPEG has no
 * EXIF APP1 segment, one is created. Other segments are preserved
 * byte-for-byte. Returns null on success, an error string otherwise. Nothing
 * here throws. No android.* import: tested on a bare JDK.
 */
final class ExifWriter {
    private ExifWriter() {}

    /**
     * What is written about a lens. make may be "" (a lens entered by hand
     * has no maker); focalMm 0 leaves the camera's FocalLength alone (a zoom:
     * a range is not a rational); focalMin/focalMax 0 writes no
     * LensSpecification; fNumber 0 writes no FNumber or MaxApertureValue.
     */
    static final class Lens {
        final String make, model;
        final int focalMm;
        final double fNumber;
        final int focalMin, focalMax;

        Lens(String make, String model, int focalMm, double fNumber, int focalMin, int focalMax) {
            this.make = make == null ? "" : make.trim();
            this.model = model == null ? "" : model;
            this.focalMm = focalMm;
            this.fNumber = fNumber;
            this.focalMin = focalMin;
            this.focalMax = focalMax;
        }

        /** A prime (or a lens of unknown range): the focal length is the range. */
        Lens(String model, int focalMm, double fNumber) {
            this("", model, focalMm, fNumber, focalMm, focalMm);
        }
    }

    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_FNUMBER = 0x829D;
    private static final int TAG_EXIF_VERSION = 0x9000;
    private static final int TAG_FOCAL_LENGTH = 0x920A;
    private static final int TAG_MAX_APERTURE = 0x9205;
    private static final int TAG_LENS_SPEC = 0xA432;
    private static final int TAG_LENS_MAKE = 0xA433;
    private static final int TAG_LENS_MODEL = 0xA434;
    private static final int TYPE_ASCII = 2, TYPE_LONG = 4, TYPE_RATIONAL = 5, TYPE_UNDEFINED = 7;
    /** An APP1 segment's length field counts itself: 65535 - 2 bytes of payload at most. */
    private static final int MAX_APP1_PAYLOAD = 65533;
    /** The marker segments before the image data are read whole; one of this size is not a camera's. */
    private static final int MAX_HEADER = 4 * 1024 * 1024;

    // ---- JPEG ----

    /** Write lens EXIF into the JPEG at path (a prime, maker unknown). Null = ok. */
    static String writeLensExif(String path, String lensModel, int focalMm, double fNumber) {
        return writeLensExif(path, new Lens(lensModel, focalMm, fNumber));
    }

    /** Write lens EXIF into the JPEG at path. Null = ok. */
    static String writeLensExif(String path, Lens lens) {
        RandomAccessFile in = null;
        FileOutputStream out = null;
        File tmp = null;
        try {
            File orig = new File(path);
            long len = orig.length(), mtime = orig.lastModified();
            if (len < 4) return "unreadable";
            in = new RandomAccessFile(orig, "r");
            byte[] soi = read(in, len, 0, 2);
            if (soi[0] != (byte) 0xFF || soi[1] != (byte) 0xD8) return "not a JPEG";

            // Walk the marker segments to the image data (SOS): where the Exif
            // APP1 is, or where to insert one (right after SOI).
            long app1Pos = -1, pos = 2;
            int app1Len = 0;
            while (pos + 4 <= len && pos < MAX_HEADER) {
                byte[] h = read(in, len, pos, 4);
                if (h[0] != (byte) 0xFF) break;
                int marker = h[1] & 0xFF;
                if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) { pos += 2; continue; }
                if (marker == 0xDA) break; // SOS: image data starts
                int segLen = ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
                if (segLen < 2 || pos + 2 + segLen > len) break;
                if (marker == 0xE1 && segLen >= 8) {
                    byte[] id = read(in, len, pos + 4, 6);
                    if (id[0] == 'E' && id[1] == 'x' && id[2] == 'i' && id[3] == 'f' && id[4] == 0 && id[5] == 0) {
                        app1Pos = pos;
                        app1Len = segLen;
                        break;
                    }
                }
                pos += 2 + segLen;
            }

            // The TIFF block: after the length field and "Exif\0\0".
            byte[] tiff = app1Pos >= 0 ? read(in, len, app1Pos + 10, app1Len - 8) : emptyTiff();
            byte[] newTiff = setLensTags(tiff, lens);
            if (newTiff == null) return "unreadable EXIF";
            int payload = 6 + newTiff.length;
            if (payload > MAX_APP1_PAYLOAD) return "EXIF block full (" + payload + " bytes)";

            // The new file: what was before the APP1 (or just SOI), the new
            // APP1, then the rest of the old file, copied through a buffer.
            tmp = tempFor(orig);
            if (tmp.exists() && !tmp.delete()) return "cannot replace " + tmp.getName();
            out = new FileOutputStream(tmp);
            long keepFrom;
            if (app1Pos >= 0) {
                copy(in, 0, app1Pos, out);
                keepFrom = app1Pos + 2 + app1Len;
            } else {
                out.write(soi);
                keepFrom = 2;
            }
            int segLen = payload + 2;
            out.write(new byte[] { (byte) 0xFF, (byte) 0xE1, (byte) (segLen >> 8), (byte) segLen,
                'E', 'x', 'i', 'f', 0, 0 });
            out.write(newTiff);
            copy(in, keepFrom, len - keepFrom, out);
            out.getFD().sync();
            out.close();
            out = null;
            in.close();
            in = null;

            // Into place: a rename over the original where the card allows it,
            // else the original removed first, else a copy.
            if (!tmp.renameTo(orig)) {
                if (!(orig.delete() && tmp.renameTo(orig))) {
                    FileInputStream ti = new FileInputStream(tmp);
                    FileOutputStream o = new FileOutputStream(orig);
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = ti.read(buf)) > 0) o.write(buf, 0, r);
                    ti.close();
                    o.close();
                    tmp.delete();
                }
            }
            tmp = null;
            // The file keeps the time it was taken, for the camera and a computer alike.
            if (mtime > 0) orig.setLastModified(mtime);
            return null;
        } catch (Throwable t) {
            return t.toString();
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (tmp != null) tmp.delete(); // a half-written temporary never stays on the card
        }
    }

    /**
     * The temporary file a JPEG is rewritten through: next to it, with an 8.3
     * name in upper case (DSC02073.JPG -> DSC02073.TMP), the only kind the
     * camera's card takes. No camera writes .TMP, so it collides with nothing.
     */
    static File tempFor(File photo) {
        String n = photo.getName();
        int dot = n.lastIndexOf('.');
        String base = (dot > 0 ? n.substring(0, dot) : n).toUpperCase();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < base.length() && b.length() < 8; i++) {
            char c = base.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') b.append(c);
        }
        if (b.length() == 0) b.append("LENSCAT");
        return new File(photo.getParentFile(), b + ".TMP");
    }

    /** n bytes of f from pos, through a buffer. */
    private static void copy(RandomAccessFile f, long pos, long n, FileOutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        f.seek(pos);
        while (n > 0) {
            int r = f.read(buf, 0, (int) Math.min(buf.length, n));
            if (r <= 0) throw new java.io.EOFException("short read at " + pos);
            out.write(buf, 0, r);
            n -= r;
        }
    }

    // ---- TIFF ----

    /** A TIFF block with an empty IFD0, little-endian: what a JPEG without EXIF starts from. */
    private static byte[] emptyTiff() {
        return new byte[] { 'I', 'I', 42, 0, 8, 0, 0, 0, /* IFD0: */ 0, 0, /* next: */ 0, 0, 0, 0 };
    }

    static byte[] setLensTags(byte[] tiff, String lensModel, int focalMm, double fNumber) {
        return setLensTags(tiff, new Lens(lensModel, focalMm, fNumber));
    }

    /**
     * The TIFF block with the lens tags set in its Exif IFD; null when the
     * block cannot be read. Everything of the original stays at its offset.
     */
    static byte[] setLensTags(byte[] tiff, Lens lens) {
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
                    if (replaced(u16(tiff, e, le), lens)) continue;
                    entries.add(subarray(tiff, e, 12));
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(tiff.length + 1024);
            out.write(tiff, 0, tiff.length);
            if (out.size() % 2 != 0) out.write(0);
            int exifAt = out.size();
            byte[] ifd = exifIfd(entries, exifAt, lens, le);
            out.write(ifd, 0, ifd.length);

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

    /** Whether an old Exif IFD entry gives way to one written here. */
    private static boolean replaced(int tag, Lens l) {
        switch (tag) {
            case TAG_LENS_MODEL: return true;
            case TAG_LENS_MAKE: return l.make.length() > 0;
            case TAG_FOCAL_LENGTH: return l.focalMm > 0;
            case TAG_FNUMBER: case TAG_MAX_APERTURE: return l.fNumber > 0;
            case TAG_LENS_SPEC: return l.focalMin > 0 && l.focalMax > 0;
            default: return false;
        }
    }

    /**
     * A new Exif IFD to be placed at offset `at` of the TIFF: `entries` (the
     * old ones kept, verbatim) plus the lens tags, sorted by tag, then the
     * values of the lens tags. Even length, so what follows stays aligned.
     */
    private static byte[] exifIfd(List<byte[]> entries, int at, Lens l, boolean le) throws Exception {
        List<byte[]> all = new ArrayList<byte[]>(entries);
        List<Object[]> mine = new ArrayList<Object[]>(); // { tag, type, count, valueBytes }
        mine.add(new Object[] { TAG_LENS_MODEL, TYPE_ASCII, ascii(l.model) });
        if (l.make.length() > 0) mine.add(new Object[] { TAG_LENS_MAKE, TYPE_ASCII, ascii(l.make) });
        if (l.focalMm > 0) mine.add(new Object[] { TAG_FOCAL_LENGTH, TYPE_RATIONAL, rational(le, l.focalMm, 1) });
        if (l.fNumber > 0) {
            int f100 = (int) Math.round(l.fNumber * 100);
            mine.add(new Object[] { TAG_FNUMBER, TYPE_RATIONAL, rational(le, f100, 100) });
            // APEX: Av = 2 log2(N)
            int av100 = (int) Math.round(2 * Math.log(l.fNumber) / Math.log(2) * 100);
            mine.add(new Object[] { TAG_MAX_APERTURE, TYPE_RATIONAL, rational(le, av100, 100) });
        }
        if (l.focalMin > 0 && l.focalMax > 0) {
            int f100 = l.fNumber > 0 ? (int) Math.round(l.fNumber * 100) : 0, d = l.fNumber > 0 ? 100 : 0;
            mine.add(new Object[] { TAG_LENS_SPEC, TYPE_RATIONAL,
                rational(le, l.focalMin, 1, l.focalMax, 1, f100, d, f100, d) });
        }
        int count = all.size() + mine.size();
        int dp = at + 2 + count * 12 + 4;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (Object[] m : mine) {
            int tag = (Integer) m[0], type = (Integer) m[1];
            byte[] v = (byte[]) m[2];
            int n = type == TYPE_RATIONAL ? v.length / 8 : v.length;
            if (v.length <= 4) {
                byte[] inline = new byte[4];
                System.arraycopy(v, 0, inline, 0, v.length);
                all.add(entry(tag, type, n, inline, le));
            } else {
                all.add(entry(tag, type, n, u32bytes(dp + data.size(), le), le));
                data.write(v, 0, v.length);
                if (data.size() % 2 != 0) data.write(0);
            }
        }
        sortByTag(all, le);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write16(out, all.size(), le);
        for (byte[] e : all) out.write(e, 0, e.length);
        write32(out, 0, le); // the Exif IFD has no next IFD
        byte[] d = data.toByteArray();
        out.write(d, 0, d.length);
        if (out.size() % 2 != 0) out.write(0);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) throws Exception {
        return (s + "\0").getBytes("UTF-8");
    }

    /** RATIONALs, numerator/denominator pairs. */
    private static byte[] rational(boolean le, int... nd) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(nd.length * 4);
        for (int v : nd) write32(o, v, le);
        return o.toByteArray();
    }

    // ---- raw files ----

    static String writeLensExifRaw(String path, String lensModel, int focalMm, double fNumber) {
        return writeLensExifRaw(path, new Lens(lensModel, focalMm, fNumber));
    }

    /**
     * The lens tags into a TIFF-based raw file (Sony's ARW) in place. Nothing
     * of the file moves (a raw converter finds the sensor data, Sony's
     * MakerNote and SR2 block at the offsets the camera wrote): the new Exif
     * IFD is appended at the end of the file, then IFD0's 4-byte pointer to the
     * Exif IFD is moved to it. Only those few hundred bytes are read and
     * written, never the 24 MB of the file. If the write stops half way, the
     * file is still the camera's: the pointer is changed last. The file keeps
     * its time. Null = ok.
     */
    static String writeLensExifRaw(String path, Lens lens) {
        RandomAccessFile f = null;
        try {
            File file = new File(path);
            long mtime = file.lastModified();
            f = new RandomAccessFile(file, "rw");
            long len = f.length();
            if (len < 16 || len > 0x7FFFFF00L) return "unreadable";
            byte[] h = read(f, len, 0, 8);
            boolean le;
            if (h[0] == 'I' && h[1] == 'I') le = true;
            else if (h[0] == 'M' && h[1] == 'M') le = false;
            else return "not a TIFF raw";
            if (u16(h, 2, le) != 42) return "not a TIFF raw";
            long ifd0 = u32(h, 4, le);
            int n0 = u16(read(f, len, ifd0, 2), 0, le);
            byte[] e0 = read(f, len, ifd0 + 2, n0 * 12);
            long ptr = -1, exifIfd = 0;
            for (int i = 0; i < n0; i++) {
                if (u16(e0, i * 12, le) == TAG_EXIF_IFD) {
                    ptr = ifd0 + 2 + i * 12 + 8;
                    exifIfd = u32(e0, i * 12 + 8, le);
                }
            }
            if (ptr < 0 || exifIfd <= 0) return "no Exif IFD";
            int n = u16(read(f, len, exifIfd, 2), 0, le);
            if (n > 1000) return "unreadable EXIF";
            byte[] ex = read(f, len, exifIfd + 2, n * 12);
            List<byte[]> entries = new ArrayList<byte[]>();
            for (int i = 0; i < n; i++) {
                if (replaced(u16(ex, i * 12, le), lens)) continue;
                entries.add(subarray(ex, i * 12, 12));
            }
            long at = len + (len % 2);
            byte[] ifd = exifIfd(entries, (int) at, lens, le);
            f.seek(len);
            if (at > len) f.write(0);
            f.write(ifd);
            f.getFD().sync();
            f.seek(ptr);
            f.write(u32bytes((int) at, le));
            f.close();
            f = null;
            if (mtime > 0) file.setLastModified(mtime);
            return null;
        } catch (Throwable t) {
            return t.toString();
        } finally {
            if (f != null) {
                try { f.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** n bytes at pos, which must lie inside the file. */
    private static byte[] read(RandomAccessFile f, long len, long pos, int n) throws Exception {
        if (pos < 0 || n < 0 || pos + n > len) throw new java.io.EOFException("offset " + pos + " past the end");
        byte[] b = new byte[n];
        f.seek(pos);
        f.readFully(b);
        return b;
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
}
