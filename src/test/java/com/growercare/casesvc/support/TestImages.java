package com.growercare.casesvc.support;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Real images with privacy-relevant metadata, for SPEC-CASE-05 R106 (EXIF GPS, PNG text chunks). */
public final class TestImages {

    /** The industry-standard antivirus test string (harmless; every scanner flags it). */
    public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private TestImages() { }

    /** A 16×16 JPEG with an APP1 "Exif" segment carrying a GPS marker, plus an APP13 (IPTC) segment. */
    public static byte[] jpegWithGps() {
        byte[] plain = encode("jpg");
        var exif = "Exif\0\0GPSLatitude=18.520430;GPSLongitude=73.856743".getBytes(StandardCharsets.ISO_8859_1);
        var iptc = "Photoshop 3.0\0grower: R. Patil".getBytes(StandardCharsets.ISO_8859_1);
        var out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                                   // SOI
        segment(out, 0xE1, exif);
        segment(out, 0xED, iptc);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /** A 16×16 PNG with tEXt and eXIf chunks inserted before IDAT. */
    public static byte[] pngWithText() {
        byte[] plain = encode("png");
        int idat = indexOf(plain, "IDAT".getBytes(StandardCharsets.US_ASCII)) - 4;   // start of the IDAT chunk (length field)
        var out = new ByteArrayOutputStream();
        out.write(plain, 0, idat);
        chunk(out, "tEXt", "Comment\0farm at 18.52,73.85".getBytes(StandardCharsets.ISO_8859_1));
        chunk(out, "eXIf", "MM\0*GPS".getBytes(StandardCharsets.ISO_8859_1));
        out.write(plain, idat, plain.length - idat);
        return out.toByteArray();
    }

    public static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static boolean contains(byte[] haystack, String ascii) {
        return indexOf(haystack, ascii.getBytes(StandardCharsets.ISO_8859_1)) >= 0;
    }

    private static byte[] encode(String format) {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
