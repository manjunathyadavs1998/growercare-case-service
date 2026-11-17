package com.growercare.casesvc.support;

import com.growercare.casesvc.application.port.GrowerDirectory.GrowerReplica;
import java.util.HexFormat;
import java.util.List;

/**
 * The growers of the dev seed by their grower-service ids (SPEC-CASE-06 D6 / SPEC-GRW-01 §2), plus K1, a test-only
 * ACTIVE grower in Karnataka (G3 there is still PENDING_CONSENT, and many rows need a Karnataka grower who can have
 * cases). K1 lives only in the test database ({@link CaseTestDatabase}).
 */
public final class SeedGrowers {

    private SeedGrowers() { }

    public static final long G1 = 110165911145454593L;   // Ramesh Patil, user 1 (grower.in1), IN Pune 1002, partner 501
    public static final long G2 = 110165911145445378L;   // Sunita Rao, user 2 (grower.in2), IN Nashik 1003, partner 502
    public static final long G3 = 110165911145455619L;   // Lakshmi, assisted, PENDING_CONSENT, IN Bengaluru Rural 1005
    public static final long G4 = 110165911142966276L;   // Joao Silva, user 3 (grower.br1), BR Ribeirão Preto 2002
    public static final long K1 = 9_100_001L;            // test-only: ACTIVE assisted grower, IN Bengaluru Rural 1005

    /** Farms after D6: 80011–80014 belong to G1, G2, G4, G3; 80001–80004 stay with the inactive 70001-style rows; 80005 (test-only) to K1. */
    public static final long FARM_G1 = 80011L;
    public static final long FARM_G2 = 80012L;
    public static final long FARM_G4 = 80013L;
    public static final long FARM_K1 = 80005L;

    public static String id(long growerId) {
        return Long.toString(growerId);
    }

    /** What grower-service's internal view returns for the seed growers (stub data for {@code GrowerDirectory}). */
    public static List<GrowerReplica> replicas() {
        var hex = HexFormat.of();
        return List.of(
                new GrowerReplica(G1, "IN", 1002L, 1L, "Ramesh Patil", hex.parseHex("9c1fd28b1659c0e76e881c2f67b3d46f4c6d0ec8b4ad63cf71a478e1f80fada5"), "3210", 501L, "ACTIVE", 0),
                new GrowerReplica(G2, "IN", 1003L, 2L, "Sunita Rao", hex.parseHex("0e51d8ac07189a1eebdb9c057b4a32593d272c75f624850d8dbe81f278eaed3c"), "7788", 502L, "ACTIVE", 0),
                new GrowerReplica(G3, "IN", 1005L, null, "Lakshmi (assisted onboarding)", hex.parseHex("6752a1f363c58ae8ada7f9809ed57a7d35a64e3241d07dfef7ea164939820d7b"), "5432", null, "PENDING_CONSENT", 0),
                new GrowerReplica(G4, "BR", 2002L, 3L, "Joao Silva", hex.parseHex("585e5ab1ed1b18e93ee25888f65e56422a0454288732edf2257e0528a70f2bb6"), "4455", null, "ACTIVE", 0));
    }
}
