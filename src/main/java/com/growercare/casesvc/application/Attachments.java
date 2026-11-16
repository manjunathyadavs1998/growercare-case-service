package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.AttachmentRepository;
import com.growercare.casesvc.application.port.AttachmentRepository.Visible;
import com.growercare.casesvc.application.port.ObjectStore;
import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.casesvc.domain.attachment.AttachmentRules;
import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.security.GcPrincipal;
import com.growercare.security.RequiresPermission;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** SPEC-CASE-05 R96–R102, R108–R112: requesting, completing, listing and downloading attachments. */
@Service
public class Attachments {

    static final Duration UPLOAD_TTL = Duration.ofMinutes(5);
    static final Duration DOWNLOAD_TTL = Duration.ofSeconds(60);

    public record RequestUpload(String kind, String contentType, long sizeBytes, String sha256Hex) { }

    public record Upload(Attachment attachment, ObjectStore.PresignedRequest request) { }

    private final CaseAccess access;
    private final AttachmentRepository repo;
    private final ObjectStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Attachments(CaseAccess access, AttachmentRepository repo, ObjectStore store, TransactionTemplate tx, Clock clock) {
        this.access = access;
        this.repo = repo;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    /** R96–R99. */
    @RequiresPermission("case:attach")
    public Upload request(long caseId, RequestUpload cmd) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:attach", caseId);                                     // R98: 404
        AttachmentRules.check(repo.typeRules(), cmd.kind(), cmd.contentType(), cmd.sizeBytes())          // R97: 400
                .ifPresent(v -> { throw ValidationException.of(v.field(), v.message()); });
        if (located.record().isClosed() && principal.userType() != UserType.STAFF) {                     // R99
            throw new BusinessRuleException("attachment.case-closed", "The case is closed");
        }
        var shard = located.shard();
        var attachment = tx.execute(status -> {
            repo.lockCase(caseId);                                                                        // R99: no race
            if (repo.countLive(shard, caseId) >= AttachmentRules.MAX_LIVE_PER_CASE) {                    // R99
                throw new BusinessRuleException("attachment.limit-reached",
                        "A case can hold at most " + AttachmentRules.MAX_LIVE_PER_CASE + " attachments");
            }
            return repo.insert(shard, new AttachmentRepository.NewAttachment(caseId, cmd.kind(),
                    AttachmentRules.normalise(cmd.contentType()), cmd.sizeBytes(), cmd.sha256Hex().toLowerCase(java.util.Locale.ROOT),
                    principal.userId()));
        });
        var upload = store.presignUpload(attachment.objectKey(), attachment.contentType(), attachment.sizeBytes(),
                attachment.sha256Hex(), UPLOAD_TTL);
        return new Upload(attachment, upload);
    }

    /** R100–R102: only the uploader completes; idempotent once past AWAITING_UPLOAD. */
    @RequiresPermission("case:attach")
    public Attachment complete(long caseId, long attachmentId) {
        var principal = CurrentPrincipal.require();
        var shard = access.byId(principal, "case:attach", caseId).shard();
        var attachment = repo.find(shard, caseId, attachmentId).map(Visible::attachment)
                .filter(a -> a.uploadedByUserId() == principal.userId())                                 // R102
                .orElseThrow(Attachments::notFound);
        if (attachment.status() != Attachment.Status.AWAITING_UPLOAD) {
            return attachment;                                                                           // R100 idempotent
        }
        var stored = store.size(ObjectStore.Area.STAGING, attachment.objectKey())
                .orElseThrow(() -> new BusinessRuleException("attachment.upload-missing", "Nothing was uploaded for this attachment"));
        if (stored != attachment.sizeBytes()) {
            throw new BusinessRuleException("attachment.upload-mismatch", "The uploaded file does not match the declared size");
        }
        repo.markPending(shard, caseId, attachmentId, clock.instant());
        return repo.find(shard, caseId, attachmentId).orElseThrow().attachment();
    }

    /** R108. */
    @RequiresPermission("case:read")
    public List<Attachment> list(long caseId) {
        var principal = CurrentPrincipal.require();
        var shard = access.byId(principal, "case:read", caseId).shard();
        return repo.listByCase(shard, caseId).stream()
                .filter(v -> visibleTo(v, principal))
                .map(Visible::attachment)
                .toList();
    }

    /** R109/R110. */
    @RequiresPermission("case:read")
    public ObjectStore.PresignedRequest download(long caseId, long attachmentId) {
        var principal = CurrentPrincipal.require();
        var shard = access.byId(principal, "case:read", caseId).shard();
        var attachment = repo.find(shard, caseId, attachmentId)
                .filter(v -> visibleTo(v, principal))
                .map(Visible::attachment)
                .orElseThrow(Attachments::notFound);
        if (attachment.status() != Attachment.Status.CLEAN) {
            throw ConflictException.notReady("attachment.not-available", "The attachment is not available for download");
        }
        return store.presignDownload(attachment.objectKey(), DOWNLOAD_TTL);
    }

    /**
     * R111/R112: called inside the comment's transaction. Every id must be the caller's own live, unlinked attachment
     * on this case; otherwise the whole comment is rejected.
     */
    void linkToComment(ShardId shard, long caseId, Collection<Long> attachmentIds, GcPrincipal author, long commentId) {
        if (attachmentIds.isEmpty()) {
            return;
        }
        var distinct = attachmentIds.stream().distinct().toList();
        if (distinct.size() != attachmentIds.size() || distinct.size() > AttachmentRules.MAX_PER_COMMENT
                || repo.linkToComment(shard, caseId, distinct, author.userId(), commentId) != distinct.size()) {
            throw new BusinessRuleException("comment.attachment-invalid",
                    "Attachments must be your own unlinked files on this case (at most " + AttachmentRules.MAX_PER_COMMENT + ")");
        }
    }

    /** D10: comment visibility first; non-CLEAN files only for their uploader and staff. */
    static boolean visibleTo(Visible row, GcPrincipal principal) {
        if (row.commentVisibility() != null && !CommentVisibility.readableBy(principal.userType()).contains(row.commentVisibility())) {
            return false;
        }
        var attachment = row.attachment();
        return attachment.status() == Attachment.Status.CLEAN
                || principal.userType() == UserType.STAFF
                || attachment.uploadedByUserId() == principal.userId();
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Attachment");
    }
}
