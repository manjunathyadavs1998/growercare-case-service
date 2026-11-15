package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.domain.CaseChildren.Comment;
import com.growercare.casesvc.domain.CaseChildren.Share;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.security.GcPrincipal;
import com.growercare.security.ScopeFilter;
import com.growercare.shard.CaseNumber;
import com.growercare.shard.ShardId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CaseRepository} over the shard schemas with named SQL ({@code sql/postgres/case.sql}, {@code comment.sql}). */
@Repository
class JdbcCaseRepository implements CaseRepository {

    private static final String SCOPE_TOKEN = "{scope}";

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;
    private final ScopeFilter scopeFilter;

    JdbcCaseRepository(JdbcClient jdbc, NamedSqlRegistry sql, ScopeFilter scopeFilter) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.scopeFilter = scopeFilter;
    }

    // ------------------------------------------------------------------------------------------------ cases

    static final RowMapper<CaseRecord> CASE = (rs, n) -> new CaseRecord(
            rs.getLong("case_id"),
            new CaseNumber(rs.getString("case_no")),
            rs.getString("country_code"),
            rs.getString("case_type_code"),
            rs.getString("status_code"),
            rs.getLong("territory_id"),
            rs.getString("bu_code"),
            rs.getLong("grower_id"),
            rs.getObject("grower_user_id", Long.class),
            rs.getString("grower_name"),
            rs.getString("grower_phone_last4"),
            rs.getObject("farm_id", Long.class),
            rs.getLong("raised_by_user_id"),
            rs.getString("raised_channel"),
            rs.getObject("raised_via_partner_org_id", Long.class),
            rs.getObject("product_id", Long.class),
            rs.getString("batch_no"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("priority"),
            rs.getObject("assignee_user_id", Long.class),
            instant(rs, "sla_due_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "closed_at"),
            rs.getInt("version"),
            longs(rs, "active_shares"));

    @Override
    public Optional<CaseRecord> findById(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("case.findById", shard.schemaName())).param("caseId", caseId).query(CASE).optional();
    }

    @Override
    public Optional<CaseRecord> findByCaseNo(ShardId shard, String caseNoDigits) {
        return jdbc.sql(sql.render("case.findByCaseNo", shard.schemaName())).param("caseNo", caseNoDigits).query(CASE).optional();
    }

    @Override
    public List<CaseRecord> list(ShardId shard, GcPrincipal principal, String permission, ListFilter filter, long beforeId, long fromId, int limit) {
        var scope = CaseAccessScope.predicate(scopeFilter, principal, permission, shard);
        // {scope} is generated from code-defined column fragments (ScopeFilter.Columns); all values are bind parameters.
        var statement = sql.render("case.list", shard.schemaName()).replace(SCOPE_TOKEN, scope.sql());
        return jdbc.sql(statement)
                .params(scope.params())
                .param("beforeId", beforeId)
                .param("fromId", fromId)
                .param("status", filter.status())
                .param("caseType", filter.caseType())
                .param("territoryId", filter.territoryId())
                .param("assignee", filter.assigneeUserId())
                .param("limit", limit)
                .query(CASE)
                .list();
    }

    @Override
    public long insert(ShardId shard, NewCaseRow r) {
        return jdbc.sql(sql.render("case.insert", shard.schemaName()))
                .param("caseType", r.caseType()).param("status", r.status()).param("territoryId", r.territoryId())
                .param("buCode", r.buCode()).param("growerId", r.growerId()).param("farmId", r.farmId())
                .param("raisedBy", r.raisedBy()).param("channel", r.channel()).param("partnerOrgId", r.partnerOrgId())
                .param("productId", r.productId()).param("batchNo", r.batchNo()).param("title", r.title())
                .param("description", r.description()).param("priority", r.priority())
                .param("slaDueAt", OffsetDateTime.ofInstant(r.slaDueAt(), ZoneOffset.UTC))
                .query(Long.class).single();
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(ShardId shard, UUID key) {
        return jdbc.sql(sql.render("case.findIdempotency", shard.schemaName())).param("key", key)
                .query((rs, n) -> new IdempotencyRecord(rs.getLong("case_id"), rs.getLong("requested_by_user_id"), rs.getBytes("request_hash")))
                .optional();
    }

    @Override
    public boolean insertIdempotency(ShardId shard, UUID key, long userId, byte[] hash, long caseId) {
        return jdbc.sql(sql.render("case.insertIdempotency", shard.schemaName()))
                .param("key", key).param("userId", userId).param("hash", hash).param("caseId", caseId)
                .query(UUID.class).optional().isPresent();
    }

    @Override
    public OptionalInt updateStatus(ShardId shard, long caseId, int expectedVersion, String toStatus, boolean closing) {
        return version(jdbc.sql(sql.render("case.updateStatus", shard.schemaName()))
                .param("caseId", caseId).param("expectedVersion", expectedVersion)
                .param("toStatus", toStatus).param("closing", closing)
                .query(Integer.class).optional());
    }

    @Override
    public OptionalInt updateAssignee(ShardId shard, long caseId, int expectedVersion, long assigneeUserId) {
        return version(jdbc.sql(sql.render("case.updateAssignee", shard.schemaName()))
                .param("caseId", caseId).param("expectedVersion", expectedVersion).param("assignee", assigneeUserId)
                .query(Integer.class).optional());
    }

    // ------------------------------------------------------------------------------------------------ activity

    @Override
    public long insertActivity(ShardId shard, long caseId, String type, long actor, String fromStatus, String toStatus, String note) {
        return jdbc.sql(sql.render("case.insertActivity", shard.schemaName()))
                .param("caseId", caseId).param("type", type).param("actor", actor)
                .param("fromStatus", fromStatus).param("toStatus", toStatus).param("note", note)
                .query(Long.class).single();
    }

    @Override
    public void insertActivityDetail(ShardId shard, long caseId, long activityId, String field, String oldValue, String newValue) {
        jdbc.sql(sql.render("case.insertActivityDetail", shard.schemaName()))
                .param("caseId", caseId).param("activityId", activityId).param("field", field)
                .param("oldValue", oldValue).param("newValue", newValue)
                .update();
    }

    @Override
    public List<Activity> listActivity(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("case.listActivity", shard.schemaName())).param("caseId", caseId)
                .query((rs, n) -> new Activity(rs.getLong("activity_id"), rs.getString("activity_type"), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getLong("actor_user_id"), rs.getString("note"), instant(rs, "occurred_at")))
                .list();
    }

    // ------------------------------------------------------------------------------------------------ comments

    static final RowMapper<Comment> COMMENT = (rs, n) -> new Comment(
            rs.getLong("case_id"), rs.getLong("comment_id"), rs.getLong("author_user_id"), rs.getString("author_role"),
            CommentVisibility.valueOf(rs.getString("visibility")), rs.getString("body"),
            rs.getObject("reply_to_comment_id", Long.class), instant(rs, "created_at"));

    @Override
    public Optional<Comment> insertComment(ShardId shard, long caseId, long author, String authorRole, CommentVisibility visibility,
                                           String body, UUID clientMsgId, Long replyTo) {
        return jdbc.sql(sql.render("comment.insert", shard.schemaName()))
                .param("caseId", caseId).param("authorUserId", author).param("authorRole", authorRole)
                .param("visibility", visibility.name()).param("body", body).param("clientMsgId", clientMsgId)
                .param("replyTo", replyTo)
                .query(COMMENT).optional();
    }

    @Override
    public Optional<Comment> findCommentByClientMsgId(ShardId shard, long caseId, UUID clientMsgId) {
        return jdbc.sql(sql.render("comment.findByClientMsgId", shard.schemaName()))
                .param("caseId", caseId).param("clientMsgId", clientMsgId).query(COMMENT).optional();
    }

    @Override
    public Optional<Comment> findComment(ShardId shard, long caseId, long commentId) {
        return jdbc.sql(sql.render("comment.findById", shard.schemaName()))
                .param("caseId", caseId).param("commentId", commentId).query(COMMENT).optional();
    }

    @Override
    public List<Comment> listComments(ShardId shard, long caseId, Set<CommentVisibility> visibilities, long beforeId, int limit) {
        return jdbc.sql(sql.render("comment.list", shard.schemaName()))
                .param("caseId", caseId)
                .param("visibilities", visibilities.stream().map(Enum::name).sorted().toList())
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(COMMENT).list();
    }

    // ------------------------------------------------------------------------------------------------ shares

    static final RowMapper<Share> SHARE = (rs, n) -> new Share(
            rs.getLong("case_id"), rs.getLong("partner_org_id"), instant(rs, "consent_given_at"), instant(rs, "revoked_at"));

    @Override
    public Optional<Share> findShare(ShardId shard, long caseId, long partnerOrgId) {
        return jdbc.sql(sql.render("comment.findShare", shard.schemaName()))
                .param("caseId", caseId).param("partnerOrgId", partnerOrgId).query(SHARE).optional();
    }

    @Override
    public Share upsertShare(ShardId shard, long caseId, long partnerOrgId, long sharedBy) {
        return jdbc.sql(sql.render("comment.upsertShare", shard.schemaName()))
                .param("caseId", caseId).param("partnerOrgId", partnerOrgId).param("sharedBy", sharedBy)
                .query(SHARE).single();
    }

    @Override
    public boolean revokeShare(ShardId shard, long caseId, long partnerOrgId, long revokedBy) {
        return jdbc.sql(sql.render("comment.revokeShare", shard.schemaName()))
                .param("caseId", caseId).param("partnerOrgId", partnerOrgId).param("revokedBy", revokedBy)
                .query(Long.class).optional().isPresent();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static OptionalInt version(Optional<Integer> v) {
        return v.map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Set<Long> longs(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((Long[]) array.getArray()).collect(Collectors.toUnmodifiableSet());
    }
}
