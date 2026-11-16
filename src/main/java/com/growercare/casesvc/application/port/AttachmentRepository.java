package com.growercare.casesvc.application.port;

import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.casesvc.domain.attachment.AttachmentRules.TypeRule;
import com.growercare.shard.ShardId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SPEC-CASE-05: attachment rows of one shard (hot store, Postgres). */
public interface AttachmentRepository {

    record NewAttachment(long caseId, String kind, String contentType, long sizeBytes, String sha256Hex, long uploadedBy) { }

    /** An attachment and, when it is linked to a comment, that comment's visibility (D9/D10). */
    record Visible(Attachment attachment, CommentVisibility commentVisibility) { }

    /** D7: {@code ref.attachment_type_rule}. */
    List<TypeRule> typeRules();

    Attachment insert(ShardId shard, NewAttachment attachment);

    /** R99: serialise attachment requests of one case until the transaction ends. */
    void lockCase(long caseId);

    /** R99: AWAITING_UPLOAD + PENDING + CLEAN. */
    int countLive(ShardId shard, long caseId);

    Optional<Visible> find(ShardId shard, long caseId, long attachmentId);

    /** Oldest first. */
    List<Visible> listByCase(ShardId shard, long caseId);

    /** R100: AWAITING_UPLOAD → PENDING. @return false if the row was not awaiting upload */
    boolean markPending(ShardId shard, long caseId, long attachmentId, Instant completedAt);

    /** D4/R114: the next PENDING row not in {@code exclude}, {@code FOR UPDATE SKIP LOCKED} (inside a transaction). */
    Optional<Attachment> claimNext(ShardId shard, Collection<Long> exclude);

    /** R114: increments {@code scan_attempts} of a still-PENDING row. @return the new count, or empty if no longer PENDING */
    Optional<Integer> recordScanFailure(ShardId shard, long caseId, long attachmentId);

    /** R103–R105: PENDING → result. @return false if the row was no longer PENDING */
    boolean markScanned(ShardId shard, long caseId, long attachmentId, Attachment.Status result, Instant scannedAt);

    /** R111: links the caller's own live, unlinked attachments. @return rows linked */
    int linkToComment(ShardId shard, long caseId, Collection<Long> attachmentIds, long uploadedBy, long commentId);

    /** CommentView: attachment ids per comment. */
    Map<Long, List<Long>> idsByComment(ShardId shard, long caseId, Collection<Long> commentIds);

    /** R113: AWAITING_UPLOAD rows created before {@code cutoff} become EXPIRED. @return the expired rows */
    List<Attachment> expireStale(ShardId shard, Instant cutoff);
}
