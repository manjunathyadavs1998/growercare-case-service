package com.growercare.casesvc.support;

import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PiiClearance;
import com.growercare.security.ScopeKind;
import com.growercare.security.UserType;
import com.growercare.testsupport.TestJwts;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The dev-seed users exactly as identity-access-service resolves them (IAM V4 role permissions + V1000 grants),
 * so case-service tests exercise realistic principals without calling IAM. Kept in sync by
 * {@code IamSecurityIT} (IAM side) and the role/permission seed.
 */
public final class DevSeedPrincipals {

    private DevSeedPrincipals() { }

    private static final Set<String> GROWER_PERMS = Set.of("case:create", "case:read", "case:share", "case:attach", "comment:read", "comment:post-public");
    private static final Set<String> RETAILER_PERMS = Set.of("case:create", "case:read", "case:attach", "comment:read", "comment:post-partner");
    private static final Set<String> CARE_PERMS = Set.of("case:create", "case:read", "case:assign", "case:transition", "case:attach", "comment:read",
            "comment:post-public", "comment:post-internal", "pii:unmask");
    private static final Set<String> AGRO_PERMS = Set.of("case:create", "case:read", "case:transition", "case:attach", "comment:read",
            "comment:post-public", "comment:post-internal");
    private static final Set<String> TM_PERMS = Set.of("case:create", "case:read", "case:assign", "case:transition", "case:attach", "comment:read",
            "comment:post-public", "comment:post-internal", "dashboard:read");

    public static final GcPrincipal GROWER_IN1 = grower(1, TestJwts.GROWER_IN1, "IN");
    public static final GcPrincipal GROWER_IN2 = grower(2, TestJwts.GROWER_IN2, "IN");
    public static final GcPrincipal GROWER_BR1 = grower(3, TestJwts.GROWER_BR1, "BR");
    public static final GcPrincipal CARE_IN = staff(101, TestJwts.CARE_IN, "IN",
            grant("CARE_AGENT", 30, ScopeKind.COUNTRY, PiiClearance.MASKED, CARE_PERMS, Set.of("IN"), Set.of(), Set.of()));
    public static final GcPrincipal AGRO_IN = staff(102, TestJwts.AGRO_IN, "IN",
            grant("FIELD_AGRONOMIST", 30, ScopeKind.ASSIGNED, PiiClearance.FULL, AGRO_PERMS, Set.of(), Set.of(1002L), Set.of("CP")));
    public static final GcPrincipal TM_IN = staff(103, TestJwts.TM_IN, "IN",
            grant("TERRITORY_MANAGER", 40, ScopeKind.TERRITORY, PiiClearance.FULL, TM_PERMS, Set.of(), Set.of(1001L, 1002L, 1003L), Set.of()));
    public static final GcPrincipal CARE_BR = staff(105, TestJwts.CARE_BR, "BR",
            grant("CARE_AGENT", 30, ScopeKind.COUNTRY, PiiClearance.MASKED, CARE_PERMS, Set.of("BR"), Set.of(), Set.of()));
    public static final GcPrincipal RETAILER_IN = partner(201, TestJwts.RETAILER_IN, 501L);
    public static final GcPrincipal RETAILER2_IN = partner(202, TestJwts.RETAILER2_IN, 502L);

    /**
     * Test-only (not in the dev seed): an AUDITOR in India, with case:read and comment:read but no case:attach.
     * Used for "missing permission" rows (SPEC-CASE-05 E105).
     */
    public static final GcPrincipal AUDITOR_TEST = staff(901, java.util.UUID.fromString("6f1c0a10-0901-4a00-8000-000000000901"), "IN",
            grant("AUDITOR", 50, ScopeKind.COUNTRY, PiiClearance.MASKED, Set.of("case:read", "comment:read"), Set.of("IN"), Set.of(), Set.of()));

    /** Every principal the stubbed IAM resolves: the dev seed plus test-only users. */
    public static List<GcPrincipal> resolvable() {
        return java.util.stream.Stream.concat(all().stream(), java.util.stream.Stream.of(AUDITOR_TEST)).toList();
    }

    public static List<GcPrincipal> all() {
        return List.of(GROWER_IN1, GROWER_IN2, GROWER_BR1, CARE_IN, AGRO_IN, TM_IN, CARE_BR, RETAILER_IN, RETAILER2_IN);
    }

    private static EffectiveGrant grant(String role, int level, ScopeKind kind, PiiClearance pii, Set<String> perms,
                                        Set<String> countries, Set<Long> territories, Set<String> bus) {
        return new EffectiveGrant(level * 1000L, role, level, kind, pii, perms, countries, territories, bus, null);
    }

    private static GcPrincipal grower(long userId, UUID subject, String country) {
        return new GcPrincipal(userId, subject, UserType.GROWER, country, null, List.of(
                grant("GROWER", 10, ScopeKind.OWN, PiiClearance.FULL, GROWER_PERMS, Set.of(country), Set.of(), Set.of())));
    }

    private static GcPrincipal staff(long userId, UUID subject, String country, EffectiveGrant grant) {
        return new GcPrincipal(userId, subject, UserType.STAFF, country, null, List.of(grant));
    }

    private static GcPrincipal partner(long userId, UUID subject, long org) {
        return new GcPrincipal(userId, subject, UserType.PARTNER, "IN", org, List.of(
                grant("RETAILER_USER", 20, ScopeKind.ORG, PiiClearance.MASKED, RETAILER_PERMS, Set.of("IN"), Set.of(), Set.of())));
    }
}
