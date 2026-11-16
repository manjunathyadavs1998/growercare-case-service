package com.growercare.casesvc.domain.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * SPEC-CASE-05 D5: the local/test scanner. It flags the standard EICAR antivirus test file wherever it occurs in the
 * stream (chunked, with overlap across chunk boundaries). It is <b>not</b> a malware scanner; production plugs a real one
 * into {@link MalwareScanner}.
 */
public final class EicarScanner implements MalwareScanner {

    static final int CHUNK = 64 * 1024;
    private static final byte[] SIGNATURE =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);

    @Override
    public Verdict scan(InputStream content) throws IOException {
        var window = new byte[CHUNK + SIGNATURE.length];
        int carried = 0;
        int read;
        while ((read = content.readNBytes(window, carried, CHUNK)) > 0) {
            int filled = carried + read;
            if (indexOf(window, filled) >= 0) {
                return Verdict.INFECTED;
            }
            carried = Math.min(SIGNATURE.length - 1, filled);           // keep the tail: a match may straddle chunks
            System.arraycopy(window, filled - carried, window, 0, carried);
        }
        return Verdict.CLEAN;
    }

    private static int indexOf(byte[] buffer, int length) {
        outer:
        for (int i = 0; i <= length - SIGNATURE.length; i++) {
            for (int j = 0; j < SIGNATURE.length; j++) {
                if (buffer[i + j] != SIGNATURE[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
