package com.growercare.casesvc.application;

import com.growercare.casesvc.application.CaseQueries.Page;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.CaseChildren.Comment;
import com.growercare.casesvc.domain.CaseChildren.Share;
import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.PermissionDeniedException;
import com.growercare.common.paging.Cursor;
import com.growercare.common.web.TraceIds;
import com.growercare.outbox.OutboxWriter;
import com.growercare.security.EffectiveGrant;
import com.growercare.security.RequiresPermission;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** SPEC-CASE-03: comments (R50–R56) and grower-consented partner sharing (R57–R60). */
@Service
public class CommentsAndSharing {

    public record PostComment(UUID clientMsgId, CommentVisibility visibility, String body, Long replyToCommentId) { }

    public record PostResult(Comment comment, boolean replayed) { }

    private final CaseAccess access;
    private final CaseRepository cases;
    private final ReferenceData ref;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CommentsAndSharing(CaseAccess access, CaseRepository cases, ReferenceData ref, OutboxWriter outbox,
                              TransactionTemplate tx, Clock clock) {
        this.access = access;
        this.cases = cases;
        this.ref = ref;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    // ============================================================================================ comments

    @RequiresPermission("case:read")
    public PostResult post(long caseId, PostComment cmd) {
        var principal = CurrentPrincipal.require();
        var now = clock.instant();
        var located = access.byId(principal, "case:read", caseId);                       // 404 first (R50, E66)
        var shard = located.shard();

        var authorGrant = principal.grantsWith(cmd.visibility().postPermission(), now)     // R51
                .max(Comparator.comparingInt(EffectiveGrant::roleLevel))
                .orElseThrow(() -> new PermissionDeniedException(cmd.visibility().postPermission()));
        if (located.record().isClosed() && principal.userType() != UserType.STAFF) {      // R53
            throw new BusinessRuleException("comment.case-closed", "The case is closed");
        }
        if (cmd.replyToCommentId() != null) {                                              // R54
            var readable = CommentVisibility.readableBy(principal.userType());
            cases.findComment(shard, caseId, cmd.replyToCommentId())
                    .filter(parent -> readable.contains(parent.visibility()))
                    .orElseThrow(() -> new BusinessRuleException("comment.reply-invalid", "The comment to reply to is not part of this case"));
        }

        var inserted = tx.execute(status -> {
            var comment = cases.insertComment(shard, caseId, principal.userId(), authorGrant.roleCode(), cmd.visibility(),
                    cmd.body().strip(), cmd.clientMsgId(), cmd.replyToCommentId());
            comment.ifPresent(c -> outbox.append(CaseCommands.outboxOf(shard), new OutboxWriter.Event("case", Long.toString(caseId),
                    "comment.posted", Map.of("caseId", Long.toString(caseId), "commentId", Long.toString(c.commentId()),
                            "visibility", c.visibility().name(), "authorRole", c.authorRole())), TraceIds.currentOrNull()));
            return comment;
        });
        if (inserted.isPresent()) {
            return new PostResult(inserted.get(), false);
        }
        var existing = cases.findCommentByClientMsgId(shard, caseId, cmd.clientMsgId()).orElseThrow();   // R52
        if (existing.authorUserId() != principal.userId()) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "clientMsgId was already used by another author");
        }
        return new PostResult(existing, true);
    }

    @RequiresPermission("case:read")
    public Page<Comment> comments(long caseId, int limit, String cursor) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:read", caseId);
        long beforeId = cursor == null ? Long.MAX_VALUE : Cursor.decode(cursor);
        var rows = cases.listComments(located.shard(), caseId, CommentVisibility.readableBy(principal.userType()), beforeId, limit + 1);
        if (rows.size() > limit) {
            var page = rows.subList(0, limit);
            return new Page<>(page, Cursor.encode(page.getLast().commentId()));
        }
        return new Page<>(rows, null);
    }

    // ============================================================================================ sharing

    /** R57/R58: only the owning grower (OWN scope of case:share) can consent to share with a partner of the case country. */
    @RequiresPermission("case:share")
    public Share share(long caseId, long partnerOrgId) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:share", caseId);
        var shard = located.shard();
        ref.partner(partnerOrgId)
                .filter(p -> "ACTIVE".equals(p.status()) && p.countryCode().equals(located.record().countryCode()))
                .orElseThrow(() -> new BusinessRuleException("share.partner-invalid", "The partner cannot receive this case"));
        return tx.execute(status -> {
            boolean wasActive = cases.findShare(shard, caseId, partnerOrgId).map(Share::isActive).orElse(false);
            var share = cases.upsertShare(shard, caseId, partnerOrgId, principal.userId());
            if (!wasActive) {
                recordShareChange(shard, caseId, partnerOrgId, principal.userId(), "SHARED", "case.shared");
            }
            return share;
        });
    }

    /** R60: revoke; the partner loses access immediately. Revoking a share that is not active is a no-op. */
    @RequiresPermission("case:share")
    public void unshare(long caseId, long partnerOrgId) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:share", caseId);
        var shard = located.shard();
        tx.executeWithoutResult(status -> {
            if (cases.revokeShare(shard, caseId, partnerOrgId, principal.userId())) {
                recordShareChange(shard, caseId, partnerOrgId, principal.userId(), "UNSHARED", "case.unshared");
            }
        });
    }

    private void recordShareChange(com.growercare.shard.ShardId shard, long caseId, long partnerOrgId, long actor,
                                   String activityType, String eventType) {
        long activityId = cases.insertActivity(shard, caseId, activityType, actor, null, null, null);
        cases.insertActivityDetail(shard, caseId, activityId, "partner_org_id", null, Long.toString(partnerOrgId));
        outbox.append(CaseCommands.outboxOf(shard), new OutboxWriter.Event("case", Long.toString(caseId), eventType,
                Map.of("caseId", Long.toString(caseId), "partnerOrgId", Long.toString(partnerOrgId))), TraceIds.currentOrNull());
    }
}
