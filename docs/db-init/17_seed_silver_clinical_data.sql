-- =============================================================================
-- 17_seed_silver_clinical_data.sql
--
-- Phase 5b (slice 1) demo data for the Silver → FHIR builders. No service
-- parses Bronze into silver.patients/conditions/observations/medications yet,
-- so these tables are otherwise empty and there'd be nothing to build from.
--
-- Codes come from the ontology seeds (07–10) so ontology-service agrees
-- they're valid. Units are UCUM, which the HL7 validator checks.
--
-- Patient SEED-0003 is DELIBERATELY non-compliant: blank first/last name
-- (the columns are NOT NULL, so '' rather than NULL — exactly what a sloppy
-- upstream feed produces) and a condition with a blank code. Building it
-- must land rows in Gold flagged is_us_core_compliant = false with the
-- reasons in compliance_notes.
--
-- Idempotent: re-running inserts nothing new (patients key on
-- (source_system, source_patient_id); children on (patient, code)).
-- =============================================================================

INSERT INTO silver.patients
    (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender,
     address_line1, city, state, postal_code, phone)
VALUES
    ('SEED_EPIC_ADT',   'SEED-0001', 'MRN100001', 'Maria', 'Garcia', '1968-04-12', 'female',
     '1200 Congress Ave', 'Austin', 'TX', '78701', '512-555-0101'),
    ('SEED_CERNER_FHIR','SEED-0002', 'MRN200002', 'James', 'Wilson', '1955-09-30', 'male',
     '45 Beacon St', 'Boston', 'MA', '02108', '617-555-0202'),
    ('SEED_CLAIMS_CSV', 'SEED-0003', NULL,        '',      '',       '1990-01-01', 'unknown',
     NULL, NULL, NULL, NULL, NULL)
ON CONFLICT (source_system, source_patient_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Conditions
-- ---------------------------------------------------------------------------
INSERT INTO silver.conditions (patient_id, code_system, code, display, clinical_status, onset_date, recorded_date)
SELECT p.id, v.code_system, v.code, v.display, v.clinical_status, v.onset_date::date, v.recorded_date::date
FROM (VALUES
    ('SEED-0001', 'ICD-10',    'E11.9',    'Type 2 diabetes mellitus without complications', 'active',   '2015-06-01', '2015-06-03'),
    ('SEED-0001', 'ICD-10',    'E78.5',    'Hyperlipidemia, unspecified',                     'active',   '2018-02-14', '2018-02-14'),
    ('SEED-0002', 'ICD-10',    'I10',      'Essential (primary) hypertension',                'active',   '2010-11-20', '2010-11-22'),
    ('SEED-0002', 'SNOMED-CT', '13645005', 'Chronic obstructive lung disease',                'active',   '2019-03-05', '2019-03-05'),
    ('SEED-0003', 'ICD-10',    '',         NULL,                                              'active',   NULL,         '2024-05-01')
) AS v(source_patient_id, code_system, code, display, clinical_status, onset_date, recorded_date)
JOIN silver.patients p ON p.source_patient_id = v.source_patient_id AND p.source_system LIKE 'SEED\_%'
WHERE NOT EXISTS (
    SELECT 1 FROM silver.conditions c WHERE c.patient_id = p.id AND c.code = v.code
);

-- ---------------------------------------------------------------------------
-- Observations (lab results, LOINC)
-- ---------------------------------------------------------------------------
INSERT INTO silver.observations (patient_id, code, display, value_numeric, value_text, unit, status, effective_date)
SELECT p.id, v.code, v.display, v.value_numeric, v.value_text, v.unit, v.status, v.effective_date::timestamptz
FROM (VALUES
    ('SEED-0001', '4548-4',  'Hemoglobin A1c/Hemoglobin.total in Blood', 7.2::numeric,   NULL, '%',      'final', '2024-03-10T08:30:00Z'),
    ('SEED-0001', '2345-7',  'Glucose [Mass/volume] in Serum or Plasma', 142::numeric,   NULL, 'mg/dL',  'final', '2024-03-10T08:30:00Z'),
    ('SEED-0001', '13457-7', 'Cholesterol in LDL [Mass/volume] in Serum or Plasma by calculation', 118::numeric, NULL, 'mg/dL', 'final', '2024-03-10T08:30:00Z'),
    ('SEED-0002', '2160-0',  'Creatinine [Mass/volume] in Serum or Plasma', 1.1::numeric, NULL, 'mg/dL',  'final', '2024-04-02T14:15:00Z'),
    ('SEED-0002', '2823-3',  'Potassium [Moles/volume] in Serum or Plasma', 4.3::numeric, NULL, 'mmol/L', 'final', '2024-04-02T14:15:00Z'),
    ('SEED-0003', '718-7',   'Hemoglobin [Mass/volume] in Blood',       13.5::numeric,   NULL, 'g/dL',   'final', '2024-05-01T09:00:00Z')
) AS v(source_patient_id, code, display, value_numeric, value_text, unit, status, effective_date)
JOIN silver.patients p ON p.source_patient_id = v.source_patient_id AND p.source_system LIKE 'SEED\_%'
WHERE NOT EXISTS (
    SELECT 1 FROM silver.observations o WHERE o.patient_id = p.id AND o.code = v.code
);

-- ---------------------------------------------------------------------------
-- Medications (RxNorm)
-- ---------------------------------------------------------------------------
INSERT INTO silver.medications (patient_id, rxnorm_code, display, dosage_text, status, start_date, end_date)
SELECT p.id, v.rxnorm_code, v.display, v.dosage_text, v.status, v.start_date::date, v.end_date::date
FROM (VALUES
    ('SEED-0001', '6809',  'Metformin',    '500 mg by mouth twice daily with meals', 'active',    '2015-06-03', NULL),
    ('SEED-0001', '83367', 'Atorvastatin', '20 mg by mouth once daily at bedtime',   'active',    '2018-02-14', NULL),
    ('SEED-0002', '29046', 'Lisinopril',   '10 mg by mouth once daily',              'active',    '2010-11-22', NULL),
    ('SEED-0002', '17767', 'Amlodipine',   '5 mg by mouth once daily',               'stopped',   '2012-01-10', '2016-07-01')
) AS v(source_patient_id, rxnorm_code, display, dosage_text, status, start_date, end_date)
JOIN silver.patients p ON p.source_patient_id = v.source_patient_id AND p.source_system LIKE 'SEED\_%'
WHERE NOT EXISTS (
    SELECT 1 FROM silver.medications m WHERE m.patient_id = p.id AND m.rxnorm_code = v.rxnorm_code
);
