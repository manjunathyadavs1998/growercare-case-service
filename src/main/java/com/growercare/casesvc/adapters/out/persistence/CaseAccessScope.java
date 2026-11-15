package com.growercare.casesvc.adapters.out.persistence;

import com.growercare.security.GcPrincipal;
import com.growercare.security.ScopeFilter;
import com.growercare.shard.ShardId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the ABAC scope maps onto {@code <shard>.cases c} (skill: java-security-rbac):
 * <ul>
 *   <li>OWN — the case's grower is linked to the user ({@code ref.grower.user_id});</li>
 *   <li>ORG — raised via the partner org, or actively shared with it ({@code case_share}, ACC-11/12);</li>
 *   <li>ASSIGNED — {@code assignee_user_id}; geography — {@code country_code}/{@code territory_id}; BU — {@code bu_code}.</li>
 * </ul>
 * Queries must alias the cases table as {@code c}.
 */
public final class CaseAccessScope {

    private static final ScopeFilter.Columns TEMPLATE = new ScopeFilter.Columns(
            "c.country_code",
            "c.territory_id",
            "c.bu_code",
            "c.grower_id IN (SELECT g.grower_id FROM ref.grower g WHERE g.user_id = {param})",
            "c.assignee_user_id",
            "(c.raised_via_partner_org_id = {param} OR EXISTS (SELECT 1 FROM {shard}.case_share s"
                    + " WHERE s.case_id = c.case_id AND s.partner_org_id = {param} AND s.revoked_at IS NULL))");

    private static final Map<String, ScopeFilter.Columns> PER_SHARD = new ConcurrentHashMap<>();

    private CaseAccessScope() { }

    /** Column mapping for one shard (the share sub-query is shard-qualified; schema name validated by ShardId). */
    public static ScopeFilter.Columns columns(ShardId shard) {
        return PER_SHARD.computeIfAbsent(shard.schemaName(), schema -> new ScopeFilter.Columns(
                TEMPLATE.country(), TEMPLATE.territory(), TEMPLATE.bu(), TEMPLATE.ownedBy(), TEMPLATE.assignee(),
                TEMPLATE.partnerEntitled().replace("{shard}", schema)));
    }

    /** {@code AND}-able predicate restricting {@code c} to rows the principal may {@code permission}. */
    public static ScopeFilter.SqlPredicate predicate(ScopeFilter filter, GcPrincipal principal, String permission, ShardId shard) {
        return filter.forPermission(principal, permission, columns(shard));
    }
}
