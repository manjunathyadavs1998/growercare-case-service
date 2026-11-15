-- =====================================================================================================
-- DEV/TEST ONLY (gc.dev-seed=true). Replica rows that other services would normally publish.
-- Partner orgs and user IDs match identity-access-service dev seed and the Keycloak realm.
-- Product names are fictitious.
-- =====================================================================================================

INSERT INTO ref.partner_org (partner_org_id, country_code, org_type, parent_partner_org_id, legal_name, status) VALUES
    (503, 'IN', 'DISTRIBUTOR', NULL, 'Deccan Agri Distributors Pvt Ltd', 'ACTIVE'),
    (501, 'IN', 'RETAILER',    503,  'Patil Agro Store',                 'ACTIVE'),
    (502, 'IN', 'RETAILER',    NULL, 'Krishi Kendra Nashik',             'ACTIVE');

INSERT INTO ref.grower (grower_id, country_code, territory_id, user_id, display_name, phone_last4, phone_hmac, preferred_partner_org_id) VALUES
    (70001, 'IN', 1002, 1,    'Ramesh Patil', '3210', sha256('dev-phone-70001'::bytea), 501),
    (70002, 'IN', 1003, 2,    'Sunita Rao',   '7788', sha256('dev-phone-70002'::bytea), 502),
    (70003, 'BR', 2002, 3,    'Joao Silva',   '4455', sha256('dev-phone-70003'::bytea), NULL),
    (70004, 'IN', 1005, NULL, 'Lakshmi (assisted onboarding)', NULL, NULL, NULL);

INSERT INTO ref.farm (farm_id, grower_id, name, area_ha, latitude, longitude) VALUES
    (80001, 70001, 'Patil grape block A', 2.50, 18.520430, 73.856743),
    (80002, 70002, 'Rao onion field',     1.20, 19.997454, 73.789803),
    (80003, 70003, 'Fazenda Silva soy',  120.00, -21.177600, -47.810300),
    (80004, 70004, 'Lakshmi ragi plot',   0.80, 13.284500, 77.607800);

INSERT INTO ref.product (product_id, product_code, bu_code, name, product_kind) VALUES
    (9001, 'AZX-325SC', 'CP',    'Azoxa Duo 325 SC',      'FUNGICIDE'),
    (9002, 'LMB-50CS',  'CP',    'Lambdor 5 CS',          'INSECTICIDE'),
    (9003, 'HYC-6668',  'SEEDS', 'Hybrid corn HC-6668',   'SEED');

INSERT INTO ref.product_batch (product_id, batch_no, mfg_date, expiry_date, plant_code) VALUES
    (9001, 'AZX2604-117', DATE '2026-04-10', DATE '2028-04-09', 'IN-GOA'),
    (9002, 'LMB2602-031', DATE '2026-02-02', DATE '2028-02-01', 'IN-GOA'),
    (9003, 'HYC2605-009', DATE '2026-05-20', DATE '2027-05-19', 'IN-HYD');

INSERT INTO ref.product_registration (country_code, product_id, registration_no, status, valid_until) VALUES
    ('IN', 9001, 'CIR-123456/2024', 'REGISTERED', DATE '2029-12-31'),
    ('IN', 9002, 'CIR-223344/2023', 'REGISTERED', DATE '2028-12-31'),
    ('IN', 9003, 'SEED-IN-6668',    'REGISTERED', NULL),
    ('BR', 9001, 'MAPA-10120',      'REGISTERED', DATE '2030-06-30');
