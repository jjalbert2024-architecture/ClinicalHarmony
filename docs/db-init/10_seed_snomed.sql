-- =============================================================================
-- 10_seed_snomed.sql
--
-- SNOMED CT was not part of the original Phase 1 ontology schema (only
-- icd10_codes, loinc_codes, rxnorm_codes, clinical_rules were created in
-- 05_ontology_tables.sql). This script creates ontology.snomed_codes and
-- seeds 15 common clinical concepts spanning disorders, findings, and
-- procedures. Curated sample, not a full SNOMED CT distribution.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ontology.snomed_codes (
    concept_id      VARCHAR(20)     PRIMARY KEY,
    term            VARCHAR(500)    NOT NULL,
    semantic_tag    VARCHAR(50)     NOT NULL
        CONSTRAINT chk_snomed_codes_semantic_tag
        CHECK (semantic_tag IN ('disorder', 'finding', 'procedure')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.snomed_codes IS 'SNOMED CT reference set used to validate silver.conditions.code when code_system = SNOMED-CT.';
COMMENT ON COLUMN ontology.snomed_codes.semantic_tag IS 'SNOMED CT semantic tag — which axis this concept belongs to (disorder/finding/procedure).';

CREATE INDEX IF NOT EXISTS idx_snomed_codes_semantic_tag ON ontology.snomed_codes (semantic_tag);

INSERT INTO ontology.snomed_codes (concept_id, term, semantic_tag)
VALUES
    ('44054006',  'Diabetes mellitus type 2',              'disorder'),
    ('38341003',  'Hypertensive disorder',                 'disorder'),
    ('233604007', 'Pneumonia',                              'disorder'),
    ('195967001', 'Asthma',                                 'disorder'),
    ('84114007',  'Heart failure',                          'disorder'),
    ('13645005',  'Chronic obstructive lung disease',       'disorder'),
    ('35489007',  'Depressive disorder',                    'disorder'),
    ('48694002',  'Anxiety',                                'disorder'),
    ('386661006', 'Fever',                                  'finding'),
    ('29857009',  'Chest pain',                             'finding'),
    ('267036007', 'Dyspnea',                                'finding'),
    ('49727002',  'Cough',                                  'finding'),
    ('396339007', 'Blood test',                             'procedure'),
    ('168537006', 'Plain chest X-ray',                      'procedure'),
    ('29303009',  'Electrocardiographic procedure',         'procedure')
ON CONFLICT (concept_id) DO NOTHING;
