-- =====================================================================================================
-- case-service · common · V4 · SLA policy per country, case queues, routing rules (SPEC-CASE-04 D1, D5)
-- =====================================================================================================

-- R80: SLA hours per country × case type; absent row ⇒ ref.case_type.default_sla_hours.
-- Adverse-incident values are ILLUSTRATIVE — validate with Regulatory/Legal per country before go-live.
CREATE TABLE ref.case_sla_policy (
    country_code   char(2)     NOT NULL REFERENCES ref.country (country_code),
    case_type_code varchar(40) NOT NULL REFERENCES ref.case_type (case_type_code),
    sla_hours      integer     NOT NULL CHECK (sla_hours BETWEEN 1 AND 8760),
    PRIMARY KEY (country_code, case_type_code)
);

-- Work queues (a team inbox), owned by a country.
CREATE TABLE ref.case_queue (
    queue_code   varchar(44)  PRIMARY KEY CHECK (queue_code ~ '^[A-Z]{2}-[A-Z0-9-]{2,40}$'),
    country_code char(2)      NOT NULL REFERENCES ref.country (country_code),
    name         varchar(120) NOT NULL,
    CONSTRAINT case_queue_prefix_is_country CHECK (substr(queue_code, 1, 2) = country_code),
    CONSTRAINT case_queue_code_country_uq UNIQUE (queue_code, country_code)
);

-- R88: null columns are wildcards; the lowest priority number among matching rules wins.
CREATE TABLE ref.routing_rule (
    rule_id        bigint      PRIMARY KEY,
    country_code   char(2)     NOT NULL REFERENCES ref.country (country_code),
    priority       integer     NOT NULL CHECK (priority > 0),
    case_type_code varchar(40) REFERENCES ref.case_type (case_type_code),
    bu_code        varchar(8)  REFERENCES ref.business_unit (bu_code),
    territory_id   bigint,
    queue_code     varchar(44) NOT NULL,
    CONSTRAINT routing_rule_priority_uq UNIQUE (country_code, priority),
    CONSTRAINT routing_rule_queue_fk FOREIGN KEY (queue_code, country_code) REFERENCES ref.case_queue (queue_code, country_code),
    CONSTRAINT routing_rule_territory_fk FOREIGN KEY (territory_id, country_code) REFERENCES ref.territory (territory_id, country_code)
);

GRANT SELECT ON ref.case_sla_policy, ref.case_queue, ref.routing_rule TO ${app_role};
-- New ref tables get DML through default privileges: take writes away again (masters are read-only at runtime).
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ref.case_sla_policy, ref.case_queue, ref.routing_rule FROM ${app_role};

-- ------------------------------------------------------------------ seed
INSERT INTO ref.case_sla_policy (country_code, case_type_code, sla_hours) VALUES
    ('IN', 'ADVERSE_INCIDENT', 48),
    ('BR', 'ADVERSE_INCIDENT', 72);

INSERT INTO ref.case_queue (queue_code, country_code, name) VALUES
    ('IN-STEWARDSHIP',   'IN', 'India stewardship (adverse incidents)'),
    ('IN-SEEDS-QUALITY', 'IN', 'India seeds quality'),
    ('IN-MH-FIELD',      'IN', 'India Maharashtra field team'),
    ('IN-CP-COMPLAINTS', 'IN', 'India crop-protection complaints'),
    ('IN-CARE',          'IN', 'India care desk (fallback)'),
    ('BR-CARE',          'BR', 'Brazil care desk (fallback)');

INSERT INTO ref.routing_rule (rule_id, country_code, priority, case_type_code, bu_code, territory_id, queue_code) VALUES
    (1, 'IN', 10,  'ADVERSE_INCIDENT',  NULL,    NULL, 'IN-STEWARDSHIP'),
    (2, 'IN', 20,  'PRODUCT_COMPLAINT', 'SEEDS', NULL, 'IN-SEEDS-QUALITY'),
    (3, 'IN', 30,  NULL,                NULL,    1001, 'IN-MH-FIELD'),
    (4, 'IN', 40,  'PRODUCT_COMPLAINT', NULL,    NULL, 'IN-CP-COMPLAINTS'),
    (5, 'IN', 999, NULL,                NULL,    NULL, 'IN-CARE'),
    (6, 'BR', 999, NULL,                NULL,    NULL, 'BR-CARE');
