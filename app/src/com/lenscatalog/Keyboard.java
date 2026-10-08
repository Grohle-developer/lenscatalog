package com.lenscatalog;

/**
 * The on-screen keyboard's model: a grid of keys walked with the four-way
 * (and the control wheel, key by key), the centre button pressing the one
 * under the cursor. The camera has no touch screen and no text entry of its
 * own, so a lens's brand and model are typed here, one key at a time, as a
 * Wi-Fi password is on the camera's own menus.
 *
 * QWERTY, eleven units wide: digits with backspace at the top right, three
 * rows of letters with the punctuation lens names use (- . / : ( ) ' +),
 * shift at the bottom left as on a real keyboard, a wide space and a wide
 * Done. Shift cycles off / once / lock, as on a phone: a field that starts
 * empty begins with shift once, so a name gets its capital.
 *
 * Pure Java, no android.* import: the layout and the cursor are tested on a
 * bare JDK (test/KeyboardTest.java), and the view only draws them.
 */
final class Keyboard {
    /** What a key does. */
    static final int CH = 0, SHIFT = 1, SPACE = 2, BACK = 3, CLEAR = 4, DONE = 5;
    /** Shift states. */
    static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;

    static final class Key {
        final int kind;
        final char ch;
        /** Width in grid units (a letter is 1). */
        final int span;
        /** First unit of this key in its row. */
        final int start;

        Key(int kind, char ch, int span, int start) {
            this.kind = kind;
            this.ch = ch;
            this.span = span;
            this.start = start;
        }

        boolean letter() { return kind == CH && Character.isLetter(ch); }
    }

    /** The layout: one string a row; a letter or sign is a key of one unit, the names are the wide keys. */
    private static final String[][] LAYOUT = {
        { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "back" },
        { "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "-" },
        { "a", "s", "d", "f", "g", "h", "j", "k", "l", ".", "/" },
        { "shift", "z", "x", "c", "v", "b", "n", "m", ":", "(", ")" },
        { "clear:2", "space:5", "'", "+", "done:2" },
    };
    /** Units a row spans; every row is this wide. */
    static final int UNITS = 11;

    final Key[][] rows;
    final int maxLen;
    private final StringBuilder text;
    /** The cursor: the row and the key in it. */
    int row, col;
    /** The unit the cursor keeps when it moves between rows (so a trip through the wide space comes back). */
    private int anchor;
    int shift;
    /** Set when Done is pressed; the view then leaves the screen. */
    boolean done;

    Keyboard(String initial, int maxLen) {
        this.maxLen = maxLen;
        text = new StringBuilder(initial == null ? "" : initial);
        if (text.length() > maxLen) text.setLength(maxLen);
        rows = new Key[LAYOUT.length][];
        for (int r = 0; r < LAYOUT.length; r++) {
            rows[r] = new Key[LAYOUT[r].length];
            int u = 0;
            for (int c = 0; c < LAYOUT[r].length; c++) {
                String spec = LAYOUT[r][c];
                int span = 1, colon = spec.indexOf(':');
                String name = spec;
                // "name:span" for the wide keys; ":" alone is the colon sign
                if (colon > 0) {
                    name = spec.substring(0, colon);
                    span = Integer.parseInt(spec.substring(colon + 1));
                }
                Key k;
                if (name.equals("back")) k = new Key(BACK, '\0', span, u);
                else if (name.equals("shift")) k = new Key(SHIFT, '\0', span, u);
                else if (name.equals("space")) k = new Key(SPACE, ' ', span, u);
                else if (name.equals("clear")) k = new Key(CLEAR, '\0', span, u);
                else if (name.equals("done")) k = new Key(DONE, '\0', span, u);
                else k = new Key(CH, name.charAt(0), span, u);
                rows[r][c] = k;
                u += span;
            }
        }
        // start on the first letter; an empty field gets its capital
        row = 1;
        col = 0;
        anchor = 0;
        shift = text.length() == 0 ? SHIFT_ONCE : SHIFT_OFF;
    }

    String text() { return text.toString(); }

    int length() { return text.length(); }

    Key selected() { return rows[row][col]; }

    /** The character a key types now: letters follow shift. */
    char charOf(Key k) {
        if (k.kind == SPACE) return ' ';
        if (k.kind != CH) return '\0';
        return k.letter() && shift != SHIFT_OFF ? Character.toUpperCase(k.ch) : k.ch;
    }

    /** The four-way: dr rows down (negative: up), dc keys right (negative: left), each wrapping. */
    void move(int dr, int dc) {
        if (dc != 0) {
            int n = rows[row].length;
            col = ((col + dc) % n + n) % n;
            anchor = rows[row][col].start;
        }
        if (dr != 0) {
            int n = rows.length;
            row = ((row + dr) % n + n) % n;
            col = keyAt(row, anchor);
        }
    }

    /** The control wheel: the next key (d = 1) or the one before (-1), row after row, wrapping at the end. */
    void next(int d) {
        if (d > 0) {
            if (col + 1 < rows[row].length) col++;
            else { row = (row + 1) % rows.length; col = 0; }
        } else {
            if (col > 0) col--;
            else { row = (row - 1 + rows.length) % rows.length; col = rows[row].length - 1; }
        }
        anchor = rows[row][col].start;
    }

    /** The key of a row that covers a unit. */
    private int keyAt(int r, int unit) {
        Key[] ks = rows[r];
        for (int c = 0; c < ks.length; c++) if (unit >= ks[c].start && unit < ks[c].start + ks[c].span) return c;
        return ks.length - 1;
    }

    /** The centre button: what the key under the cursor does. */
    void press() {
        Key k = selected();
        switch (k.kind) {
            case SHIFT:
                shift = (shift + 1) % 3;
                break;
            case BACK:
                if (text.length() > 0) text.setLength(text.length() - 1);
                break;
            case CLEAR:
                text.setLength(0);
                shift = SHIFT_ONCE;
                break;
            case DONE:
                done = true;
                break;
            default: {
                if (text.length() >= maxLen) break;
                char ch = charOf(k);
                text.append(ch);
                if (k.letter() && shift == SHIFT_ONCE) shift = SHIFT_OFF;
                break;
            }
        }
    }
}
