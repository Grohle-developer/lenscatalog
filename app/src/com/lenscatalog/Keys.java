package com.lenscatalog;

/**
 * The keys: which physical key an event is, on the camera and in the A7 II
 * simulator. The camera says which key it is in the scan code (Sony's
 * ScalarInput codes); the Android key code is whatever the firmware put
 * there. The four-way and the centre arrive with the same scan codes on
 * both (103/105/106/108/232 are Linux's and Sony's alike). No android.*
 * import.
 */
final class Keys {
    private Keys() {}

    /** Logical keys. */
    static final int NONE = 0, UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, ENTER = 5, MENU = 6;

    // Sony scan codes (com.sony.scalar.sysutil.ScalarInput)
    static final int SC_UP = 103, SC_DOWN = 108, SC_LEFT = 105, SC_RIGHT = 106, SC_ENTER = 232,
            SC_MENU = 514, SC_SK1 = 229;

    // Android key codes (android.view.KeyEvent)
    static final int KC_BACK = 4, KC_DPAD_UP = 19, KC_DPAD_DOWN = 20, KC_DPAD_LEFT = 21,
            KC_DPAD_RIGHT = 22, KC_DPAD_CENTER = 23, KC_ENTER = 66, KC_MENU = 82;

    static int fromScanCode(int sc) {
        switch (sc) {
            case SC_UP: return UP;
            case SC_DOWN: return DOWN;
            case SC_LEFT: return LEFT;
            case SC_RIGHT: return RIGHT;
            case SC_ENTER: return ENTER;
            case SC_MENU: case SC_SK1: return MENU;
            default: return NONE;
        }
    }

    static int fromKeyCode(int kc) {
        switch (kc) {
            case KC_DPAD_UP: return UP;
            case KC_DPAD_DOWN: return DOWN;
            case KC_DPAD_LEFT: return LEFT;
            case KC_DPAD_RIGHT: return RIGHT;
            case KC_DPAD_CENTER: case KC_ENTER: return ENTER;
            case KC_MENU: case KC_BACK: return MENU;
            default: return NONE;
        }
    }

    /** Sony's scan code first; the key code when the scan code is not one of Sony's. */
    static int logical(int scanCode, int keyCode) {
        int k = fromScanCode(scanCode);
        return k != NONE ? k : fromKeyCode(keyCode);
    }
}
