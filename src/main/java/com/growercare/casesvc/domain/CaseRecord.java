package com.growercare.casesvc.domain;

import com.growercare.security.ResourceScope;
import com.growercare.shard.CaseNumber;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** One case row with the joined grower facts needed for access checks and views. */
public record CaseRecord(
        long caseId,
        CaseNumber caseNo,
        String countryCode,
        String caseType,
        String status,
        long territoryId,
        String buCode,
        long growerId,
        Long growerUserId,
        String growerName,
        String growerPhoneLast4,
        Long farmId,
        long raisedByUserId,
        String raisedChannel,
        Long raisedViaPartnerOrgId,
        Long productId,
        String batchNo,
        String title,
        String description,
        String priority,
        Long assigneeUserId,
        Instant slaDueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        int version,
        Set<Long> activeShares) {

    public CaseRecord {
        activeShares = Set.copyOf(activeShares);
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /** Access attributes (SPEC-CASE-01): partners entitled = raised via + active shares. */
    public ResourceScope resourceScope() {
        var partners = new HashSet<>(activeShares);
        if (raisedViaPartnerOrgId != null) {
            partners.add(raisedViaPartnerOrgId);
        }
        return new ResourceScope(countryCode, territoryId, buCode, growerUserId, partners, assigneeUserId);
    }
}
