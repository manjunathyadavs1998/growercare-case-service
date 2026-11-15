package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.common.error.NotFoundException;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.shard.CaseNumber;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import org.springframework.stereotype.Component;

/**
 * Locates a case by id or public number and enforces object-level access (SPEC-CASE-01). Every "can't see it"
 * outcome — malformed id, unknown country, missing row, out of scope — is the same 404.
 */
@Component
public class CaseAccess {

    public record Located(ShardId shard, CaseRecord record) { }

    private final ShardRouter router;
    private final CaseRepository cases;
    private final PolicyEnforcer policy;

    public CaseAccess(ShardRouter router, CaseRepository cases, PolicyEnforcer policy) {
        this.router = router;
        this.cases = cases;
        this.policy = policy;
    }

    public Located byId(GcPrincipal principal, String permission, long caseId) {
        var shard = router.forId(caseId).orElseThrow(CaseAccess::notFound);
        var record = cases.findById(shard, caseId).orElseThrow(CaseAccess::notFound);
        policy.require(principal, permission, record.resourceScope(), "Case");
        return new Located(shard, record);
    }

    public Located byNumber(GcPrincipal principal, String permission, String caseNo) {
        var number = CaseNumber.parse(caseNo).orElseThrow(CaseAccess::notFound);
        var shard = router.forCaseNumber(number).orElseThrow(CaseAccess::notFound);
        var record = cases.findByCaseNo(shard, number.digits()).orElseThrow(CaseAccess::notFound);
        policy.require(principal, permission, record.resourceScope(), "Case");
        return new Located(shard, record);
    }

    static NotFoundException notFound() {
        return new NotFoundException("Case");
    }
}
