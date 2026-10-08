package com.lenscatalog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * The XMP that says the lens: as a sidecar next to a raw file (DSC01837.ARW ->
 * DSC01837.XMP, the name Lightroom, Bridge and Capture One look for on
 * import; an 8.3 name, upper case, as the camera's card wants) and, through
 * {@link #packet}, embedded in the photographs themselves by ExifWriter.
 *
 * It carries the lens in every vocabulary a reader is known to use: EXIF's
 * own (exifEX:LensModel, exifEX:LensMake), Adobe's (aux:Lens, aux:LensInfo,
 * what Lightroom falls back to), and Microsoft's (MicrosoftPhoto:LensModel,
 * MicrosoftPhoto:LensManufacturer: the only source of Windows Explorer's
 * "Lens model" and "Lens maker" fields, which read neither EXIF nor
 * sidecars), plus the focal length when there is one (primes) and the
 * f-number.
 *
 * A sidecar that LensCatalog did not write (one a computer left on the card,
 * with someone's edits in it) is never overwritten. No android.* import:
 * tested on a bare JDK. Null = ok.
 */
final class XmpSidecar {
    private XmpSidecar() {}

    /** Written into every packet of ours (x:xmptk), so a later run knows it may replace it. */
    static final String TOOLKIT = "LensCatalog";

    /** The sidecar's file: the photo's name with .XMP for its extension. */
    static File fileFor(File photo) {
        String n = photo.getName();
        int dot = n.lastIndexOf('.');
        return new File(photo.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".XMP");
    }

    /** The XMP packet for a lens, as text (UTF-8 when written). */
    static String packet(ExifWriter.Lens l) {
        String model = escape(l.model);
        StringBuilder s = new StringBuilder();
        s.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
        s.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"").append(TOOLKIT).append("\">\n");
        s.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
        s.append("  <rdf:Description rdf:about=\"\"\n");
        s.append("    xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"\n");
        s.append("    xmlns:exifEX=\"http://cipa.jp/exif/1.0/\"\n");
        s.append("    xmlns:aux=\"http://ns.adobe.com/exif/1.0/aux/\"\n");
        s.append("    xmlns:MicrosoftPhoto=\"http://ns.microsoft.com/photo/1.0/\"\n");
        s.append("   exifEX:LensModel=\"").append(model).append("\"\n");
        if (l.make.length() > 0) s.append("   exifEX:LensMake=\"").append(escape(l.make)).append("\"\n");
        s.append("   aux:Lens=\"").append(model).append("\"\n");
        if (l.focalMin > 0 && l.focalMax > 0) {
            String f = l.fNumber > 0 ? Math.round(l.fNumber * 100) + "/100" : "0/0";
            s.append("   aux:LensInfo=\"").append(l.focalMin).append("/1 ").append(l.focalMax).append("/1 ")
                    .append(f).append(' ').append(f).append("\"\n");
        }
        s.append("   MicrosoftPhoto:LensModel=\"").append(model).append("\"\n");
        if (l.make.length() > 0) s.append("   MicrosoftPhoto:LensManufacturer=\"").append(escape(l.make)).append("\"\n");
        if (l.focalMm > 0) s.append("   exif:FocalLength=\"").append(l.focalMm).append("/1\"\n");
        if (l.fNumber > 0) s.append("   exif:FNumber=\"").append(Math.round(l.fNumber * 100)).append("/100\"\n");
        s.append("  />\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n");
        return s.toString();
    }

    /** Whether an XMP packet (as bytes) is one of ours, which a later run may replace. */
    static boolean ours(byte[] packet) {
        try {
            return new String(packet, "UTF-8").indexOf("x:xmptk=\"" + TOOLKIT + "\"") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static String write(File photo, String lensModel, int focalMm, double fNumber) {
        return write(photo, new ExifWriter.Lens(lensModel, focalMm, fNumber));
    }

    static String write(File photo, ExifWriter.Lens l) {
        try {
            File x = fileFor(photo);
            if (x.exists() && !oursFile(x)) return "kept " + x.getName() + " (not written by " + TOOLKIT + ")";
            FileOutputStream out = new FileOutputStream(x);
            try {
                out.write(packet(l).getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** Whether a sidecar was written by LensCatalog (its toolkit attribute says so). */
    private static boolean oursFile(File x) {
        try {
            if (x.length() > 64 * 1024) return false;
            byte[] b = new byte[(int) x.length()];
            FileInputStream in = new FileInputStream(x);
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            return ours(b);
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
