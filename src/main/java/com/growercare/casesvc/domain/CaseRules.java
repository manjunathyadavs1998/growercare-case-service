package com.growercare.casesvc.domain;

import com.growercare.security.GcPrincipal;
import com.growercare.security.ScopeKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pure case rules (no I/O), unit-tested by {@code CaseRulesTest}. */
public final class CaseRules {

    private CaseRules() { }

    /** CASE-R30: keep the first character of each word, star the rest ("Ramesh Patil" → "R***** P****"). */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Arrays.stream(name.split(" ", -1))
                .map(word -> word.isEmpty() ? word : word.charAt(0) + "*".repeat(word.length() - 1))
                .collect(Collectors.joining(" "));
    }

    /**
     * CASE-R20–R22: how the case reached us, derived from who raises it.
     * Growers → APP; partners → RETAILER; staff with a field-scoped create grant (ASSIGNED/TERRITORY) → FIELD;
     * other staff → CARE_CALL.
     */
    public static String channelFor(GcPrincipal principal, Instant now) {
        return switch (principal.userType()) {
            case GROWER -> "APP";
            case PARTNER -> "RETAILER";
            case STAFF -> principal.grantsWith("case:create", now)
                    .anyMatch(g -> g.scopeKind() == ScopeKind.ASSIGNED || g.scopeKind() == ScopeKind.TERRITORY)
                    ? "FIELD" : "CARE_CALL";
        };
    }

    /**
     * CASE-R26/R27: SHA-256 of the canonical request (every business field + the caller), excluding the key itself.
     * Same key + same hash ⇒ replay; same key + different hash ⇒ 409.
     */
    public static byte[] requestHash(NewCaseCommand c, long raisedByUserId) {
        var canonical = Stream.of(
                        "raisedBy=" + raisedByUserId, "grower=" + c.growerId(), "type=" + c.caseType(), "title=" + c.title(),
                        "description=" + c.description(), "farm=" + c.farmId(), "product=" + c.productId(), "batch=" + c.batchNo(),
                        "bu=" + c.buCode(), "priority=" + c.priority())
                .map(Objects::toString)
                .collect(Collectors.joining("\u001F"));      // unit separator: cannot be confused with field content
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
