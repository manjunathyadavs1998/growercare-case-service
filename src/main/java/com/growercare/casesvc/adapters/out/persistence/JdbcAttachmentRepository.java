package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.AttachmentRepository;
import com.growercare.casesvc.domain.CommentVisibility;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.casesvc.domain.attachment.AttachmentRules.TypeRule;
import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.shard.ShardId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AttachmentRepository} with named SQL from {@code sql/postgres/attachment.sql}. */
@Repository
class JdbcAttachmentRepository implements AttachmentRepository {

    private static final RowMapper<Attachment> ATTACHMENT = (rs, n) -> attachment(rs);
    private static final RowMapper<Visible> VISIBLE = (rs, n) -> new Visible(attachment(rs),
            Optional.ofNullable(rs.getString("comment_visibility")).map(CommentVisibility::valueOf).orElse(null));

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcAttachmentRepository(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public List<TypeRule> typeRules() {
        return jdbc.sql(sql.get("attachment.typeRules"))
                .query((rs, n) -> new TypeRule(rs.getString("kind"), rs.getString("content_type"), rs.getLong("max_bytes")))
                .list();
    }

    @Override
    public Attachment insert(ShardId shard, NewAttachment a) {
        return jdbc.sql(sql.render("attachment.insert", shard.schemaName()))
                .param("caseId", a.caseId())
                .param("kind", a.kind())
                .param("keyPrefix", Attachment.keyPrefix(shard.schemaName(), a.caseId()))
                .param("contentType", a.contentType())
                .param("sizeBytes", a.sizeBytes())
                .param("sha256Hex", a.sha256Hex())
                .param("uploadedBy", a.uploadedBy())
                .query(ATTACHMENT).single();
    }

    @Override
    public void lockCase(long caseId) {
        jdbc.sql(sql.get("attachment.lockCaseAttachments")).param("caseId", Long.toString(caseId)).query().listOfRows();
    }

    @Override
    public int countLive(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("attachment.countLive", shard.schemaName())).param("caseId", caseId)
                .query(Integer.class).single();
    }

    @Override
    public Optional<Visible> find(ShardId shard, long caseId, long attachmentId) {
        return jdbc.sql(sql.render("attachment.find", shard.schemaName()))
                .param("caseId", caseId).param("attachmentId", attachmentId)
                .query(VISIBLE).optional();
    }

    @Override
    public List<Visible> listByCase(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("attachment.listByCase", shard.schemaName())).param("caseId", caseId).query(VISIBLE).list();
    }

    @Override
    public boolean markPending(ShardId shard, long caseId, long attachmentId, Instant completedAt) {
        return jdbc.sql(sql.render("attachment.markPending", shard.schemaName()))
                .param("caseId", caseId).param("attachmentId", attachmentId).param("completedAt", utc(completedAt))
                .update() == 1;
    }

    @Override
    public Optional<Attachment> claimNext(ShardId shard, Collection<Long> exclude) {
        return jdbc.sql(sql.render("attachment.claimNext", shard.schemaName()))
                .param("exclude", exclude.isEmpty() ? List.of(-1L) : List.copyOf(exclude))     // NOT IN () is invalid SQL
                .query(ATTACHMENT).optional();
    }

    @Override
    public Optional<Integer> recordScanFailure(ShardId shard, long caseId, long attachmentId) {
        return jdbc.sql(sql.render("attachment.recordScanFailure", shard.schemaName()))
                .param("caseId", caseId).param("attachmentId", attachmentId)
                .query(Integer.class).optional();
    }

    @Override
    public boolean markScanned(ShardId shard, long caseId, long attachmentId, Attachment.Status result, Instant scannedAt) {
        return jdbc.sql(sql.render("attachment.markScanned", shard.schemaName()))
                .param("caseId", caseId).param("attachmentId", attachmentId)
                .param("result", result.name()).param("scannedAt", utc(scannedAt))
                .update() == 1;
    }

    @Override
    public int linkToComment(ShardId shard, long caseId, Collection<Long> attachmentIds, long uploadedBy, long commentId) {
        if (attachmentIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql(sql.render("attachment.linkToComment", shard.schemaName()))
                .param("caseId", caseId).param("attachmentIds", List.copyOf(attachmentIds))
                .param("uploadedBy", uploadedBy).param("commentId", commentId)
                .update();
    }

    @Override
    public Map<Long, List<Long>> idsByComment(ShardId shard, long caseId, Collection<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        record Link(long commentId, long attachmentId) { }
        return jdbc.sql(sql.render("attachment.idsByComment", shard.schemaName()))
                .param("caseId", caseId).param("commentIds", List.copyOf(commentIds))
                .query((rs, n) -> new Link(rs.getLong("comment_id"), rs.getLong("attachment_id")))
                .list().stream()
                .collect(Collectors.groupingBy(Link::commentId, LinkedHashMap::new,
                        Collectors.mapping(Link::attachmentId, Collectors.toList())));
    }

    @Override
    public List<Attachment> expireStale(ShardId shard, Instant cutoff) {
        return jdbc.sql(sql.render("attachment.expireStale", shard.schemaName())).param("cutoff", utc(cutoff)).query(ATTACHMENT).list();
    }

    private static Attachment attachment(ResultSet rs) throws SQLException {
        return new Attachment(
                rs.getLong("case_id"), rs.getLong("attachment_id"), rs.getString("kind"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256_hex"), Attachment.Status.valueOf(rs.getString("scan_status")),
                rs.getString("object_key"), rs.getLong("uploaded_by_user_id"), rs.getObject("comment_id", Long.class),
                instant(rs, "created_at"), instant(rs, "completed_at"), instant(rs, "scanned_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
