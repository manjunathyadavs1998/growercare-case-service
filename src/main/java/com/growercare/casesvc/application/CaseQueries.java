package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.ArchiveCaseQueryPort;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.CaseChildren.Activity;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.casesvc.domain.SlaRules;
import com.growercare.common.error.GcException;
import com.growercare.common.error.ValidationException;
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
                            int limit, String cursor, String queueCode, SlaRules.SlaState slaState,
                            Long growerId, Long productId, String batchNo) {

        /** SPEC-CASE-07 R204: what a cursor is bound to (every filter, not the page size). */
        String fingerprint() {
            var text = String.join("|", String.valueOf(status), String.valueOf(caseType), String.valueOf(territoryId),
                    String.valueOf(assignedToMe), String.valueOf(createdFrom), String.valueOf(queueCode), String.valueOf(slaState),
                    String.valueOf(growerId), String.valueOf(productId), String.valueOf(batchNo));
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(digest, 0, 6);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** SPEC-CASE-07 R210: the overview numbers over open cases in the caller's dashboard scope. */
    public record Overview(Set<String> countryCodes, Set<Long> territoryIds, Instant asOf, java.util.Map<String, Long> openByStatus,
                           java.util.Map<String, Long> openByType, long atRisk, long breached, java.util.Map<Long, Long> workload,
                           long unassigned) { }

    public record Page<T>(List<T> items, String nextCursor) { }

    private final CaseAccess access;
    private final CaseRepository cases;
    private final ReferenceData ref;
    private final ShardRouter router;
    private final ArchiveCaseQueryPort archive;
    private final Clock clock;
    private final OverviewCache overviewCache;

    public CaseQueries(CaseAccess access, CaseRepository cases, ReferenceData ref, ShardRouter router,
                       ArchiveCaseQueryPort archive, Clock clock, OverviewCache overviewCache) {
        this.overviewCache = overviewCache;
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

    /** SPEC-CASE-09: what the caller may do next with a case, by the same checks the actions apply. */
    public record AllowedActions(List<ReferenceData.Edge> transitions, boolean canAssign) { }

    /**
     * SPEC-CASE-09 D1/D4 (R190–R193): the edges out of the case's status that {@code CaseCommands#transition} would
     * accept from this caller now ({@code case:transition} plus the edge's own permission; the case is already in the
     * caller's read scope), and whether {@code case:assign} is held. Advice for the UI: the actions still check (D2).
     */
    public AllowedActions allowedActions(CaseRecord record) {
        var principal = CurrentPrincipal.require();
        var now = clock.instant();
        var transitions = !principal.hasPermission("case:transition", now) ? List.<ReferenceData.Edge>of()
                : ref.transitionsFrom(record.caseType(), record.status()).stream()
                        .filter(e -> e.requiredPermission() == null || principal.hasPermission(e.requiredPermission(), now))
                        .toList();
        return new AllowedActions(transitions, principal.hasPermission("case:assign", now));
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
        if (q.batchNo() != null && q.productId() == null) {                                 // SPEC-CASE-07 R203
            throw ValidationException.of("batchNo", "needs productId");
        }
        long beforeId = q.cursor() == null ? Long.MAX_VALUE : decodeCursor(q.cursor(), q.fingerprint());
        long fromId = q.createdFrom() == null || q.createdFrom().isBefore(Instant.ofEpochMilli(IdCodec.EPOCH_MS))
                ? 0 : IdCodec.floor(q.createdFrom());
        if (q.queueCode() != null && !ref.queueExists(q.queueCode())) {                    // SPEC-CASE-04 R90
            throw ValidationException.of("queue", "unknown queue");
        }
        var filter = new CaseRepository.ListFilter(q.status(), q.caseType(), q.territoryId(),
                q.assignedToMe() ? principal.userId() : null, q.queueCode(),
                q.slaState() == null ? null : q.slaState().name(), q.growerId(), q.productId(), q.batchNo());

        var shards = targetShards(principal, now, "case:read");
        if (q.growerId() != null) {                                                       // SPEC-CASE-07 D2: one shard
            shards.retainAll(shards.stream().map(ShardId::countryCode).distinct()
                    .map(country -> router.forGrower(country, q.growerId())).collect(Collectors.toSet()));
        }
        var rows = fanOut(shards, shard ->
                cases.list(shard, principal, "case:read", filter, beforeId, fromId, q.limit() + 1));
        var sorted = rows.stream().sorted(Comparator.comparingLong(CaseRecord::caseId).reversed()).limit(q.limit() + 1L).toList();
        if (sorted.size() > q.limit()) {
            var page = sorted.subList(0, q.limit());
            return new Page<>(page, Cursor.encode(page.getLast().caseId()) + "." + q.fingerprint());
        }
        return new Page<>(sorted, null);
    }

    /** R204: {@code <cursor>.<fingerprint>}; a cursor issued for other filters is a 400 on {@code cursor}. */
    private static long decodeCursor(String cursor, String fingerprint) {
        int dot = cursor.lastIndexOf('.');
        if (dot < 0 || !cursor.substring(dot + 1).equals(fingerprint)) {
            throw ValidationException.of("cursor", "is not a valid cursor for these filters");
        }
        return Cursor.decode(cursor.substring(0, dot));
    }

    /**
     * SPEC-CASE-07 R210/R211 (DSH-2): counts over open cases in the shards and scope SQL the case list uses, but for the
     * caller's {@code dashboard:read} grants. A territory outside those grants (or unknown) is 404. Cached per scope (D5).
     */
    @RequiresPermission("dashboard:read")
    public Overview overview(Long territoryId) {
        var principal = CurrentPrincipal.require();
        var now = clock.instant();
        var grants = principal.grantsWith("dashboard:read", now).toList();
        var countries = new java.util.TreeSet<String>();
        var territories = new java.util.TreeSet<Long>();
        for (var grant : grants) {
            switch (grant.scopeKind()) {
                case ASSIGNED, TERRITORY, COUNTRY, REGION -> {
                    countries.addAll(grant.countries());
                    countries.addAll(ref.countriesOfTerritories(grant.territories()));
                    territories.addAll(grant.territories());
                }
                case ORG -> countries.add(principal.homeCountry());
                case OWN, GLOBAL -> { }
            }
        }
        if (territoryId != null) {
            var line = ref.territoryWithAncestors(territoryId);
            var country = line.isEmpty() ? Set.<String>of() : ref.countriesOfTerritories(Set.of(territoryId));
            boolean covered = grants.stream().anyMatch(g -> switch (g.scopeKind()) {
                case ASSIGNED, TERRITORY, COUNTRY, REGION -> !java.util.Collections.disjoint(g.territories(), line)
                        || !java.util.Collections.disjoint(g.countries(), country);
                case ORG -> country.contains(principal.homeCountry());
                case OWN, GLOBAL -> false;
            });
            if (line.isEmpty() || !covered) {
                throw new com.growercare.common.error.NotFoundException("territory");
            }
            countries.retainAll(country);
            territories.clear();
            territories.add(territoryId);
        }
        var key = principal.userId() + "|" + territoryId + "|" + grants.stream().map(String::valueOf).sorted().toList();
        return overviewCache.get(key, () -> computeOverview(principal, now, territoryId, countries, territories));
    }

    private Overview computeOverview(GcPrincipal principal, Instant now, Long territoryId, Set<String> countries, Set<Long> territories) {
        var rows = fanOut(targetShards(principal, now, "dashboard:read"),
                shard -> cases.overview(shard, principal, "dashboard:read", territoryId));
        var byStatus = new java.util.TreeMap<String, Long>();
        var byType = new java.util.TreeMap<String, Long>();
        var workload = new java.util.TreeMap<Long, Long>();
        long atRisk = 0;
        long breached = 0;
        long unassigned = 0;
        for (var row : rows) {
            byStatus.merge(row.status(), row.open(), Long::sum);
            byType.merge(row.caseType(), row.open(), Long::sum);
            if (row.assigneeUserId() == null) {
                unassigned += row.open();
            } else {
                workload.merge(row.assigneeUserId(), row.open(), Long::sum);
            }
            if ("AT_RISK".equals(row.slaState())) {
                atRisk += row.open();
            } else if ("BREACHED".equals(row.slaState())) {
                breached += row.open();
            }
        }
        return new Overview(Set.copyOf(countries), Set.copyOf(territories), now, byStatus, byType, atRisk, breached, workload, unassigned);
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
    Set<ShardId> targetShards(GcPrincipal principal, Instant now, String permission) {
        var shards = new LinkedHashSet<ShardId>();
        var countries = new HashSet<String>();
        for (EffectiveGrant grant : principal.grantsWith(permission, now).toList()) {
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
    private <T> List<T> fanOut(Set<ShardId> shards, java.util.function.Function<ShardId, List<T>> query) {
        if (shards.isEmpty()) {
            return List.of();
        }
        var permits = new Semaphore(FAN_OUT_PARALLELISM);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<T>>> futures = shards.stream()
                    .map(shard -> executor.submit(() -> {
                        permits.acquire();
                        try {
                            return query.apply(shard);
                        } finally {
                            permits.release();
                        }
                    }))
                    .collect(Collectors.toList());
            var all = new ArrayList<T>();
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
