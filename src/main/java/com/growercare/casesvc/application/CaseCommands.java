package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.AssigneeDirectory;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.CaseRepository.NewCaseRow;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.application.port.SlaTimerRepository;
import com.growercare.casesvc.domain.RoutingRules;
import com.growercare.casesvc.domain.SlaRules;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.CaseRules;
import com.growercare.casesvc.domain.NewCaseCommand;
import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.PermissionDeniedException;
import com.growercare.common.error.StaleVersionException;
import com.growercare.common.error.ValidationException;
import com.growercare.common.web.TraceIds;
import com.growercare.outbox.OutboxWriter;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.RequiresPermission;
import com.growercare.security.ResourceScope;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Case write use cases: create (SPEC-CASE-02 R20–R27), transition (R34–R37), assign (R38/R39). */
@Service
public class CaseCommands {

    /** Outcome of {@link #create}: a new case, or the original on an idempotent replay (R26). */
    public record CreateResult(CaseRecord record, boolean replayed) { }

    private final ReferenceData ref;
    private final CaseRepository cases;
    private final CaseAccess access;
    private final AssigneeDirectory assignees;
    private final ShardRouter router;
    private final PolicyEnforcer policy;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SlaTimerRepository timers;

    public CaseCommands(ReferenceData ref, CaseRepository cases, CaseAccess access, AssigneeDirectory assignees, ShardRouter router,
                        PolicyEnforcer policy, OutboxWriter outbox, TransactionTemplate tx, Clock clock, SlaTimerRepository timers) {
        this.ref = ref;
        this.cases = cases;
        this.access = access;
        this.assignees = assignees;
        this.router = router;
        this.policy = policy;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.timers = timers;
    }

    // ============================================================================================ create

    @RequiresPermission("case:create")
    public CreateResult create(NewCaseCommand cmd) {
        var principal = CurrentPrincipal.require();
        var now = clock.instant();

        // R24 — request shape that needs reference data
        var type = ref.caseType(cmd.caseType()).orElseThrow(() -> ValidationException.of("caseType", "unknown case type"));
        if (cmd.productId() == null && cmd.buCode() == null) {
            throw ValidationException.of("buCode", "is required when no product is given");
        }
        if (cmd.batchNo() != null && cmd.productId() == null) {
            throw ValidationException.of("batchNo", "requires productId");
        }
        if (!type.active()) {
            throw new BusinessRuleException("case.type-inactive", "Case type " + type.code() + " is not active");
        }

        // R23 — the grower must be one the caller may raise cases for (404 otherwise, no existence leak)
        var grower = ref.grower(cmd.growerId()).orElseThrow(() -> new NotFoundException("Grower"));
        var bu = cmd.productId() == null ? cmd.buCode() : ref.productBu(cmd.productId()).orElse(cmd.buCode());
        if (!policy.permits(principal, "case:create", creationScope(principal, grower, bu))) {
            throw new NotFoundException("Grower");
        }

        // R25 — business validation against reference data
        if (cmd.productId() != null && !ref.productRegistered(grower.countryCode(), cmd.productId())) {
            throw new BusinessRuleException("case.product-not-registered", "The product is not registered in " + grower.countryCode());
        }
        if (cmd.batchNo() != null && !ref.batchOfProduct(cmd.productId(), cmd.batchNo())) {
            throw new BusinessRuleException("case.batch-unknown", "The batch does not belong to the product");
        }
        if (cmd.farmId() != null && !ref.farmOfGrower(cmd.farmId(), cmd.growerId())) {
            throw new BusinessRuleException("case.farm-not-of-grower", "The farm does not belong to the grower");
        }

        var shard = router.forGrower(grower.countryCode(), grower.growerId());
        var hash = CaseRules.requestHash(cmd, principal.userId());

        // R26/R27 — idempotency (fast path; the insert below closes the race)
        var previous = cases.findIdempotency(shard, cmd.idempotencyKey());
        if (previous.isPresent()) {
            return replay(shard, previous.get(), hash);
        }
        // SPEC-CASE-04 R80/R88: SLA hours per country × type; queue by the most specific routing rule
        int slaHours = SlaRules.slaHours(ref.slaHours(grower.countryCode(), type.code()), type.defaultSlaHours());
        var slaDue = now.plus(Duration.ofHours(slaHours));
        var queue = RoutingRules.route(ref.routingRules().getOrDefault(grower.countryCode(), List.of()), type.code(), bu,
                        ref.territoryWithAncestors(grower.territoryId()))
                .orElseThrow(() -> new IllegalStateException("No routing rule (not even a fallback) for " + grower.countryCode()));
        var row = new NewCaseRow(type.code(), type.initialStatus(), grower.territoryId(), bu, grower.growerId(), cmd.farmId(),
                principal.userId(), CaseRules.channelFor(principal, now),
                principal.userType() == UserType.PARTNER ? principal.partnerOrgId() : null,
                cmd.productId(), cmd.batchNo(), cmd.title().strip(), cmd.description().strip(), cmd.priority(),
                slaDue, now, queue);
        try {
            Long caseId = tx.execute(status -> {
                long id = cases.insert(shard, row);
                if (!cases.insertIdempotency(shard, cmd.idempotencyKey(), principal.userId(), hash, id)) {
                    status.setRollbackOnly();          // another request with the same key won the race
                    return null;
                }
                cases.insertActivity(shard, id, "CREATED", principal.userId(), null, row.status(), null);
                timers.insertTimers(shard, id, SlaRules.timers(now, slaDue));                                 // R81
                long routed = cases.insertActivity(shard, id, "ROUTED", principal.userId(), null, null, null); // R88
                cases.insertActivityDetail(shard, id, routed, "queue_code", null, queue);
                // SPEC-PLAT-04 E72: created before routed (same key ⇒ consumers see them in this order)
                outbox.append(outboxOf(shard), new OutboxWriter.Event("case", Long.toString(id), "case.created", Map.of(
                        "caseId", Long.toString(id), "caseType", row.caseType(), "status", row.status(),
                        "countryCode", grower.countryCode(), "territoryId", Long.toString(row.territoryId()),
                        "buCode", row.buCode(), "growerId", Long.toString(row.growerId()), "queueCode", queue)), TraceIds.currentOrNull());
                outbox.append(outboxOf(shard), new OutboxWriter.Event("case", Long.toString(id), "case.routed",
                        Map.of("caseId", Long.toString(id), "queueCode", queue)), TraceIds.currentOrNull());
                return id;
            });
            if (caseId == null) {
                return replay(shard, cases.findIdempotency(shard, cmd.idempotencyKey()).orElseThrow(), hash);
            }
            return new CreateResult(cases.findById(shard, caseId).orElseThrow(), false);
        } catch (DataIntegrityViolationException e) {
            // e.g. an unknown buCode (FK): a reference the client supplied does not exist
            throw new BusinessRuleException("case.reference-invalid", "A referenced code does not exist", e);
        }
    }

    private CreateResult replay(ShardId shard, CaseRepository.IdempotencyRecord previous, byte[] hash) {
        if (!Arrays.equals(previous.requestHash(), hash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used for a different request");
        }
        return new CreateResult(cases.findById(shard, previous.caseId()).orElseThrow(), true);
    }

    /** Scope of a case that would be created: partners count as "raised via" when in the grower's country. */
    private static ResourceScope creationScope(GcPrincipal principal, ReferenceData.Grower grower, String bu) {
        var partners = principal.userType() == UserType.PARTNER && grower.countryCode().equals(principal.homeCountry())
                ? Set.of(principal.partnerOrgId()) : Set.<Long>of();
        return new ResourceScope(grower.countryCode(), grower.territoryId(), bu, grower.userId(), partners, principal.userId());
    }

    // ============================================================================================ transition

    @RequiresPermission("case:transition")
    public CaseRecord transition(long caseId, int expectedVersion, String toStatus, String note) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:read", caseId);
        var current = located.record();
        var edge = ref.transition(current.caseType(), current.status(), toStatus)
                .orElseThrow(() -> new BusinessRuleException("case.transition-not-allowed",
                        "A " + current.caseType() + " case cannot move from " + current.status() + " to " + toStatus));
        if (!principal.hasPermission(edge.requiredPermission(), clock.instant())) {
            throw new PermissionDeniedException(edge.requiredPermission());
        }
        var shard = located.shard();
        tx.executeWithoutResult(status -> {
            int version = cases.updateStatus(shard, caseId, expectedVersion, toStatus, edge.toClosed())
                    .orElseThrow(() -> new StaleVersionException("Case", expectedVersion));
            cases.insertActivity(shard, caseId, "STATUS_CHANGED", principal.userId(), current.status(), toStatus, blankToNull(note));
            if (edge.toClosed()) {
                timers.cancelTimers(shard, caseId);                                                         // R84
            } else if (current.isClosed()) {
                rearmSla(shard, current);                                                                   // R85
            }
            outbox.append(outboxOf(shard), new OutboxWriter.Event("case", Long.toString(caseId), "case.status_changed", Map.of(
                    "caseId", Long.toString(caseId), "from", current.status(), "to", toStatus, "version", version)), TraceIds.currentOrNull());
        });
        return cases.findById(shard, caseId).orElseThrow();
    }

    // ============================================================================================ assign

    @RequiresPermission("case:assign")
    public CaseRecord assign(long caseId, int expectedVersion, long assigneeUserId) {
        var principal = CurrentPrincipal.require();
        var located = access.byId(principal, "case:read", caseId);
        var current = located.record();
        var scope = current.resourceScope();
        var asAssigned = new ResourceScope(scope.countryCode(), scope.territoryId(), scope.buCode(), scope.ownerUserId(),
                scope.partnerOrgIds(), assigneeUserId);
        assignees.findByUserId(assigneeUserId)
                .filter(candidate -> candidate.userType() == UserType.STAFF)
                .filter(candidate -> policy.permits(candidate, "case:read", asAssigned))
                .orElseThrow(() -> new BusinessRuleException("case.assignee-not-eligible",
                        "The assignee must be staff who can work on this case"));
        var shard = located.shard();
        tx.executeWithoutResult(status -> {
            int version = cases.updateAssignee(shard, caseId, expectedVersion, assigneeUserId)
                    .orElseThrow(() -> new StaleVersionException("Case", expectedVersion));
            long activityId = cases.insertActivity(shard, caseId, "ASSIGNED", principal.userId(), null, null, null);
            cases.insertActivityDetail(shard, caseId, activityId, "assignee_user_id",
                    current.assigneeUserId() == null ? null : current.assigneeUserId().toString(), Long.toString(assigneeUserId));
            var payload = new HashMap<String, Object>();
            payload.put("caseId", Long.toString(caseId));
            payload.put("assigneeUserId", Long.toString(assigneeUserId));
            payload.put("version", version);
            outbox.append(outboxOf(shard), new OutboxWriter.Event("case", Long.toString(caseId), "case.assigned", payload), TraceIds.currentOrNull());
        });
        return cases.findById(shard, caseId).orElseThrow();
    }

    /** R85: a reopened case gets a fresh SLA window and a fresh set of timers. */
    private void rearmSla(ShardId shard, CaseRecord current) {
        var now = clock.instant();
        int defaultHours = ref.caseType(current.caseType()).orElseThrow().defaultSlaHours();
        var due = now.plus(Duration.ofHours(SlaRules.slaHours(ref.slaHours(current.countryCode(), current.caseType()), defaultHours)));
        timers.rearmSla(shard, current.caseId(), now, due);
        timers.insertTimers(shard, current.caseId(), SlaRules.timers(now, due));
    }

    static String outboxOf(ShardId shard) {
        return shard.schemaName() + ".outbox";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
