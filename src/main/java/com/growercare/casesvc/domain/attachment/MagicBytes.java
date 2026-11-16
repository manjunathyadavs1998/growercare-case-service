package com.growercare.casesvc.domain.attachment;

/** SPEC-CASE-05 R105: does a file start the way its declared content type requires? */
public final class MagicBytes {

    /** Bytes needed from the start of a file to decide. */
    public static final int HEAD = 16;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};

    private MagicBytes() { }

    public static boolean matches(String contentType, byte[] head) {
        return switch (AttachmentRules.normalise(contentType)) {
            case "image/jpeg" -> startsWith(head, 0, JPEG);
            case "image/png" -> startsWith(head, 0, PNG);
            case "application/pdf" -> startsWith(head, 0, PDF);
            case "video/mp4" -> startsWith(head, 4, FTYP);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int offset, byte[] signature) {
        if (head.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
