package com.lenscatalog;

/**
 * The on-screen keyboard's model and the cleaning of what it types, on a bare
 * JDK: the layout's shape, the cursor's moves (the four-way wrapping, the
 * anchor kept across rows, the wheel key by key), shift's three states, the
 * length limit, and UserLenses' cleaning and ids. Run by test/unit-test.sh.
 */
public final class KeyboardTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) failures++;
    }

    private static String xs(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('x');
        return b.toString();
    }

    private static void eq(Object got, Object want, String what) {
        check(want.equals(got), what + " = " + want + (want.equals(got) ? "" : " (got " + got + ")"));
    }

    /** Walk to the key that types `ch` (letters by their lower case), the way the tour does. */
    private static void goTo(Keyboard kb, char ch) {
        for (int r = 0; r < kb.rows.length; r++) {
            for (int c = 0; c < kb.rows[r].length; c++) {
                Keyboard.Key k = kb.rows[r][c];
                boolean hit = k.kind == Keyboard.SPACE ? ch == ' '
                        : k.kind == Keyboard.CH && Character.toLowerCase(k.ch) == Character.toLowerCase(ch);
                if (!hit) continue;
                while (kb.row != r) kb.move(1, 0);
                while (kb.col != c) kb.move(0, 1);
                return;
            }
        }
        throw new IllegalArgumentException("no key for " + ch);
    }

    private static void goToKind(Keyboard kb, int kind) {
        for (int r = 0; r < kb.rows.length; r++) {
            for (int c = 0; c < kb.rows[r].length; c++) {
                if (kb.rows[r][c].kind != kind) continue;
                while (kb.row != r) kb.move(1, 0);
                while (kb.col != c) kb.move(0, 1);
                return;
            }
        }
    }

    /** Type a string key by key, pressing shift as a person would. */
    private static void type(Keyboard kb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetter(ch)) {
                boolean upper = Character.isUpperCase(ch);
                while ((kb.shift != Keyboard.SHIFT_OFF) != upper) {
                    goToKind(kb, Keyboard.SHIFT);
                    kb.press();
                }
            }
            goTo(kb, ch);
            kb.press();
        }
    }

    public static void main(String[] args) {
        System.out.println("Keyboard");
        Keyboard kb = new Keyboard("", 60);
        for (int r = 0; r < kb.rows.length; r++) {
            int units = 0;
            for (Keyboard.Key k : kb.rows[r]) units += k.span;
            eq(units, Keyboard.UNITS, "row " + r + " spans the grid");
        }
        eq(kb.row + "," + kb.col, "1,0", "starts on q");
        eq(kb.shift, Keyboard.SHIFT_ONCE, "an empty field starts with shift once");
        eq(new Keyboard("Canon", 60).shift, Keyboard.SHIFT_OFF, "a filled field starts with shift off");
        eq(new Keyboard(xs(80), 60).length(), 60, "a long initial text is cut to the limit");

        System.out.println("moves");
        kb.move(0, -1);
        eq(String.valueOf(kb.selected().ch), "-", "left from q wraps to the row's end");
        kb.move(0, 1);
        eq(String.valueOf(kb.selected().ch), "q", "right wraps back");
        kb.move(0, 5);
        eq(String.valueOf(kb.selected().ch), "y", "five right: y");
        kb.move(1, 0);
        eq(String.valueOf(kb.selected().ch), "h", "down keeps the column: h");
        kb.move(1, 0);
        eq(String.valueOf(kb.selected().ch), "b", "down: b");
        kb.move(1, 0);
        eq(kb.selected().kind, Keyboard.SPACE, "down into the wide space");
        kb.move(1, 0);
        eq(String.valueOf(kb.selected().ch), "6", "down wraps to the digits, on the anchored column");
        kb.move(1, 0);
        eq(String.valueOf(kb.selected().ch), "y", "and back to y: the anchor survived the space");
        kb.move(-1, 0);
        eq(String.valueOf(kb.selected().ch), "6", "up wraps too");
        kb.move(0, 5);
        eq(kb.selected().kind, Keyboard.BACK, "backspace at the top right");
        kb.next(1);
        eq(String.valueOf(kb.selected().ch), "q", "the wheel goes on to the next row");
        kb.next(-1);
        eq(kb.selected().kind, Keyboard.BACK, "and back");
        kb.move(1, 0);
        kb.move(1, 0);
        kb.move(1, 0);
        eq(String.valueOf(kb.selected().ch), ")", "three down from backspace: the last key of the shift row");
        kb.move(1, 0);
        eq(kb.selected().kind, Keyboard.DONE, "below it, the wide Done");
        kb.move(-1, 0);
        eq(String.valueOf(kb.selected().ch), ")", "and up again onto the same key");

        System.out.println("typing");
        kb = new Keyboard("", 60);
        type(kb, "Meyer-Optik Oreston 50mm f/1.8");
        eq(kb.text(), "Meyer-Optik Oreston 50mm f/1.8", "a name with capitals, a dash, a space, digits, / and .");
        eq(kb.shift, Keyboard.SHIFT_OFF, "shift is off after the capital");
        kb = new Keyboard("", 60);
        goToKind(kb, Keyboard.SHIFT);
        kb.press();
        eq(kb.shift, Keyboard.SHIFT_LOCK, "shift once, then lock");
        for (char ch : "kmz".toCharArray()) { goTo(kb, ch); kb.press(); }   // the letter keys as they are
        eq(kb.text(), "KMZ", "lock types capitals");
        eq(kb.shift, Keyboard.SHIFT_LOCK, "and stays");
        kb.press();
        kb.press();
        eq(kb.text(), "KMZZZ", "the same key again types again");
        goToKind(kb, Keyboard.BACK);
        kb.press();
        kb.press();
        eq(kb.text(), "KMZ", "backspace");
        goToKind(kb, Keyboard.SHIFT);
        kb.press();
        eq(kb.shift, Keyboard.SHIFT_OFF, "lock, then off");
        goToKind(kb, Keyboard.CLEAR);
        kb.press();
        eq(kb.text(), "", "clear empties the text");
        eq(kb.shift, Keyboard.SHIFT_ONCE, "and the next letter gets its capital again");
        kb = new Keyboard("", 3);
        type(kb, "abcdef");
        eq(kb.text(), "abc", "nothing past the limit (shift turned off for the small letters)");
        check(!kb.done, "not done yet");
        goToKind(kb, Keyboard.DONE);
        kb.press();
        check(kb.done, "Done");

        System.out.println("UserLenses cleaning");
        eq(UserLenses.clean("  Helios   44M-4\t58mm\n1:2  ", 60), "Helios 44M-4 58mm 1:2", "spaces and control characters");
        eq(UserLenses.clean(xs(80), 24), xs(24), "cut to the limit");
        eq(UserLenses.clean(null, 10), "", "null");
        eq(UserLenses.newId("Meyer-Optik", "Oreston 50mm f/1.8", "M42"), "my-meyer-optik-oreston-50mm-f-1-8-m42", "id in the catalogue's style");
        eq(UserLenses.newId("KMZ", "Helios 44M-4 58mm 1:2", ""), "my-kmz-helios-44m-4-58mm-1-2", "id without a mount");
        Catalog.Lens l = new Catalog.Lens();
        l.brand = " ";
        l.model = "Zoom 70-210";
        l.mount = "";
        l.type = "zoom";
        l.focalMin = 210;
        l.focalMax = 70;
        l.maxAperture = 3.49999;
        UserLenses.tidy(l);
        eq(l.brand, "Manual", "an empty brand becomes Manual");
        eq(l.focalMin + "-" + l.focalMax, "70-210", "a zoom's range the right way round");
        eq(l.focal, 70, "a zoom's focal is its wide end");
        eq(l.maxAperture, 3.5, "aperture rounded to two decimals");
        l.type = "weird";
        l.focal = 0;
        l.maxAperture = 99;
        UserLenses.tidy(l);
        eq(l.type, "prime", "an unknown type is a prime");
        eq(l.focal, UserLenses.FOCAL_MIN, "focal within range");
        eq(l.maxAperture, 0.0, "an impossible aperture is unknown");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
