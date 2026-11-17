-- =====================================================================================================
-- DEV/TEST ONLY · SPEC-CASE-06 D6 / R151: the grower replica uses grower-service's ids (its dev seed G1–G4,
-- SPEC-GRW-01 §2). The 70001-style rows stay for cases already raised on them locally, but become INACTIVE without a
-- user, so user ids stay unique and no new case can be raised for them. Farms move to the new growers.
-- HMACs are grower-service's (dev key); the phone itself is never stored (D8).
-- =====================================================================================================

UPDATE ref.grower SET user_id = NULL, status = 'INACTIVE', updated_at = now()
 WHERE grower_id IN (70001, 70002, 70003, 70004);

INSERT INTO ref.grower (grower_id, country_code, territory_id, user_id, display_name, phone_hmac, phone_last4,
                        preferred_partner_org_id, status, version) VALUES
    (110165911145454593, 'IN', 1002, 1,    'Ramesh Patil',                  decode('9c1fd28b1659c0e76e881c2f67b3d46f4c6d0ec8b4ad63cf71a478e1f80fada5', 'hex'), '3210', 501,  'ACTIVE',          0),
    (110165911145445378, 'IN', 1003, 2,    'Sunita Rao',                    decode('0e51d8ac07189a1eebdb9c057b4a32593d272c75f624850d8dbe81f278eaed3c', 'hex'), '7788', 502,  'ACTIVE',          0),
    (110165911145455619, 'IN', 1005, NULL, 'Lakshmi (assisted onboarding)', decode('6752a1f363c58ae8ada7f9809ed57a7d35a64e3241d07dfef7ea164939820d7b', 'hex'), '5432', NULL, 'PENDING_CONSENT', 0),
    (110165911142966276, 'BR', 2002, 3,    'Joao Silva',                    decode('585e5ab1ed1b18e93ee25888f65e56422a0454288732edf2257e0528a70f2bb6', 'hex'), '4455', NULL, 'ACTIVE',          0);

UPDATE ref.farm SET grower_id = 110165911145454593 WHERE farm_id = 80001;
UPDATE ref.farm SET grower_id = 110165911145445378 WHERE farm_id = 80002;
UPDATE ref.farm SET grower_id = 110165911142966276 WHERE farm_id = 80003;
UPDATE ref.farm SET grower_id = 110165911145455619 WHERE farm_id = 80004;
