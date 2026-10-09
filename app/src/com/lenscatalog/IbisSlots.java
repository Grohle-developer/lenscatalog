package com.lenscatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the camera keeps SteadyShot Adjust (Auto / Manual) and its focal
 * length in its settings store, and how to write them there.
 *
 * What the app sets through CameraEx lives only while the app holds the
 * camera: on leaving, the body goes back to what its store says, and shoots
 * with that. So the focal length has to go into the store, as Recipe Lab
 * does with its recipes. The store is thousands of numbered slots with no names; which
 * two are SteadyShot's is not published for any body, and the A7 II research
 * found no rule that gives a slot from a menu item (Finder/Monitor is
 * 0x01070795 because the firmware says so). They are found on the camera
 * itself, by a calibration the user does once: three snapshots of the store,
 * the camera's own menu changed between them,
 *
 *   1. SteadyShot Adjust: Auto;
 *   2. Manual, focal length F1;
 *   3. Manual, focal length F2.
 *
 * The focal slot is the one that reads F1 in the second and F2 in the third
 * under one encoding (a byte, a 16- or 32-bit number, or an index into the
 * camera's list of focal lengths); the mode slot is one that changed from 1
 * to 2 and stayed from 2 to 3, with small values. Slots that change at every
 * snapshot (clocks, counters) drop out by themselves. The app then writes
 * only those two slots, only with values of the kind the menu itself wrote.
 *
 * Pure Java, no android.* import: the analysis is tested on a bare JDK
 * (test/IbisSlotsTest.java); the snapshots and the writes come through
 * {@link Store}.
 */
final class IbisSlots {
    private IbisSlots() {}

    /** The focal lengths the calibration asks the user to set: far apart, both in every Sony list. */
    static final int CAL_F1 = 50, CAL_F2 = 200;

    /** How the focal slot holds its millimetres. */
    static final int ENC_U8 = 0, ENC_U16LE = 1, ENC_U16BE = 2, ENC_U32LE = 3, ENC_U32BE = 4, ENC_IDX8 = 5,
            ENC_IDX16LE = 6;
    /** The order encodings are tried: the firmware keeps the focal as a 16-bit number (us_steadyshot_focal_length). */
    private static final int[] ENC_ORDER = { ENC_U16LE, ENC_U8, ENC_IDX8, ENC_U32LE, ENC_U16BE, ENC_U32BE, ENC_IDX16LE };
    static final String[] ENC_NAMES = { "u8", "u16le", "u16be", "u32le", "u32be", "idx8", "idx16le" };

    /** The store, or a stand-in: one slot's bytes at a time. */
    interface Store {
        /** The slot's bytes, or null. */
        byte[] read(int id);

        /** Bytes written; negative when refused (-1 another size, -2 read-only, -3 other). */
        int write(int id, byte[] data);

        /** The slot's attribute word (bit 0 read-only), or negative. */
        int attr(int id);

        void sync();
    }

    /** What the calibration found. */
    static final class Calibration {
        int focalSlot, focalEnc;
        /** The mode slot and its bytes for Manual and for Auto; modeSlot 0 when none was found. */
        int modeSlot;
        byte[] modeManual, modeAuto;

        boolean hasFocal() { return focalSlot != 0; }

        boolean hasMode() { return modeSlot != 0 && modeManual != null; }

        /** "focal 0107042e u16le, mode 0107042d 00>01", for the log and the diagnostics. */
        String describe() {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "focal %08x %s", focalSlot, ENC_NAMES[focalEnc]));
            if (hasMode()) s.append(String.format(Locale.US, ", mode %08x %s>%s", modeSlot, hex(modeAuto), hex(modeManual)));
            else s.append(", mode: not found");
            return s.toString();
        }

        /** One line, "01070a2e:1;01070a2d:00:01": what the app keeps between runs. */
        String encode() {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "%08x:%d", focalSlot, focalEnc));
            if (hasMode()) s.append(String.format(Locale.US, ";%08x:%s:%s", modeSlot, hex(modeAuto), hex(modeManual)));
            return s.toString();
        }

        static Calibration decode(String s) {
            if (s == null || s.length() == 0) return null;
            try {
                Calibration c = new Calibration();
                String[] parts = s.split(";");
                String[] f = parts[0].split(":");
                c.focalSlot = (int) Long.parseLong(f[0], 16);
                c.focalEnc = Integer.parseInt(f[1]);
                if (c.focalEnc < 0 || c.focalEnc >= ENC_NAMES.length) return null;
                if (parts.length > 1) {
                    String[] m = parts[1].split(":");
                    c.modeSlot = (int) Long.parseLong(m[0], 16);
                    c.modeAuto = unhex(m[1]);
                    c.modeManual = unhex(m[2]);
                }
                return c.hasFocal() ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** What learn() has to say besides the calibration: every candidate, for the log. */
    static final class Learned {
        Calibration cal;
        final List<String> focalCandidates = new ArrayList<String>();
        final List<String> modeCandidates = new ArrayList<String>();
        /** Why there is no calibration; null when there is one. */
        String error;
    }

    // ------------------------------------------------------------ the analysis
    /**
     * From the three snapshots (slot -> bytes) and the two focal lengths the
     * user set, the slots. `focals`: the camera's list of SteadyShot focal
     * lengths, for the index encodings.
     */
    static Learned learn(Map<Integer, byte[]> auto, Map<Integer, byte[]> manual1, Map<Integer, byte[]> manual2,
            int f1, int f2, int[] focals) {
        Learned out = new Learned();
        // the focal slot: F1 in the second snapshot, F2 in the third, under one encoding
        int bestSlot = 0, bestEnc = -1, bestRank = Integer.MAX_VALUE;
        for (Map.Entry<Integer, byte[]> e : new TreeMap<Integer, byte[]>(manual1).entrySet()) {
            byte[] b1 = e.getValue(), b2 = manual2.get(e.getKey());
            if (b2 == null || same(b1, b2) || b1.length != b2.length || b1.length > 4) continue;
            for (int rank = 0; rank < ENC_ORDER.length; rank++) {
                int enc = ENC_ORDER[rank];
                if (decode(b1, enc, focals) == f1 && decode(b2, enc, focals) == f2) {
                    out.focalCandidates.add(String.format(Locale.US, "%08x %s %s>%s", e.getKey(), ENC_NAMES[enc], hex(b1), hex(b2)));
                    if (rank < bestRank) { bestRank = rank; bestSlot = e.getKey(); bestEnc = enc; }
                    break;
                }
            }
        }
        if (bestSlot == 0) {
            out.error = "no slot reads " + f1 + " then " + f2;
            return out;
        }
        Calibration c = new Calibration();
        c.focalSlot = bestSlot;
        c.focalEnc = bestEnc;
        // the mode slot: changed from Auto to Manual, kept between the two Manual snapshots, small values
        int bestMode = 0, bestModeRank = Integer.MAX_VALUE;
        for (Map.Entry<Integer, byte[]> e : new TreeMap<Integer, byte[]>(manual1).entrySet()) {
            int id = e.getKey();
            if (id == bestSlot) continue;
            byte[] a = auto.get(id), b1 = e.getValue(), b2 = manual2.get(id);
            if (a == null || b2 == null || a.length != b1.length || b1.length > 4) continue;
            if (same(a, b1) || !same(b1, b2)) continue;
            long va = unsigned(a), vb = unsigned(b1);
            if (va > 16 || vb > 16) continue;
            // a two-state item is the likeliest: 0/1 first, then the smaller the values the better
            int rank = (int) (Math.max(va, vb) * 2 + (b1.length - 1));
            out.modeCandidates.add(String.format(Locale.US, "%08x %s>%s", id, hex(a), hex(b1)));
            if (rank < bestModeRank) { bestModeRank = rank; bestMode = id; c.modeAuto = a; c.modeManual = b1; }
        }
        c.modeSlot = bestMode;
        if (bestMode == 0) { c.modeAuto = null; c.modeManual = null; }
        out.cal = c;
        return out;
    }

    /** The millimetres a slot's bytes stand for under an encoding; -1 when they cannot. */
    static int decode(byte[] b, int enc, int[] focals) {
        switch (enc) {
            case ENC_U8: return b.length == 1 ? b[0] & 0xff : -1;
            case ENC_U16LE: return b.length == 2 ? (b[0] & 0xff) | ((b[1] & 0xff) << 8) : -1;
            case ENC_U16BE: return b.length == 2 ? (b[1] & 0xff) | ((b[0] & 0xff) << 8) : -1;
            case ENC_U32LE: return b.length == 4 && b[3] == 0 && b[2] == 0 ? (b[0] & 0xff) | ((b[1] & 0xff) << 8) : -1;
            case ENC_U32BE: return b.length == 4 && b[0] == 0 && b[1] == 0 ? (b[3] & 0xff) | ((b[2] & 0xff) << 8) : -1;
            case ENC_IDX8: {
                if (b.length != 1 || focals == null) return -1;
                int i = b[0] & 0xff;
                return i < focals.length ? focals[i] : -1;
            }
            case ENC_IDX16LE: {
                if (b.length != 2 || focals == null) return -1;
                int i = (b[0] & 0xff) | ((b[1] & 0xff) << 8);
                return i < focals.length ? focals[i] : -1;
            }
            default: return -1;
        }
    }

    /** The bytes a slot of `size` bytes takes for `mm` under an encoding; null when it cannot hold it. */
    static byte[] encode(int mm, int enc, int size, int[] focals) {
        switch (enc) {
            case ENC_U8: return size == 1 && mm >= 0 && mm < 256 ? new byte[] { (byte) mm } : null;
            case ENC_U16LE: return size == 2 && mm >= 0 && mm < 65536 ? new byte[] { (byte) mm, (byte) (mm >> 8) } : null;
            case ENC_U16BE: return size == 2 && mm >= 0 && mm < 65536 ? new byte[] { (byte) (mm >> 8), (byte) mm } : null;
            case ENC_U32LE: return size == 4 && mm >= 0 ? new byte[] { (byte) mm, (byte) (mm >> 8), 0, 0 } : null;
            case ENC_U32BE: return size == 4 && mm >= 0 ? new byte[] { 0, 0, (byte) (mm >> 8), (byte) mm } : null;
            case ENC_IDX8: case ENC_IDX16LE: {
                if (focals == null) return null;
                int i = indexOf(focals, mm);
                if (i < 0) return null;
                if (enc == ENC_IDX8) return size == 1 ? new byte[] { (byte) i } : null;
                return size == 2 ? new byte[] { (byte) i, (byte) (i >> 8) } : null;
            }
            default: return null;
        }
    }

    // ------------------------------------------------------------ writing
    /** What a write did, for the log and the result card. */
    static final class Written {
        /** The focal the store holds now, read back; -1 when it could not be written or read. */
        int focalNow = -1;
        /** Manual is in the store (or the calibration knows no mode slot: null). */
        Boolean manual;
        String error;
    }

    /**
     * Put SteadyShot Adjust: Manual and `mm` into the store, then commit, then
     * read back. Only the two calibrated slots are touched, only when they are
     * writable and of the size the calibration saw, and `mm` must be one of
     * the camera's own focal lengths.
     */
    static Written apply(Store s, Calibration c, int mm, int[] focals) {
        Written w = new Written();
        if (c == null || !c.hasFocal()) { w.error = "not calibrated"; return w; }
        if (indexOf(focals, mm) < 0) { w.error = mm + " mm is not a SteadyShot focal length"; return w; }
        byte[] cur = s.read(c.focalSlot);
        if (cur == null) { w.error = String.format(Locale.US, "cannot read %08x", c.focalSlot); return w; }
        byte[] want = encode(mm, c.focalEnc, cur.length, focals);
        if (want == null) { w.error = String.format(Locale.US, "%08x cannot hold %d mm as %s", c.focalSlot, mm, ENC_NAMES[c.focalEnc]); return w; }
        if ((s.attr(c.focalSlot) & 1) != 0) { w.error = String.format(Locale.US, "%08x is read-only", c.focalSlot); return w; }
        boolean wrote = false;
        if (!same(cur, want)) {
            int r = s.write(c.focalSlot, want);
            if (r < 0) { w.error = String.format(Locale.US, "%08x refused (%d)", c.focalSlot, r); return w; }
            wrote = true;
        }
        if (c.hasMode()) {
            byte[] m = s.read(c.modeSlot);
            if (m != null && m.length == c.modeManual.length && !same(m, c.modeManual) && (s.attr(c.modeSlot) & 1) == 0) {
                int r = s.write(c.modeSlot, c.modeManual);
                if (r < 0) w.error = String.format(Locale.US, "mode %08x refused (%d)", c.modeSlot, r);
                else wrote = true;
            }
        }
        if (wrote) s.sync();
        byte[] back = s.read(c.focalSlot);
        w.focalNow = back == null ? -1 : decode(back, c.focalEnc, focals);
        if (c.hasMode()) {
            byte[] m = s.read(c.modeSlot);
            w.manual = m != null && same(m, c.modeManual);
        }
        if (w.focalNow != mm && w.error == null) w.error = "the store holds " + w.focalNow + " mm after writing " + mm;
        return w;
    }

    /** The focal the store holds now under the calibration, or -1. */
    static int focalInStore(Store s, Calibration c, int[] focals) {
        if (c == null || !c.hasFocal()) return -1;
        byte[] b = s.read(c.focalSlot);
        return b == null ? -1 : decode(b, c.focalEnc, focals);
    }

    // ------------------------------------------------------------ snapshots as text
    /** A snapshot in the dump files' format: "slot size hex" a line, after any "#" lines. */
    static String format(Map<Integer, byte[]> m, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append("# LensCatalog settings store; ").append(header).append('\n');
        for (Map.Entry<Integer, byte[]> e : new TreeMap<Integer, byte[]>(m).entrySet()) {
            sb.append(String.format(Locale.US, "%08x %d %s\n", e.getKey(), e.getValue().length, hex(e.getValue())));
        }
        return sb.toString();
    }

    static Map<Integer, byte[]> parse(String text) {
        Map<Integer, byte[]> m = new TreeMap<Integer, byte[]>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length < 3) continue;
            try {
                int id = (int) Long.parseLong(f[0], 16);
                byte[] b = unhex(f[2]);
                if (b.length == Integer.parseInt(f[1])) m.put(id, b);
            } catch (Throwable t) {
                // a line that is not a slot
            }
        }
        return m;
    }

    // ------------------------------------------------------------ bytes
    static boolean same(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static long unsigned(byte[] b) {
        long v = 0;
        for (int i = b.length - 1; i >= 0; i--) v = (v << 8) | (b[i] & 0xff);
        return v;
    }

    private static int indexOf(int[] a, int v) {
        if (a == null) return -1;
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    static String hex(byte[] b) {
        if (b == null) return "?";
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException("hex");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
