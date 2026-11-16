package com.growercare.casesvc.domain.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * SPEC-CASE-05 D6 / R106: removes metadata that can carry personal data (EXIF GPS, IPTC, comments, text chunks)
 * without re-encoding pixels. Malformed images throw {@link IllegalArgumentException} (the scan job rejects them).
 */
public final class ImageMetadataStripper {

    /** JPEG segments dropped: APP1 (Exif/XMP), APP13 (IPTC/Photoshop), COM (free text). */
    private static final Set<Integer> JPEG_DROP = Set.of(0xE1, 0xED, 0xFE);
    /** PNG chunks dropped: EXIF and every text chunk. */
    private static final Set<String> PNG_DROP = Set.of("eXIf", "tEXt", "iTXt", "zTXt");
    private static final int SOS = 0xDA;

    private ImageMetadataStripper() { }

    /** @return the stripped image, or the same array for types without metadata stripping (PDF, MP4) */
    public static byte[] strip(String contentType, byte[] file) {
        return switch (AttachmentRules.normalise(contentType)) {
            case "image/jpeg" -> jpeg(file);
            case "image/png" -> png(file);
            default -> file;
        };
    }

    private static byte[] jpeg(byte[] in) {
        if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("not a JPEG");
        }
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 2);                                        // SOI
        int pos = 2;
        while (pos < in.length) {
            if (pos + 4 > in.length || (in[pos] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("corrupt JPEG segment at " + pos);
            }
            int marker = in[pos + 1] & 0xFF;
            int length = ((in[pos + 2] & 0xFF) << 8) | (in[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > in.length) {
                throw new IllegalArgumentException("corrupt JPEG segment length at " + pos);
            }
            if (marker == SOS) {                                     // entropy-coded data follows: copy the rest as is
                out.write(in, pos, in.length - pos);
                return out.toByteArray();
            }
            if (!JPEG_DROP.contains(marker)) {
                out.write(in, pos, 2 + length);
            }
            pos += 2 + length;
        }
        throw new IllegalArgumentException("JPEG without image data");
    }

    private static byte[] png(byte[] in) {
        if (in.length < 8 || !MagicBytes.matches("image/png", in)) {
            throw new IllegalArgumentException("not a PNG");
        }
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int pos = 8;
        boolean ended = false;
        while (pos < in.length) {
            if (pos + 12 > in.length) {
                throw new IllegalArgumentException("corrupt PNG chunk at " + pos);
            }
            long length = ((in[pos] & 0xFFL) << 24) | ((in[pos + 1] & 0xFF) << 16) | ((in[pos + 2] & 0xFF) << 8) | (in[pos + 3] & 0xFF);
            if (length > in.length - pos - 12L) {
                throw new IllegalArgumentException("corrupt PNG chunk length at " + pos);
            }
            var type = new String(in, pos + 4, 4, StandardCharsets.US_ASCII);
            int total = 12 + (int) length;
            if (!PNG_DROP.contains(type)) {
                out.write(in, pos, total);
            }
            pos += total;
            if ("IEND".equals(type)) {
                ended = true;
                break;
            }
        }
        if (!ended) {
            throw new IllegalArgumentException("PNG without IEND");
        }
        return out.toByteArray();
    }
}
