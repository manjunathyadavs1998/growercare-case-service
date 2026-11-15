-- =====================================================================================================
-- case-service · common · V1 · Read-only replicas of masters owned by other services (DB-per-service).
-- Owners: geography/BU → control-plane · partner_org → identity-access · grower/farm → grower-service ·
--         product/batch/registration → catalog-registry. Kept in sync by events later; seeded for now.
-- The runtime role only reads these tables (enforced below).
-- Placeholders: ${app_role}
-- =====================================================================================================
GRANT USAGE ON SCHEMA ref TO ${app_role};

CREATE TABLE ref.region (
    region_code varchar(8)  PRIMARY KEY CHECK (region_code ~ '^[A-Z]{2,8}$'),
    name        varchar(80) NOT NULL
);

CREATE TABLE ref.country (
    country_code   char(2)     PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$'),
    region_code    varchar(8)  NOT NULL REFERENCES ref.region (region_code),
    name           varchar(80) NOT NULL,
    country_ix     smallint    NOT NULL UNIQUE CHECK (country_ix BETWEEN 0 AND 63),
    case_no_cc     smallint    NOT NULL UNIQUE CHECK (case_no_cc BETWEEN 10 AND 99),
    time_zone      varchar(64) NOT NULL,
    currency_code  char(3)     NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    default_locale varchar(16) NOT NULL,
    active         boolean     NOT NULL DEFAULT true
);

CREATE TABLE ref.territory (
    territory_id        bigint       PRIMARY KEY,
    country_code        char(2)      NOT NULL REFERENCES ref.country (country_code),
    parent_territory_id bigint,
    territory_code      varchar(32)  NOT NULL,
    name                varchar(120) NOT NULL,
    territory_level     varchar(16)  NOT NULL CHECK (territory_level IN ('STATE', 'DISTRICT', 'SALES_AREA')),
    CONSTRAINT ref_territory_code_uq UNIQUE (country_code, territory_code),
    CONSTRAINT ref_territory_id_country_uq UNIQUE (territory_id, country_code),
    CONSTRAINT ref_territory_parent_fk FOREIGN KEY (parent_territory_id, country_code)
        REFERENCES ref.territory (territory_id, country_code),
    CONSTRAINT ref_territory_not_own_parent CHECK (parent_territory_id IS DISTINCT FROM territory_id)
);
CREATE INDEX ref_territory_parent_ix ON ref.territory (parent_territory_id);

CREATE TABLE ref.business_unit (
    bu_code varchar(8)  PRIMARY KEY CHECK (bu_code ~ '^[A-Z]{2,8}$'),
    name    varchar(80) NOT NULL
);

CREATE TABLE ref.partner_org (
    partner_org_id        bigint       PRIMARY KEY,
    country_code          char(2)      NOT NULL REFERENCES ref.country (country_code),
    org_type              varchar(16)  NOT NULL CHECK (org_type IN ('DISTRIBUTOR', 'RETAILER')),
    parent_partner_org_id bigint       REFERENCES ref.partner_org (partner_org_id),
    legal_name            varchar(200) NOT NULL,
    status                varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ref_partner_id_country_uq UNIQUE (partner_org_id, country_code)
);

-- Grower profile replica: only what case handling needs. Phone = keyed HMAC + last 4 (search by exact phone, CASE-10).
CREATE TABLE ref.grower (
    grower_id                bigint       PRIMARY KEY,
    country_code             char(2)      NOT NULL REFERENCES ref.country (country_code),
    territory_id             bigint       NOT NULL,
    user_id                  bigint       UNIQUE,              -- IAM user_account.user_id; NULL for assisted growers
    display_name             varchar(120) NOT NULL,
    phone_hmac               bytea        CHECK (octet_length(phone_hmac) = 32),
    phone_last4              char(4)      CHECK (phone_last4 ~ '^[0-9]{4}$'),
    preferred_partner_org_id bigint,
    status                   varchar(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERASED')),
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ref_grower_id_country_uq UNIQUE (grower_id, country_code),
    CONSTRAINT ref_grower_territory_fk FOREIGN KEY (territory_id, country_code)
        REFERENCES ref.territory (territory_id, country_code),
    CONSTRAINT ref_grower_partner_fk FOREIGN KEY (preferred_partner_org_id, country_code)
        REFERENCES ref.partner_org (partner_org_id, country_code),
    CONSTRAINT ref_grower_phone_parts_together CHECK ((phone_hmac IS NULL) = (phone_last4 IS NULL))
);
CREATE INDEX ref_grower_phone_ix ON ref.grower (phone_hmac) WHERE phone_hmac IS NOT NULL;
CREATE INDEX ref_grower_territory_ix ON ref.grower (territory_id);

CREATE TABLE ref.farm (
    farm_id      bigint        PRIMARY KEY,
    grower_id    bigint        NOT NULL REFERENCES ref.grower (grower_id),
    name         varchar(120)  NOT NULL,
    area_ha      numeric(10,2) NOT NULL CHECK (area_ha > 0),
    latitude     numeric(9,6)  CHECK (latitude BETWEEN -90 AND 90),
    longitude    numeric(9,6)  CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ref_farm_id_grower_uq UNIQUE (farm_id, grower_id),
    CONSTRAINT ref_farm_point_complete CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX ref_farm_grower_ix ON ref.farm (grower_id);

CREATE TABLE ref.product (
    product_id   bigint       PRIMARY KEY,
    product_code varchar(32)  NOT NULL UNIQUE,
    bu_code      varchar(8)   NOT NULL REFERENCES ref.business_unit (bu_code),
    name         varchar(120) NOT NULL,
    product_kind varchar(24)  NOT NULL CHECK (product_kind IN ('FUNGICIDE', 'INSECTICIDE', 'HERBICIDE', 'SEED', 'BIOLOGICAL'))
);

CREATE TABLE ref.product_batch (
    product_id  bigint      NOT NULL REFERENCES ref.product (product_id),
    batch_no    varchar(32) NOT NULL CHECK (batch_no ~ '^[A-Z0-9-]{4,32}$'),
    mfg_date    date        NOT NULL,
    expiry_date date        NOT NULL,
    plant_code  varchar(16) NOT NULL,
    PRIMARY KEY (product_id, batch_no),
    CONSTRAINT ref_batch_dates CHECK (expiry_date > mfg_date)
);
CREATE INDEX ref_product_batch_no_ix ON ref.product_batch (batch_no);

-- Country registration/label status (GRW-3, minimal): a case can only reference a product registered in its country.
CREATE TABLE ref.product_registration (
    country_code    char(2)     NOT NULL REFERENCES ref.country (country_code),
    product_id      bigint      NOT NULL REFERENCES ref.product (product_id),
    registration_no varchar(40) NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('REGISTERED', 'SUSPENDED', 'WITHDRAWN')),
    valid_until     date,
    PRIMARY KEY (country_code, product_id)
);
