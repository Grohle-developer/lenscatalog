package com.lenscatalog;

import java.util.HashMap;
import java.util.Map;

/**
 * The SteadyShot calibration's analysis and its writes, on a bare JDK with a
 * store in a map: the focal slot found under each encoding, the mode slot
 * told from the noise, the candidates listed, the writes guarded (read-only,
 * size, foreign focal), the round trip of the calibration's text form, and
 * the snapshot format. Run by test/unit-test.sh.
 */
public final class IbisSlotsTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) failures++;
    }

    private static void eq(Object got, Object want, String what) {
        boolean ok = want == null ? got == null : want.equals(got);
        check(ok, what + " = " + want + (ok ? "" : " (got " + got + ")"));
    }

    static final int[] FOCALS = Sony_IBIS_FOCALS();

    private static int[] Sony_IBIS_FOCALS() {
        return new int[] { 8, 10, 12, 16, 18, 20, 24, 28, 30, 35, 40, 50, 60, 70, 85, 100, 120, 135, 150, 180, 200,
                250, 300, 350, 400, 450, 500, 600, 700, 800, 1000 };
    }

    /** A store in a map, with a read-only set. */
    static final class MapStore implements IbisSlots.Store {
        final Map<Integer, byte[]> m = new HashMap<Integer, byte[]>();
        final java.util.Set<Integer> readOnly = new java.util.HashSet<Integer>();
        int syncs;

        public byte[] read(int id) { byte[] b = m.get(id); return b == null ? null : b.clone(); }

        public int write(int id, byte[] data) {
            byte[] cur = m.get(id);
            if (cur == null || cur.length != data.length) return -1;
            if (readOnly.contains(id)) return -2;
            m.put(id, data.clone());
            return data.length;
        }

        public int attr(int id) { return readOnly.contains(id) ? 1 : 0; }

        public void sync() { syncs++; }
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    /** Three snapshots of a made-up store: the SteadyShot slots, a clock, a menu cursor, and dozens that never move. */
    private static Map<Integer, byte[]>[] snapshots(int focalEnc, boolean withMode) {
        @SuppressWarnings("unchecked")
        Map<Integer, byte[]>[] s = new Map[3];
        for (int i = 0; i < 3; i++) {
            s[i] = new HashMap<Integer, byte[]>();
            for (int k = 0; k < 60; k++) s[i].put(0x01070000 + k, b(k % 7, 3));
            s[i].put(0x01070900, b(0x10 + i, 0x20, 0x30, 0x40 + i));      // a clock: changes every time
            s[i].put(0x01070901, b(i == 0 ? 5 : i == 1 ? 9 : 4));          // the menu's cursor: changes every time too
            s[i].put(0x01070902, b(i == 0 ? 0 : 1, 0));                    // a two-byte item that flips with the mode
        }
        int f1 = IbisSlots.CAL_F1, f2 = IbisSlots.CAL_F2;
        int size = focalEnc == IbisSlots.ENC_U8 || focalEnc == IbisSlots.ENC_IDX8 ? 1
                : focalEnc == IbisSlots.ENC_U32LE || focalEnc == IbisSlots.ENC_U32BE ? 4 : 2;
        s[0].put(0x01070a2e, IbisSlots.encode(35, focalEnc, size, FOCALS));
        s[1].put(0x01070a2e, IbisSlots.encode(f1, focalEnc, size, FOCALS));
        s[2].put(0x01070a2e, IbisSlots.encode(f2, focalEnc, size, FOCALS));
        if (withMode) {
            s[0].put(0x01070a2d, b(0));
            s[1].put(0x01070a2d, b(1));
            s[2].put(0x01070a2d, b(1));
        }
        return s;
    }

    public static void main(String[] args) {
        System.out.println("IbisSlots: the analysis");
        for (int enc = 0; enc < IbisSlots.ENC_NAMES.length; enc++) {
            Map<Integer, byte[]>[] s = snapshots(enc, true);
            IbisSlots.Learned l = IbisSlots.learn(s[0], s[1], s[2], IbisSlots.CAL_F1, IbisSlots.CAL_F2, FOCALS);
            check(l.cal != null && l.error == null, "found with the focal as " + IbisSlots.ENC_NAMES[enc]);
            if (l.cal == null) continue;
            eq(Integer.toHexString(l.cal.focalSlot), "1070a2e", "the focal slot");
            // a byte 50 is also index 11... no: the index of 50 is 11 and of 200 is 20, so u8 and idx8 cannot both fit
            eq(IbisSlots.ENC_NAMES[l.cal.focalEnc], IbisSlots.ENC_NAMES[enc], "its encoding");
            eq(Integer.toHexString(l.cal.modeSlot), "1070a2d", "the mode slot: the one-byte 0>1 item, not the two-byte one");
            eq(IbisSlots.hex(l.cal.modeManual), "01", "manual = 01");
            eq(IbisSlots.hex(l.cal.modeAuto), "00", "auto = 00");
        }
        Map<Integer, byte[]>[] s = snapshots(IbisSlots.ENC_U16LE, true);
        IbisSlots.Learned l = IbisSlots.learn(s[0], s[1], s[2], IbisSlots.CAL_F1, IbisSlots.CAL_F2, FOCALS);
        eq(l.focalCandidates.size(), 1, "one focal candidate (the clock and the cursor never read 50 then 200)");
        eq(l.modeCandidates.size(), 2, "two mode candidates (the real one and the two-byte item)");
        s = snapshots(IbisSlots.ENC_U16LE, false);
        s[0].remove(0x01070902); s[1].remove(0x01070902); s[2].remove(0x01070902);
        l = IbisSlots.learn(s[0], s[1], s[2], IbisSlots.CAL_F1, IbisSlots.CAL_F2, FOCALS);
        check(l.cal != null && l.cal.hasFocal() && !l.cal.hasMode(), "focal without a mode slot is still a calibration");
        eq(l.cal.describe(), "focal 01070a2e u16le, mode: not found", "describe");
        Map<Integer, byte[]> empty = new HashMap<Integer, byte[]>();
        l = IbisSlots.learn(empty, empty, empty, 50, 200, FOCALS);
        check(l.cal == null && l.error != null, "nothing found: an error, no calibration");
        // the user set the same focal twice: nothing can be told apart
        s = snapshots(IbisSlots.ENC_U16LE, true);
        s[2].put(0x01070a2e, s[1].get(0x01070a2e));
        l = IbisSlots.learn(s[0], s[1], s[2], IbisSlots.CAL_F1, IbisSlots.CAL_F2, FOCALS);
        check(l.cal == null, "the same focal in both manual snapshots: no calibration");

        System.out.println("encodings");
        eq(IbisSlots.decode(b(60), IbisSlots.ENC_U8, FOCALS), 60, "u8");
        eq(IbisSlots.decode(b(0xc8, 0x00), IbisSlots.ENC_U16LE, FOCALS), 200, "u16le");
        eq(IbisSlots.decode(b(0x00, 0xc8), IbisSlots.ENC_U16BE, FOCALS), 200, "u16be");
        eq(IbisSlots.decode(b(0xe8, 0x03, 0, 0), IbisSlots.ENC_U32LE, FOCALS), 1000, "u32le");
        eq(IbisSlots.decode(b(12), IbisSlots.ENC_IDX8, FOCALS), 60, "idx8: index 12 is 60 mm");
        eq(IbisSlots.decode(b(99), IbisSlots.ENC_IDX8, FOCALS), -1, "idx8 out of the list");
        eq(IbisSlots.decode(b(1, 2), IbisSlots.ENC_U8, FOCALS), -1, "a two-byte slot is no u8");
        eq(IbisSlots.hex(IbisSlots.encode(60, IbisSlots.ENC_U16LE, 2, FOCALS)), "3c00", "encode u16le");
        eq(IbisSlots.hex(IbisSlots.encode(60, IbisSlots.ENC_IDX8, 1, FOCALS)), "0c", "encode idx8");
        eq(IbisSlots.encode(61, IbisSlots.ENC_IDX8, 1, FOCALS), null, "61 mm is in no list");
        eq(IbisSlots.encode(60, IbisSlots.ENC_U16LE, 1, FOCALS), null, "the wrong size encodes nothing");

        System.out.println("writing");
        MapStore st = new MapStore();
        st.m.put(0x01070a2e, b(0x32, 0x00));   // 50 mm
        st.m.put(0x01070a2d, b(0));             // auto
        IbisSlots.Calibration c = IbisSlots.Calibration.decode("01070a2e:1;01070a2d:00:01");
        check(c != null && c.hasFocal() && c.hasMode(), "a calibration decodes");
        eq(c.encode(), "01070a2e:1;01070a2d:00:01", "and encodes back the same");
        IbisSlots.Written w = IbisSlots.apply(st, c, 60, FOCALS);
        eq(w.error, null, "60 mm written without error");
        eq(w.focalNow, 60, "the store reads 60 mm back");
        eq(w.manual, Boolean.TRUE, "and Manual");
        eq(IbisSlots.hex(st.m.get(0x01070a2e)), "3c00", "the focal bytes");
        eq(IbisSlots.hex(st.m.get(0x01070a2d)), "01", "the mode bytes");
        eq(st.syncs, 1, "one commit");
        w = IbisSlots.apply(st, c, 60, FOCALS);
        eq(st.syncs, 1, "writing what the store already holds commits nothing");
        eq(w.focalNow, 60, "and still reads 60");
        w = IbisSlots.apply(st, c, 61, FOCALS);
        check(w.error != null && w.error.indexOf("not a SteadyShot") >= 0, "a focal the camera has not is refused: " + w.error);
        st.readOnly.add(0x01070a2e);
        w = IbisSlots.apply(st, c, 85, FOCALS);
        check(w.error != null && w.error.indexOf("read-only") >= 0, "a read-only slot is not written: " + w.error);
        eq(IbisSlots.hex(st.m.get(0x01070a2e)), "3c00", "and holds what it held");
        st.readOnly.clear();
        st.m.put(0x01070a2e, b(0x32, 0x00, 0x00));   // the slot changed size since the calibration
        w = IbisSlots.apply(st, c, 85, FOCALS);
        check(w.error != null && w.error.indexOf("cannot hold") >= 0, "a slot of another size is not written: " + w.error);
        eq(IbisSlots.apply(st, null, 85, FOCALS).error, "not calibrated", "no calibration, no write");
        eq(IbisSlots.focalInStore(new MapStore(), c, FOCALS), -1, "an unreadable slot reads -1");
        eq(IbisSlots.Calibration.decode("garbage"), null, "garbage decodes to nothing");
        eq(IbisSlots.Calibration.decode("01070a2e:9"), null, "an unknown encoding decodes to nothing");

        System.out.println("snapshots as text");
        Map<Integer, byte[]> m = new HashMap<Integer, byte[]>();
        m.put(0x01070a2e, b(0x32, 0x00));
        m.put(0x01000001, b(7));
        String text = IbisSlots.format(m, "test");
        check(text.startsWith("# LensCatalog settings store; test\n01000001 1 07\n01070a2e 2 3200\n"), "the dump format, sorted");
        Map<Integer, byte[]> back = IbisSlots.parse(text + "junk line\n0107ffff 2 00\n");
        eq(back.size(), 2, "parsed back: the two slots, the junk and the size mismatch dropped");
        eq(IbisSlots.hex(back.get(0x01070a2e)), "3200", "with their bytes");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
