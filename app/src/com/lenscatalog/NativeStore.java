package com.lenscatalog;

/**
 * JNI binding to the camera's settings store (liblcstore.so, jni/store/, the
 * backup-service driver of OpenMemories-Platform, MIT): one slot read or
 * written at a time, then a commit. The store is what the camera keeps through the app closing and a
 * power cycle; Camera.Parameters are not. Loaded on first use and only on a
 * camera: the simulator has no such library, and available() says so.
 * Nothing here throws.
 */
final class NativeStore {
    private NativeStore() {}

    private static int state = -1; // -1 not tried, 0 no library, 1 loaded

    static synchronized boolean available() {
        if (state < 0) {
            try {
                System.loadLibrary("lcstore");
                state = 1;
            } catch (Throwable t) {
                state = 0;
                AppLog.i("settings store: " + t);
            }
        }
        return state == 1;
    }

    /** The slot's bytes, or null. */
    static native byte[] read(int id);

    /** Bytes written; -1 the slot is another size, -2 read-only, -3 refused. */
    static native int write(int id, byte[] data);

    /** The slot's attribute word (bit 0 read-only), or a negative number. */
    static native int attr(int id);

    static native void sync();
}
