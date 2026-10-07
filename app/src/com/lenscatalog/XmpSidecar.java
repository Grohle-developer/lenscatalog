package com.lenscatalog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * An XMP sidecar next to a raw file: DSC01837.ARW -> DSC01837.XMP, the name
 * Lightroom, Bridge and Capture One look for on import (an 8.3 name, upper
 * case, as the camera's card wants). It says the lens (exifEX:LensModel and
 * aux:Lens), the focal length when there is one (primes) and the f-number,
 * so a raw converter has them even if it reads the lens from Sony's MakerNote
 * rather than from the EXIF. The raw file itself is not touched here.
 *
 * A sidecar that LensCatalog did not write (one a computer left on the card,
 * with someone's edits in it) is never overwritten. No android.* import:
 * tested on a bare JDK. Null = ok.
 */
final class XmpSidecar {
    private XmpSidecar() {}

    /** Written into every sidecar of ours (x:xmptk), so a later run knows it may replace it. */
    static final String TOOLKIT = "LensCatalog";

    /** The sidecar's file: the photo's name with .XMP for its extension. */
    static File fileFor(File photo) {
        String n = photo.getName();
        int dot = n.lastIndexOf('.');
        return new File(photo.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".XMP");
    }

    static String write(File photo, String lensModel, int focalMm, double fNumber) {
        try {
            File x = fileFor(photo);
            if (x.exists() && !ours(x)) return "kept " + x.getName() + " (not written by " + TOOLKIT + ")";
            String lens = escape(lensModel);
            StringBuilder s = new StringBuilder();
            s.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
            s.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"").append(TOOLKIT).append("\">\n");
            s.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
            s.append("  <rdf:Description rdf:about=\"\"\n");
            s.append("    xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"\n");
            s.append("    xmlns:exifEX=\"http://cipa.jp/exif/1.0/\"\n");
            s.append("    xmlns:aux=\"http://ns.adobe.com/exif/1.0/aux/\"\n");
            s.append("   exifEX:LensModel=\"").append(lens).append("\"\n");
            s.append("   aux:Lens=\"").append(lens).append("\"");
            if (focalMm > 0) s.append("\n   exif:FocalLength=\"").append(focalMm).append("/1\"");
            if (fNumber > 0) s.append("\n   exif:FNumber=\"").append(Math.round(fNumber * 100)).append("/100\"");
            s.append("/>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n");
            FileOutputStream out = new FileOutputStream(x);
            try {
                out.write(s.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** Whether a sidecar was written by LensCatalog (its toolkit attribute says so). */
    private static boolean ours(File x) {
        try {
            if (x.length() > 64 * 1024) return false;
            byte[] b = new byte[(int) x.length()];
            FileInputStream in = new FileInputStream(x);
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            return new String(b, 0, n, "UTF-8").indexOf("x:xmptk=\"" + TOOLKIT + "\"") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c < 0x20) b.append(' '); else b.append(c);
            }
        }
        return b.toString();
    }
}
