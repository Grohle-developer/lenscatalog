package com.lenscatalog;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The camera's settings store, written to the card as text
 * (/AINTFILM/STORE001.TXT, STORE002.TXT...: 8.3 names): one line a slot,
 * "id size hex-bytes". Two dumps, one before and one after changing a single
 * setting in the camera's menu (SteadyShot Adjust: Manual, its focal length),
 * differ in the slot that holds it: how Recipe Lab and aintfilm-sony found
 * theirs. Reading only; nothing is written to the store. Slots are
 * 0xSSSSIIII; subsystems 0x0100-0x010F, ids 0x000-0xFFF are walked.
 */
final class StoreDump {
    private StoreDump() {}

    static final class Result {
        String file;
        int slots;
        long millis;
        String error;
    }

    static Result dump(File dir, String header) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        if (!NativeStore.available()) {
            r.error = "no store";
            return r;
        }
        try {
            dir.mkdirs();
            File f = null;
            for (int n = 1; n < 1000; n++) {
                File c = new File(dir, "STORE" + (n < 10 ? "00" : n < 100 ? "0" : "") + n + ".TXT");
                if (!c.exists()) { f = c; break; }
            }
            if (f == null) { r.error = "999 dumps already"; return r; }
            StringBuilder sb = new StringBuilder();
            sb.append("# LensCatalog settings store dump; ").append(header).append('\n');
            for (int sub = 0x0100; sub <= 0x010F; sub++) {
                for (int id = 0; id <= 0x0FFF; id++) {
                    int slot = (sub << 16) | id;
                    byte[] b = NativeStore.read(slot);
                    if (b == null || b.length == 0) continue;
                    sb.append(String.format("%08x %d ", slot, b.length));
                    for (byte x : b) sb.append(String.format("%02x", x & 0xff));
                    sb.append('\n');
                    r.slots++;
                }
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            r.file = f.getName();
        } catch (Throwable t) {
            r.error = t.toString();
        }
        r.millis = System.currentTimeMillis() - t0;
        AppLog.i("store dump: " + (r.error != null ? r.error : r.file + ", " + r.slots + " slots") + " in " + r.millis + " ms");
        return r;
    }
}
