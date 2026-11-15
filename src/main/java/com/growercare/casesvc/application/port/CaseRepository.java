package com.growercare.casesvc.application.port;

import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.domain.CaseChildren.Comment;
import com.growercare.casesvc.domain.CaseChildren.Share;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.security.GcPrincipal;
import com.growercare.shard.ShardId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Shard-local persistence of cases and their children (hot store, Postgres). Every call names its shard. */
public interface CaseRepository {

    Optional<CaseRecord> findById(ShardId shard, long caseId);

    Optional<CaseRecord> findByCaseNo(ShardId shard, String caseNoDigits);

    /** One shard's keyset page; the principal's scope for {@code permission} is ANDed in, so out-of-scope rows are never read. */
    List<CaseRecord> list(ShardId shard, GcPrincipal principal, String permission, ListFilter filter, long beforeId, long fromId, int limit);

    record ListFilter(String status, String caseType, Long territoryId, Long assigneeUserId) { }

    record NewCaseRow(String caseType, String status, long territoryId, String buCode, long growerId, Long farmId,
                      long raisedBy, String channel, Long partnerOrgId, Long productId, String batchNo, String title,
                      String description, String priority, Instant slaDueAt) { }

    long insert(ShardId shard, NewCaseRow row);

    record IdempotencyRecord(long caseId, long requestedBy, byte[] requestHash) { }

    Optional<IdempotencyRecord> findIdempotency(ShardId shard, UUID key);

    /** @return false when another request claimed the key first */
    boolean insertIdempotency(ShardId shard, UUID key, long userId, byte[] hash, long caseId);

    /** @return the new version, or empty when {@code expectedVersion} is stale */
    OptionalInt updateStatus(ShardId shard, long caseId, int expectedVersion, String toStatus, boolean closing);

    OptionalInt updateAssignee(ShardId shard, long caseId, int expectedVersion, long assigneeUserId);

    long insertActivity(ShardId shard, long caseId, String type, long actor, String fromStatus, String toStatus, String note);

    void insertActivityDetail(ShardId shard, long caseId, long activityId, String field, String oldValue, String newValue);

    List<Activity> listActivity(ShardId shard, long caseId);

    /** @return the inserted comment, or empty when the (case, clientMsgId) pair already exists */
    Optional<Comment> insertComment(ShardId shard, long caseId, long author, String authorRole, CommentVisibility visibility,
                                    String body, UUID clientMsgId, Long replyTo);

    Optional<Comment> findCommentByClientMsgId(ShardId shard, long caseId, UUID clientMsgId);

    Optional<Comment> findComment(ShardId shard, long caseId, long commentId);

    List<Comment> listComments(ShardId shard, long caseId, Set<CommentVisibility> visibilities, long beforeId, int limit);

    Optional<Share> findShare(ShardId shard, long caseId, long partnerOrgId);

    Share upsertShare(ShardId shard, long caseId, long partnerOrgId, long sharedBy);

    boolean revokeShare(ShardId shard, long caseId, long partnerOrgId, long revokedBy);
}
