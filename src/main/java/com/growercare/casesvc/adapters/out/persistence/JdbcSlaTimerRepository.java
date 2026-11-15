package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.casesvc.application.port.SlaTimerRepository;
import com.growercare.casesvc.domain.SlaRules.Timer;
import com.growercare.casesvc.domain.SlaRules.TimerKind;
import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.shard.ShardId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link SlaTimerRepository} with named SQL from {@code sql/postgres/maintenance.sql}. */
@Repository
class JdbcSlaTimerRepository implements SlaTimerRepository {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcSlaTimerRepository(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void insertTimers(ShardId shard, long caseId, List<Timer> timers) {
        var statement = sql.render("maintenance.insertTimer", shard.schemaName());
        timers.forEach(t -> jdbc.sql(statement)
                .param("caseId", caseId).param("kind", t.kind().name()).param("dueAt", utc(t.dueAt()))
                .update());
    }

    @Override
    public int cancelTimers(ShardId shard, long caseId) {
        return jdbc.sql(sql.render("maintenance.cancelTimers", shard.schemaName())).param("caseId", caseId).update();
    }

    @Override
    public void rearmSla(ShardId shard, long caseId, Instant startedAt, Instant dueAt) {
        jdbc.sql(sql.render("maintenance.rearmSla", shard.schemaName()))
                .param("caseId", caseId).param("startedAt", utc(startedAt)).param("dueAt", utc(dueAt))
                .update();
    }

    @Override
    public List<DueTimer> claimDueTimers(ShardId shard, int limit) {
        return jdbc.sql(sql.render("maintenance.claimDueTimers", shard.schemaName()))
                .param("limit", limit)
                .query((rs, n) -> new DueTimer(rs.getLong("case_id"), rs.getLong("timer_id"),
                        TimerKind.valueOf(rs.getString("kind")),
                        rs.getObject("sla_due_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public boolean markFired(ShardId shard, long caseId, long timerId) {
        return jdbc.sql(sql.render("maintenance.markTimerFired", shard.schemaName()))
                .param("caseId", caseId).param("timerId", timerId).update() == 1;
    }

    @Override
    public boolean tryJobLock(String job, ShardId shard) {
        return Boolean.TRUE.equals(jdbc.sql(sql.get("maintenance.tryJobLock"))
                .param("lockKey", "gc.job." + job + "." + shard.schemaName())
                .query(Boolean.class).single());
    }

    @Override
    public int ensurePartitions(ShardId shard, int weeksAhead) {
        return jdbc.sql(sql.render("maintenance.ensurePartitions", shard.schemaName()))
                .param("weeksAhead", weeksAhead).query(Integer.class).single();
    }

    @Override
    public int deleteExpiredIdempotency(ShardId shard, Instant cutoff) {
        return jdbc.sql(sql.render("maintenance.deleteExpiredIdempotency", shard.schemaName()))
                .param("cutoff", utc(cutoff)).update();
    }

    @Override
    public long defaultPartitionRows(ShardId shard) {
        return jdbc.sql(sql.render("maintenance.defaultPartitionRows", shard.schemaName())).query(Long.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
