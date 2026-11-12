-- =============================================================================
-- 21_seed_pmi_duplicates.sql
--
-- Phase 6 demo data for patient-index-service: the same people arriving from
-- different source systems, with the kinds of differences real feeds have.
-- Builds on 17's SEED-0001 (Maria Garcia) and SEED-0002 (James Wilson).
--
-- Expected outcome of a matching run (default PMI_MATCH weights):
--   PMI-0001  Maria Garcia, claims feed, same DOB + phone, "Avenue" spelled out
--             → deterministic (exact name + DOB + phone) → AUTO-MERGED with SEED-0001
--   PMI-0002  "Jim" Wilson, lab feed, no phone, "Street" spelled out
--             → probabilistic ~0.91 (nickname, normalized address) → AUTO-MERGED with SEED-0002
--   PMI-0006  James "Willson", same Cerner feed and MRN as SEED-0002
--             → deterministic (same source + MRN) → AUTO-MERGED with SEED-0002
--   PMI-0003  Maria Garcia, DOB day/month swapped, no phone, different street
--             → probabilistic ~0.65 → REVIEW queue
--   PMI-0004  Mario Garcia, same DOB/address/phone as Maria but male (a twin)
--             → probabilistic ~0.71, gender conflict keeps it out of auto-merge → REVIEW
--   PMI-0005  Robert Wilson, same DOB as James, different everything else
--             → ~0.21 → no match; confirmed as its own master
--
-- Masters exist only for Silver patients fhir-compliance-service has built
-- (POST /api/fhir/build creates PROVISIONAL 1:1 masters), so build first, then
-- run matching (POST /api/pmi/match/run).
--
-- All demographics are synthetic. Idempotent: keyed on (source_system, source_patient_id).
-- =============================================================================

INSERT INTO silver.patients
    (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender,
     address_line1, city, state, postal_code, phone)
VALUES
    ('SEED_CLAIMS_CSV',  'PMI-0001', NULL,        'Maria',  'Garcia',  '1968-04-12', 'female',
     '1200 Congress Avenue', 'Austin', 'TX', '78701', '(512) 555-0101'),
    ('SEED_LAB_LIS',     'PMI-0002', 'LAB-88231', 'Jim',    'Wilson',  '1955-09-30', 'male',
     '45 Beacon Street', 'Boston', 'MA', '02108', NULL),
    ('SEED_EPIC_ADT',    'PMI-0003', 'MRN100318', 'Maria',  'Garcia',  '1968-12-04', 'female',
     '77 Rainey St', 'Austin', 'TX', '78701', NULL),
    ('SEED_EPIC_ADT',    'PMI-0004', 'MRN100077', 'Mario',  'Garcia',  '1968-04-12', 'male',
     '1200 Congress Ave', 'Austin', 'TX', '78701', '512-555-0101'),
    ('SEED_CLAIMS_CSV',  'PMI-0005', NULL,        'Robert', 'Wilson',  '1955-09-30', 'male',
     '9 Camelback Rd', 'Phoenix', 'AZ', '85001', '602-555-0303'),
    ('SEED_CERNER_FHIR', 'PMI-0006', 'MRN200002', 'James',  'Willson', '1955-09-30', 'male',
     '45 Beacon St', 'Boston', 'MA', '02108', '617-555-0202')
ON CONFLICT (source_system, source_patient_id) DO NOTHING;
