-- =====================================================================================================
-- case-service · common · V6 · allowed attachment types and limits (SPEC-CASE-05 D7)
-- Reference data, so a country profile can narrow it later (convention 4) without code changes.
-- =====================================================================================================

CREATE TABLE ref.attachment_type_rule (
    kind         varchar(16)  NOT NULL CHECK (kind IN ('PHOTO', 'VIDEO', 'PACK_PHOTO', 'INVOICE', 'PRESCRIPTION', 'LAB_REPORT', 'OTHER')),
    content_type varchar(100) NOT NULL CHECK (content_type = lower(content_type)),
    max_bytes    bigint       NOT NULL CHECK (max_bytes > 0 AND max_bytes <= 104857600),
    CONSTRAINT attachment_type_rule_pk PRIMARY KEY (kind, content_type)
);

INSERT INTO ref.attachment_type_rule (kind, content_type, max_bytes)
SELECT k.kind, t.content_type, k.max_bytes
  FROM (VALUES ('PHOTO', 26214400), ('PACK_PHOTO', 26214400)) AS k (kind, max_bytes)
 CROSS JOIN (VALUES ('image/jpeg'), ('image/png')) AS t (content_type)
UNION ALL
SELECT 'VIDEO', 'video/mp4', 104857600
UNION ALL
SELECT k.kind, t.content_type, 26214400
  FROM (VALUES ('INVOICE'), ('PRESCRIPTION'), ('LAB_REPORT'), ('OTHER')) AS k (kind)
 CROSS JOIN (VALUES ('application/pdf'), ('image/jpeg'), ('image/png')) AS t (content_type);

GRANT SELECT ON ref.attachment_type_rule TO ${app_role};
