package com.growercare.casesvc.domain.attachment;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** SPEC-CASE-05: one file attached to a case (and optionally to one of its comments). */
public record Attachment(long caseId, long attachmentId, String kind, String contentType, long sizeBytes, String sha256Hex,
                         Status status, String objectKey, long uploadedByUserId, Long commentId,
                         Instant createdAt, Instant completedAt, Instant scannedAt) {

    /** AWAITING_UPLOAD → PENDING → CLEAN | INFECTED | REJECTED; AWAITING_UPLOAD → EXPIRED. */
    public enum Status {
        AWAITING_UPLOAD, PENDING, CLEAN, INFECTED, REJECTED, EXPIRED;

        /** R99 counts these; R111 may link these to a comment. */
        public static final Set<Status> LIVE = EnumSet.of(AWAITING_UPLOAD, PENDING, CLEAN);
    }

    /** D2: storage key = ids only (no file name, no PII). */
    public static String objectKey(String shardSchema, long caseId, long attachmentId) {
        return keyPrefix(shardSchema, caseId) + attachmentId;
    }

    /** The key without the attachment id (the database appends the generated id). */
    public static String keyPrefix(String shardSchema, long caseId) {
        return shardSchema + "/" + caseId + "/";
    }
}
