-- =============================================================================
-- 09_seed_rxnorm.sql
--
-- Seeds 15 common medications by RxNorm ingredient-level RxCUI, covering the
-- drug classes most likely to appear in a demo patient population (diabetes,
-- hypertension, lipid, and infection management). Curated sample, not a full
-- RxNorm distribution — production deployments should load the full NLM
-- RxNorm release into this table.
-- =============================================================================

INSERT INTO ontology.rxnorm_codes
    (rxcui, name, term_type, is_active)
VALUES
    ('6809',   'Metformin',      'IN', TRUE),
    ('29046',  'Lisinopril',     'IN', TRUE),
    ('83367',  'Atorvastatin',   'IN', TRUE),
    ('723',    'Amoxicillin',    'IN', TRUE),
    ('7646',   'Omeprazole',     'IN', TRUE),
    ('5640',   'Ibuprofen',      'IN', TRUE),
    ('161',    'Acetaminophen',  'IN', TRUE),
    ('32968',  'Levothyroxine',  'IN', TRUE),
    ('36567',  'Simvastatin',    'IN', TRUE),
    ('52175',  'Losartan',       'IN', TRUE),
    ('6918',   'Metoprolol',     'IN', TRUE),
    ('4603',   'Furosemide',     'IN', TRUE),
    ('1191',   'Aspirin',        'IN', TRUE),
    ('36437',  'Sertraline',     'IN', TRUE),
    ('17767',  'Amlodipine',     'IN', TRUE)
ON CONFLICT (rxcui) DO NOTHING;
