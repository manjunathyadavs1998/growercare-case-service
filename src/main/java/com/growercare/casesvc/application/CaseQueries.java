package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.ArchiveCaseQueryPort;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.common.error.GcException;
import com.growercare.common.paging.Cursor;
import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.RequiresPermission;
import com.growercare.security.ScopeKind;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.IdCodec;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Case read use cases (SPEC-CASE-02 R28–R33) and the activity trail (SPEC-CASE-03 R61). */
@Service
public class CaseQueries {

    /** Hot window (ADR-003): older data lives in the archive. */
    public static final Duration HOT_WINDOW = Duration.ofDays(90);
    /** Max concurrent shard queries per list request (each borrows a pooled connection). */
    private static final int FAN_OUT_PARALLELISM = 16;

    public record ListQuery(String status, String caseType, Long territoryId, boolean assignedToMe, Instant createdFrom,
                            int limit, String cursor) { }

    public record Page<T>(List<T> items, String nextCursor) { }

    private final CaseAccess access;
    private final CaseRepository cases;
    private final ReferenceData ref;
    private final ShardRouter router;
    private final ArchiveCaseQueryPort archive;
    private final Clock clock;

    public CaseQueries(CaseAccess access, CaseRepository cases, ReferenceData ref, ShardRouter router,
                       ArchiveCaseQueryPort archive, Clock clock) {
        this.access = access;
        this.cases = cases;
        this.ref = ref;
        this.router = router;
        this.archive = archive;
        this.clock = clock;
    }

    @RequiresPermission("case:read")
    public CaseRecord get(long caseId) {
        return access.byId(CurrentPrincipal.require(), "case:read", caseId).record();
    }

    @RequiresPermission("case:read")
    public CaseRecord getByNumber(String caseNo) {
        return access.byNumber(CurrentPrincipal.require(), "case:read", caseNo).record();
    }

    /** R31: scope-filtered keyset page, merged across the caller's shards (decision D1). */
    @RequiresPermission("case:read")
    public Page<CaseRecord> list(ListQuery q) {
        var principal = CurrentPrincipal.require();
        var now = clock.instant();
        if (q.createdFrom() != null && q.createdFrom().isBefore(now.minus(HOT_WINDOW))) {
            archive.list(principal, q.createdFrom(), q.limit(), q.cursor());        // R33: 501 until Snowflake
        }
        long beforeId = q.cursor() == null ? Long.MAX_VALUE : Cursor.decode(q.cursor());
        long fromId = q.createdFrom() == null || q.createdFrom().isBefore(Instant.ofEpochMilli(IdCodec.EPOCH_MS))
                ? 0 : IdCodec.floor(q.createdFrom());
        var filter = new CaseRepository.ListFilter(q.status(), q.caseType(), q.territoryId(),
                q.assignedToMe() ? principal.userId() : null);

        var rows = fanOut(targetShards(principal, now), shard ->
                cases.list(shard, principal, "case:read", filter, beforeId, fromId, q.limit() + 1));
        var sorted = rows.stream().sorted(Comparator.comparingLong(CaseRecord::caseId).reversed()).limit(q.limit() + 1L).toList();
        if (sorted.size() > q.limit()) {
            var page = sorted.subList(0, q.limit());
            return new Page<>(page, Cursor.encode(page.getLast().caseId()));
        }
        return new Page<>(sorted, null);
    }

    /** R61: the trail; staff notes are hidden from growers and partners. */
    @RequiresPermission("case:read")
    public List<Activity> activity(long caseId) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:read", caseId);
        var staff = principal.userType() == UserType.STAFF;
        return cases.listActivity(located.shard(), caseId).stream()
                .map(a -> staff ? a : new Activity(a.activityId(), a.type(), a.fromStatus(), a.toStatus(), a.actorUserId(), null, a.occurredAt()))
                .toList();
    }

    /**
     * Shards that can contain rows visible to the principal: own growers' shards for OWN grants, every shard of the
     * covered countries for ORG / ASSIGNED / geographic grants. GLOBAL grants contribute none (aggregates only).
     */
    Set<ShardId> targetShards(GcPrincipal principal, Instant now) {
        var shards = new LinkedHashSet<ShardId>();
        var countries = new HashSet<String>();
        for (EffectiveGrant grant : principal.grantsWith("case:read", now).toList()) {
            switch (grant.scopeKind()) {
                case OWN -> ref.growersOfUser(principal.userId())
                        .forEach(g -> shards.add(router.forGrower(g.countryCode(), g.growerId())));
                case ORG -> countries.add(principal.homeCountry());
                case ASSIGNED, TERRITORY, COUNTRY, REGION -> {
                    countries.addAll(grant.countries());
                    countries.addAll(ref.countriesOfTerritories(grant.territories()));
                }
                case GLOBAL -> { }
            }
        }
        countries.stream().sorted().forEach(c -> shards.addAll(ref.shardsOf(c)));
        return shards;
    }

    /** Runs {@code query} on every shard in parallel (virtual threads, bounded) and concatenates the results. */
    private List<CaseRecord> fanOut(Set<ShardId> shards, java.util.function.Function<ShardId, List<CaseRecord>> query) {
        if (shards.isEmpty()) {
            return List.of();
        }
        var permits = new Semaphore(FAN_OUT_PARALLELISM);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<CaseRecord>>> futures = shards.stream()
                    .map(shard -> executor.submit(() -> {
                        permits.acquire();
                        try {
                            return query.apply(shard);
                        } finally {
                            permits.release();
                        }
                    }))
                    .collect(Collectors.toList());
            var all = new ArrayList<CaseRecord>();
            for (var future : futures) {
                all.addAll(future.get());
            }
            return all;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("case database", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GcException gc) {
                throw gc;
            }
            throw new DependencyUnavailableException("case database", e.getCause());
        }
    }
}
