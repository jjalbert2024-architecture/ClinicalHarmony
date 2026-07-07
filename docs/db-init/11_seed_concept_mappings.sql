-- =============================================================================
-- 11_seed_concept_mappings.sql
--
-- Cross-terminology mapping table — not part of the original Phase 1 schema.
-- Lets the platform answer "given this SNOMED CT finding, what's the
-- equivalent ICD-10 billing code?", a real interoperability need since
-- clinical documentation commonly uses SNOMED CT while billing requires
-- ICD-10. Seeded for the same common conditions covered by
-- 10_seed_snomed.sql, so a validated SNOMED condition can always be mapped
-- forward. Curated sample, not an authoritative NLM cross-map release.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ontology.concept_mappings (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system   VARCHAR(20)     NOT NULL,
    source_code     VARCHAR(50)     NOT NULL,
    target_system   VARCHAR(20)     NOT NULL,
    target_code     VARCHAR(50)     NOT NULL,
    mapping_type    VARCHAR(20)     NOT NULL
        CONSTRAINT chk_concept_mappings_type
        CHECK (mapping_type IN ('EQUIVALENT', 'BROADER', 'NARROWER')),
    notes           TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.concept_mappings IS 'Cross-terminology mappings (e.g. SNOMED CT -> ICD-10) used by GET /api/ontology/map.';
COMMENT ON COLUMN ontology.concept_mappings.mapping_type IS 'EQUIVALENT = same clinical meaning; BROADER/NARROWER = target is a less/more specific concept than source.';

CREATE INDEX IF NOT EXISTS idx_concept_mappings_source ON ontology.concept_mappings (source_system, source_code);

INSERT INTO ontology.concept_mappings (source_system, source_code, target_system, target_code, mapping_type, notes)
VALUES
    ('SNOMED', '44054006',  'ICD-10', 'E11.9',   'EQUIVALENT', 'Diabetes mellitus type 2 -> Type 2 diabetes mellitus without complications'),
    ('SNOMED', '73211009',  'ICD-10', 'E11.9',   'BROADER',    'Diabetes mellitus (general concept) -> Type 2 diabetes mellitus without complications'),
    ('SNOMED', '38341003',  'ICD-10', 'I10',     'EQUIVALENT', 'Hypertensive disorder -> Essential (primary) hypertension'),
    ('SNOMED', '233604007', 'ICD-10', 'J18.9',   'EQUIVALENT', 'Pneumonia -> Pneumonia, unspecified organism'),
    ('SNOMED', '195967001', 'ICD-10', 'J45.909', 'EQUIVALENT', 'Asthma -> Unspecified asthma, uncomplicated'),
    ('SNOMED', '84114007',  'ICD-10', 'I50.9',   'EQUIVALENT', 'Heart failure -> Heart failure, unspecified'),
    ('SNOMED', '13645005',  'ICD-10', 'J44.9',   'EQUIVALENT', 'Chronic obstructive lung disease -> COPD, unspecified'),
    ('SNOMED', '35489007',  'ICD-10', 'F32.9',   'EQUIVALENT', 'Depressive disorder -> Major depressive disorder, single episode, unspecified'),
    ('SNOMED', '48694002',  'ICD-10', 'F41.1',   'EQUIVALENT', 'Anxiety -> Generalized anxiety disorder'),
    ('SNOMED', '386661006', 'ICD-10', 'R50.9',   'EQUIVALENT', 'Fever -> Fever, unspecified'),
    ('SNOMED', '29857009',  'ICD-10', 'R07.9',   'EQUIVALENT', 'Chest pain -> Chest pain, unspecified'),
    ('SNOMED', '267036007', 'ICD-10', 'R06.02',  'EQUIVALENT', 'Dyspnea -> Shortness of breath'),
    ('SNOMED', '49727002',  'ICD-10', 'R05.9',   'EQUIVALENT', 'Cough -> Cough, unspecified'),
    ('SNOMED', '25064002',  'ICD-10', 'R51.9',   'EQUIVALENT', 'Headache -> Headache, unspecified'),
    ('SNOMED', '195951007', 'ICD-10', 'J20.9',   'EQUIVALENT', 'Acute bronchitis -> Acute bronchitis, unspecified'),
    ('SNOMED', '68566005',  'ICD-10', 'N39.0',   'EQUIVALENT', 'Urinary tract infection -> Urinary tract infection, site not specified'),
    ('SNOMED', '87433001',  'ICD-10', 'J43.9',   'EQUIVALENT', 'Pulmonary emphysema -> Emphysema, unspecified'),
    ('SNOMED', '271737000', 'ICD-10', 'D64.9',   'EQUIVALENT', 'Anemia -> Anemia, unspecified'),
    ('SNOMED', '195080001', 'ICD-10', 'I48.91',  'EQUIVALENT', 'Atrial fibrillation -> Unspecified atrial fibrillation'),
    ('SNOMED', '444814009', 'ICD-10', 'J01.90',  'EQUIVALENT', 'Viral sinusitis -> Acute sinusitis, unspecified')
;
