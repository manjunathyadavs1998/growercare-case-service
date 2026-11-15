-- =====================================================================================================
-- case-service · common · V3 · Reference data: geography/BU replica, phase-1 workflows (BRD §6.3), shard map
-- Geography values must match identity-access-service V4 (same master, replicated).
-- =====================================================================================================

INSERT INTO ref.region (region_code, name) VALUES
    ('NA', 'North America'), ('LATAM', 'Latin America'), ('EAME', 'Europe, Africa, Middle East'), ('APAC', 'Asia Pacific');

INSERT INTO ref.country (country_code, region_code, name, country_ix, case_no_cc, time_zone, currency_code, default_locale, active) VALUES
    ('IN', 'APAC',  'India',  46, 71, 'Asia/Kolkata',      'INR', 'en-IN', true),
    ('BR', 'LATAM', 'Brazil',  8, 31, 'America/Sao_Paulo', 'BRL', 'pt-BR', true);

INSERT INTO ref.territory (territory_id, country_code, parent_territory_id, territory_code, name, territory_level) VALUES
    (1001, 'IN', NULL, 'MH',        'Maharashtra',     'STATE'),
    (1002, 'IN', 1001, 'MH-PUNE',   'Pune',            'DISTRICT'),
    (1003, 'IN', 1001, 'MH-NASHIK', 'Nashik',          'DISTRICT'),
    (1004, 'IN', NULL, 'KA',        'Karnataka',       'STATE'),
    (1005, 'IN', 1004, 'KA-BLR-R',  'Bengaluru Rural', 'DISTRICT'),
    (2001, 'BR', NULL, 'SP',        'São Paulo',       'STATE'),
    (2002, 'BR', 2001, 'SP-RP',     'Ribeirão Preto',  'DISTRICT'),
    (2003, 'BR', NULL, 'MT',        'Mato Grosso',     'STATE');

INSERT INTO ref.business_unit (bu_code, name) VALUES
    ('CP', 'Crop Protection'), ('SEEDS', 'Seeds'), ('BIO', 'Biologicals'), ('PROF', 'Professional Solutions');

-- ---------------------------------------------------------------- phase-1 case types (CASE-1: first 4)
INSERT INTO ref.case_type (case_type_code, name, default_sla_hours) VALUES
    ('AGRONOMY_ADVICE',        'Agronomy advice',        48),
    ('PRODUCT_COMPLAINT',      'Product complaint',      240),
    ('SEED_PERFORMANCE_CLAIM', 'Seed performance claim', 360),
    ('ADVERSE_INCIDENT',       'Adverse incident',       72);   -- country packs shorten this to the regulator deadline

INSERT INTO ref.case_status (case_type_code, status_code, is_initial, is_closed, display_order) VALUES
    ('AGRONOMY_ADVICE', 'SUBMITTED',    true,  false, 1),
    ('AGRONOMY_ADVICE', 'IN_PROGRESS',  false, false, 2),
    ('AGRONOMY_ADVICE', 'ADVICE_GIVEN', false, false, 3),
    ('AGRONOMY_ADVICE', 'CLOSED',       false, true,  4),

    ('PRODUCT_COMPLAINT', 'SUBMITTED',             true,  false, 1),
    ('PRODUCT_COMPLAINT', 'TRIAGED',               false, false, 2),
    ('PRODUCT_COMPLAINT', 'NEED_INFO',             false, false, 3),
    ('PRODUCT_COMPLAINT', 'RESOLVED_BY_ADVICE',    false, false, 4),
    ('PRODUCT_COMPLAINT', 'FIELD_INVESTIGATION',   false, false, 5),
    ('PRODUCT_COMPLAINT', 'SAMPLE_COLLECTED',      false, false, 6),
    ('PRODUCT_COMPLAINT', 'LAB_ANALYSIS',          false, false, 7),
    ('PRODUCT_COMPLAINT', 'ROOT_CAUSE_DETERMINED', false, false, 8),
    ('PRODUCT_COMPLAINT', 'COMPENSATION_PROPOSED', false, false, 9),
    ('PRODUCT_COMPLAINT', 'COMPENSATION_APPROVED', false, false, 10),
    ('PRODUCT_COMPLAINT', 'NO_COMPENSATION',       false, false, 11),
    ('PRODUCT_COMPLAINT', 'ESCALATED',             false, false, 12),
    ('PRODUCT_COMPLAINT', 'SETTLED_IN_ERP',        false, false, 13),
    ('PRODUCT_COMPLAINT', 'CLOSED',                false, true,  14),
    ('PRODUCT_COMPLAINT', 'REOPENED',              false, false, 15),

    ('SEED_PERFORMANCE_CLAIM', 'SUBMITTED',             true,  false, 1),
    ('SEED_PERFORMANCE_CLAIM', 'TRIAGED',               false, false, 2),
    ('SEED_PERFORMANCE_CLAIM', 'FIELD_INVESTIGATION',   false, false, 3),
    ('SEED_PERFORMANCE_CLAIM', 'ROOT_CAUSE_DETERMINED', false, false, 4),
    ('SEED_PERFORMANCE_CLAIM', 'COMPENSATION_PROPOSED', false, false, 5),
    ('SEED_PERFORMANCE_CLAIM', 'COMPENSATION_APPROVED', false, false, 6),
    ('SEED_PERFORMANCE_CLAIM', 'NO_COMPENSATION',       false, false, 7),
    ('SEED_PERFORMANCE_CLAIM', 'SETTLED_IN_ERP',        false, false, 8),
    ('SEED_PERFORMANCE_CLAIM', 'CLOSED',                false, true,  9),

    ('ADVERSE_INCIDENT', 'REPORTED',              true,  false, 1),
    ('ADVERSE_INCIDENT', 'CLASSIFIED',            false, false, 2),
    ('ADVERSE_INCIDENT', 'INVESTIGATING',         false, false, 3),
    ('ADVERSE_INCIDENT', 'REGULATORY_ASSESSMENT', false, false, 4),
    ('ADVERSE_INCIDENT', 'REPORT_FILED',          false, false, 5),
    ('ADVERSE_INCIDENT', 'FOLLOW_UP',             false, false, 6),
    ('ADVERSE_INCIDENT', 'CLOSED',                false, true,  7);

INSERT INTO ref.case_transition (case_type_code, from_status, to_status, required_permission) VALUES
    ('AGRONOMY_ADVICE', 'SUBMITTED',    'IN_PROGRESS',  'case:transition'),
    ('AGRONOMY_ADVICE', 'IN_PROGRESS',  'ADVICE_GIVEN', 'case:transition'),
    ('AGRONOMY_ADVICE', 'ADVICE_GIVEN', 'IN_PROGRESS',  'case:transition'),
    ('AGRONOMY_ADVICE', 'ADVICE_GIVEN', 'CLOSED',       'case:transition'),

    ('PRODUCT_COMPLAINT', 'SUBMITTED',             'TRIAGED',               'case:transition'),
    ('PRODUCT_COMPLAINT', 'TRIAGED',               'NEED_INFO',             'case:transition'),
    ('PRODUCT_COMPLAINT', 'NEED_INFO',             'TRIAGED',               'case:transition'),
    ('PRODUCT_COMPLAINT', 'TRIAGED',               'RESOLVED_BY_ADVICE',    'case:transition'),
    ('PRODUCT_COMPLAINT', 'TRIAGED',               'FIELD_INVESTIGATION',   'case:transition'),
    ('PRODUCT_COMPLAINT', 'FIELD_INVESTIGATION',   'SAMPLE_COLLECTED',      'case:transition'),
    ('PRODUCT_COMPLAINT', 'FIELD_INVESTIGATION',   'ROOT_CAUSE_DETERMINED', 'case:transition'),
    ('PRODUCT_COMPLAINT', 'SAMPLE_COLLECTED',      'LAB_ANALYSIS',          'case:transition'),
    ('PRODUCT_COMPLAINT', 'LAB_ANALYSIS',          'ROOT_CAUSE_DETERMINED', 'case:transition'),
    ('PRODUCT_COMPLAINT', 'ROOT_CAUSE_DETERMINED', 'COMPENSATION_PROPOSED', 'compensation:propose'),
    ('PRODUCT_COMPLAINT', 'ROOT_CAUSE_DETERMINED', 'NO_COMPENSATION',       'case:transition'),
    ('PRODUCT_COMPLAINT', 'ROOT_CAUSE_DETERMINED', 'ESCALATED',             'case:transition'),
    ('PRODUCT_COMPLAINT', 'COMPENSATION_PROPOSED', 'COMPENSATION_APPROVED', 'compensation:approve'),
    ('PRODUCT_COMPLAINT', 'COMPENSATION_APPROVED', 'SETTLED_IN_ERP',        'case:transition'),
    ('PRODUCT_COMPLAINT', 'RESOLVED_BY_ADVICE',    'CLOSED',                'case:transition'),
    ('PRODUCT_COMPLAINT', 'NO_COMPENSATION',       'CLOSED',                'case:transition'),
    ('PRODUCT_COMPLAINT', 'ESCALATED',             'CLOSED',                'case:transition'),
    ('PRODUCT_COMPLAINT', 'SETTLED_IN_ERP',        'CLOSED',                'case:transition'),
    ('PRODUCT_COMPLAINT', 'CLOSED',                'REOPENED',              'case:transition'),   -- 30-day window: compliance pack rule
    ('PRODUCT_COMPLAINT', 'REOPENED',              'TRIAGED',               'case:transition'),

    ('SEED_PERFORMANCE_CLAIM', 'SUBMITTED',             'TRIAGED',               'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'TRIAGED',               'FIELD_INVESTIGATION',   'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'FIELD_INVESTIGATION',   'ROOT_CAUSE_DETERMINED', 'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'ROOT_CAUSE_DETERMINED', 'COMPENSATION_PROPOSED', 'compensation:propose'),
    ('SEED_PERFORMANCE_CLAIM', 'ROOT_CAUSE_DETERMINED', 'NO_COMPENSATION',       'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'COMPENSATION_PROPOSED', 'COMPENSATION_APPROVED', 'compensation:approve'),
    ('SEED_PERFORMANCE_CLAIM', 'COMPENSATION_APPROVED', 'SETTLED_IN_ERP',        'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'SETTLED_IN_ERP',        'CLOSED',                'case:transition'),
    ('SEED_PERFORMANCE_CLAIM', 'NO_COMPENSATION',       'CLOSED',                'case:transition'),

    ('ADVERSE_INCIDENT', 'REPORTED',              'CLASSIFIED',            'case:transition'),
    ('ADVERSE_INCIDENT', 'CLASSIFIED',            'INVESTIGATING',         'case:transition'),
    ('ADVERSE_INCIDENT', 'INVESTIGATING',         'REGULATORY_ASSESSMENT', 'case:transition'),
    ('ADVERSE_INCIDENT', 'REGULATORY_ASSESSMENT', 'REPORT_FILED',          'case:transition'),
    ('ADVERSE_INCIDENT', 'REGULATORY_ASSESSMENT', 'CLOSED',                'case:transition'),   -- not reportable
    ('ADVERSE_INCIDENT', 'REPORT_FILED',          'FOLLOW_UP',             'case:transition'),
    ('ADVERSE_INCIDENT', 'FOLLOW_UP',             'CLOSED',                'case:transition');

-- ---------------------------------------------------------------- shard map: 64 logical shards per active country
INSERT INTO ref.shard (country_code, shard_no, schema_name)
SELECT c.country_code, s.n, lower(c.country_code) || '_s' || lpad(s.n::text, 2, '0')
  FROM ref.country c
  CROSS JOIN generate_series(0, 63) AS s (n)
 WHERE c.active;
