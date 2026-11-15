package com.growercare.casesvc.application.port;

import com.growercare.casesvc.domain.RoutingRules;
import com.growercare.shard.ShardId;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only reference data (schema {@code ref}: replicas + workflow definition). */
public interface ReferenceData {

    record Grower(long growerId, String countryCode, long territoryId, Long userId) { }

    record CaseType(String code, int defaultSlaHours, boolean active, String initialStatus) { }

    record Transition(String requiredPermission, boolean toClosed) { }

    record Partner(long partnerOrgId, String countryCode, String status) { }

    Optional<Grower> grower(long growerId);

    List<Grower> growersOfUser(long userId);

    boolean farmOfGrower(long farmId, long growerId);

    Optional<String> productBu(long productId);

    boolean productRegistered(String countryCode, long productId);

    boolean batchOfProduct(long productId, String batchNo);

    Optional<CaseType> caseType(String code);

    Optional<Transition> transition(String caseType, String fromStatus, String toStatus);

    Optional<Partner> partner(long partnerOrgId);

    Set<String> countriesOfTerritories(Set<Long> territoryIds);

    /** All shard schemas of a country (fan-out targets, decision D1). */
    List<ShardId> shardsOf(String countryCode);

    // SPEC-CASE-04 -----------------------------------------------------------------------------------

    /** R80: country SLA policy hours, if any. */
    Optional<Integer> slaHours(String countryCode, String caseType);

    /** R88: routing rules of active countries, by country (cached). */
    Map<String, List<RoutingRules.Rule>> routingRules();

    /** The territory itself and all its ancestors. */
    Set<Long> territoryWithAncestors(long territoryId);

    boolean queueExists(String queueCode);

    Set<String> activeCountries();

    /** Every shard of every active country. */
    List<ShardId> allShards();
}
