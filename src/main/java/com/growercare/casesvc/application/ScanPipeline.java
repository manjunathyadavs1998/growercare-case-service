package com.growercare.casesvc.application;

import com.growercare.casesvc.domain.attachment.ImageMetadataStripper;
import com.growercare.casesvc.domain.attachment.MagicBytes;
import com.growercare.casesvc.domain.attachment.MalwareScanner;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * SPEC-CASE-05 D4, R103–R106: decides what happens to an uploaded file. Plain Java (no Spring, no database, no
 * storage), so it can run unchanged in the case-service poller today and in a Lambda or worker later.
 * <ol>
 *   <li>SHA-256 of the stored bytes = the declared one (else REJECTED);</li>
 *   <li>malware scan first, so malware is reported as INFECTED whatever it claims to be;</li>
 *   <li>magic bytes match the declared type (else REJECTED);</li>
 *   <li>image metadata stripped; unparsable images are REJECTED.</li>
 * </ol>
 */
public final class ScanPipeline {

    public enum Result { CLEAN, INFECTED, REJECTED }

    /** {@code cleanFile} is the file to publish (a new temp file when metadata was stripped); null unless CLEAN. */
    public record Outcome(Result result, Path cleanFile, String reason) { }

    private static final Set<String> STRIPPED_TYPES = Set.of("image/jpeg", "image/png");

    private final MalwareScanner scanner;

    public ScanPipeline(MalwareScanner scanner) {
        this.scanner = scanner;
    }

    public Outcome inspect(Path file, String declaredType, String declaredSha256Hex) {
        try {
            if (!sha256(file).equalsIgnoreCase(declaredSha256Hex)) {
                return new Outcome(Result.REJECTED, null, "checksum mismatch");
            }
            try (InputStream in = Files.newInputStream(file)) {
                if (scanner.scan(in) == MalwareScanner.Verdict.INFECTED) {
                    return new Outcome(Result.INFECTED, null, "malware signature");
                }
            }
            byte[] head;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(MagicBytes.HEAD);
            }
            if (!MagicBytes.matches(declaredType, head)) {
                return new Outcome(Result.REJECTED, null, "content does not match " + declaredType);
            }
            if (!STRIPPED_TYPES.contains(declaredType)) {
                return new Outcome(Result.CLEAN, file, null);
            }
            byte[] stripped;
            try {
                stripped = ImageMetadataStripper.strip(declaredType, Files.readAllBytes(file));   // images ≤ 25 MB (D7)
            } catch (IllegalArgumentException e) {
                return new Outcome(Result.REJECTED, null, "unreadable image");
            }
            var cleanFile = Files.createTempFile("gc-clean-", ".bin");
            Files.write(cleanFile, stripped);
            return new Outcome(Result.CLEAN, cleanFile, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(Path file) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
