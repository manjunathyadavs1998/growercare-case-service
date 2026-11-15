package com.growercare.casesvc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.casesvc.support.DevSeedPrincipals;
import com.growercare.security.GcPrincipal;
import com.growercare.security.UserType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-CASE-02 R20–R22, R26/R27, R30 and SPEC-CASE-03 R51/R56 at unit level. */
class CaseRulesTest {

    static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @ParameterizedTest(name = "CASE-R30 mask \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            Ramesh Patil                   | R***** P****
            Sunita Rao                     | S***** R**
            Lakshmi (assisted onboarding)  | L****** (******** o**********
            A                              | A
            ''                             | ''
            """)
    void growerNamesAreMaskedWordByWord(String name, String masked) {
        assertThat(CaseRules.maskName(name)).isEqualTo(masked);
    }

    static Stream<Arguments> channels() {
        return Stream.of(
                argumentSet("CASE-R20 grower raises in the app", DevSeedPrincipals.GROWER_IN1, "APP"),
                argumentSet("CASE-R21 central role raises a care call", DevSeedPrincipals.CARE_IN, "CARE_CALL"),
                argumentSet("CASE-R21 field role raises in the field", DevSeedPrincipals.TM_IN, "FIELD"),
                argumentSet("CASE-R21 agronomist raises in the field", DevSeedPrincipals.AGRO_IN, "FIELD"),
                argumentSet("CASE-R22 partner raises at the shop", DevSeedPrincipals.RETAILER_IN, "RETAILER"));
    }

    @ParameterizedTest
    @MethodSource("channels")
    void channelIsDerivedFromTheCaller(GcPrincipal principal, String channel) {
        assertThat(CaseRules.channelFor(principal, NOW)).isEqualTo(channel);
    }

    static final NewCaseCommand BASE = new NewCaseCommand(70001L, "PRODUCT_COMPLAINT", "Leaves yellowing",
            "Spots after spray", 80001L, 9001L, "AZX2604-117", null, "NORMAL", UUID.fromString("0196c1b2-7c3a-7cc0-8a1b-000000000001"));

    static Stream<Arguments> requestHashes() {
        return Stream.of(
                argumentSet("CASE-R26 identical request, same caller", BASE, 1L, true),
                argumentSet("CASE-R27 different title", BASE.withTitle("Other title"), 1L, false),
                argumentSet("CASE-R27 same body, different caller", BASE, 2L, false),
                argumentSet("CASE-R26 key itself is not part of the hash", BASE.withIdempotencyKey(UUID.randomUUID()), 1L, true));
    }

    @ParameterizedTest
    @MethodSource("requestHashes")
    void idempotencyHashDetectsChangedRequests(NewCaseCommand command, long raisedBy, boolean sameAsBase) {
        var base = HexFormat.of().formatHex(CaseRules.requestHash(BASE, 1L));
        var other = HexFormat.of().formatHex(CaseRules.requestHash(command, raisedBy));
        assertThat(other).hasSize(64);
        assertThat(other.equals(base)).isEqualTo(sameAsBase);
    }

    @ParameterizedTest(name = "CASE-R51 {0} needs {1}")
    @CsvSource(textBlock = """
            PUBLIC,   comment:post-public
            INTERNAL, comment:post-internal
            PARTNER,  comment:post-partner
            """)
    void eachVisibilityNeedsItsOwnPermission(CommentVisibility visibility, String permission) {
        assertThat(visibility.postPermission()).isEqualTo(permission);
    }

    static Stream<Arguments> readable() {
        return Stream.of(
                argumentSet("CASE-R56 growers read PUBLIC", UserType.GROWER, EnumSet.of(CommentVisibility.PUBLIC)),
                argumentSet("CASE-R56 partners read PUBLIC + PARTNER", UserType.PARTNER, EnumSet.of(CommentVisibility.PUBLIC, CommentVisibility.PARTNER)),
                argumentSet("CASE-R56 staff read everything", UserType.STAFF, EnumSet.allOf(CommentVisibility.class)));
    }

    @ParameterizedTest
    @MethodSource("readable")
    void readableVisibilitiesDependOnUserType(UserType type, Set<CommentVisibility> expected) {
        assertThat(CommentVisibility.readableBy(type)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "every visibility round-trips: {0}")
    @EnumSource(CommentVisibility.class)
    void visibilityNamesAreTheApiValues(CommentVisibility visibility) {
        assertThat(CommentVisibility.valueOf(visibility.name())).isSameAs(visibility);
    }
}
