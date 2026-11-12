-- =============================================================================
-- 19_seed_cms0057_gold.sql
--
-- Phase 5b (fhir-compliance-service, slice 3) demo data for the CMS-0057-F
-- payer read endpoints (GET /fhir/r4/{Coverage,PractitionerRole,
-- MedicationKnowledge,MeasureReport,Device}). Nothing upstream produces these
-- resource types — there's no claims-eligibility, provider-directory, formulary
-- or quality-measure feed — so they're seeded straight into Gold as synthetic
-- FHIR JSON, the same way 17 seeds Silver.
--
-- What's here (all synthetic; names/ids are invented, codes are real):
--   Organization x2 (payer, provider) and Practitioner x2 — the targets of the
--     references below, so a client can resolve them from Gold.
--   PractitionerRole x2 — cms-practitionerrole-2 is DELIBERATELY non-compliant:
--     no telecom and no endpoint, violating US Core's pd-1 ("SHALL have contact
--     information or a reference to an Endpoint").
--   Coverage x2 — SEED-0001 commercial, SEED-0002 Medicare (SOPT codes).
--   MedicationKnowledge x3 — Da Vinci Formulary drugs for the seed patients'
--     meds: RxNorm SCD + its SCDG (looked up on NLM RxNav).
--   MeasureReport x1 — DEQM individual report for SEED-0001's HbA1c-poor-control
--     eCQM (7.2% in 17's Silver seed → not in the numerator).
--   Device x1 — US Core implantable device (pacemaker) for SEED-0002, using the
--     UDI from the FHIR spec's own example.
--
-- Rows land UNVALIDATED: is_us_core_compliant = false and compliance_notes =
-- {"status":"PENDING_VALIDATION",...}. fhir-compliance-service's GoldWriter
-- validates every pending row (on startup, and via POST /api/fhir/gold/revalidate),
-- replacing compliance_notes with real validator findings and writing a
-- SEED-stage data_lineage row. SQL can't run the HL7 validator, and stamping a
-- compliance verdict here would be asserting something nothing has checked.
--
-- Patient references resolve to the Silver seed patients' FHIR ids
-- ('silver-patient-' || silver.patients.id), so they match the Patients that
-- POST /api/fhir/build writes. If those Silver rows are missing, the
-- patient-linked rows are skipped rather than pointing at nothing.
--
-- Same JSON as fhir-compliance-service/src/test/resources/cms0057/*.json
-- (Cms0057ProfileValidationTest validates those against the real validator).
--
-- Idempotent: ON CONFLICT DO NOTHING — re-running never clobbers a row that
-- has since been validated.
-- =============================================================================

-- RxNorm concepts used by the formulary seed, so ontology-service agrees they
-- exist (09 only seeded ingredients, TTY = IN).
INSERT INTO ontology.rxnorm_codes (rxcui, name, term_type, is_active)
VALUES
    ('861007',  'metformin hydrochloride 500 MG Oral Tablet', 'SCD',  TRUE),
    ('617310',  'atorvastatin 20 MG Oral Tablet',             'SCD',  TRUE),
    ('314076',  'lisinopril 10 MG Oral Tablet',               'SCD',  TRUE),
    ('1161610', 'metformin Oral Product',                     'SCDG', TRUE),
    ('1158284', 'atorvastatin Oral Product',                  'SCDG', TRUE),
    ('1164689', 'lisinopril Oral Product',                    'SCDG', TRUE)
ON CONFLICT (rxcui) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Resources with no patient reference
-- ---------------------------------------------------------------------------
INSERT INTO gold.fhir_resources (resource_type, fhir_id, resource_json, is_us_core_compliant, compliance_notes)
VALUES
    ('Organization', 'cms-org-payer',
     '{"resourceType":"Organization","id":"cms-org-payer","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-organization"]},"identifier":[{"system":"http://clinicalharmony.example.org/fhir/sid/payer-id","value":"CHP-PAYER-001"}],"active":true,"name":"ClinicalHarmony Health Plan (synthetic)","type":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/organization-type","code":"pay","display":"Payer"}]}],"telecom":[{"system":"phone","value":"800-555-0100","use":"work"}],"address":[{"line":["100 Synthetic Plaza"],"city":"Austin","state":"TX","postalCode":"78701","country":"US"}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('Organization', 'cms-org-provider',
     '{"resourceType":"Organization","id":"cms-org-provider","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-organization"]},"identifier":[{"system":"http://clinicalharmony.example.org/fhir/sid/provider-org-id","value":"CHP-CLINIC-001"}],"active":true,"name":"Harmony Family Medicine (synthetic)","type":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/organization-type","code":"prov","display":"Healthcare Provider"}]}],"telecom":[{"system":"phone","value":"512-555-0150","use":"work"}],"address":[{"line":["250 Clinic Way"],"city":"Austin","state":"TX","postalCode":"78702","country":"US"}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('Practitioner', 'cms-practitioner-1',
     '{"resourceType":"Practitioner","id":"cms-practitioner-1","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitioner"]},"identifier":[{"system":"http://clinicalharmony.example.org/fhir/sid/provider-id","value":"CHP-PRAC-001"}],"active":true,"name":[{"family":"Okafor","given":["Adaeze"],"prefix":["Dr."]}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('Practitioner', 'cms-practitioner-2',
     '{"resourceType":"Practitioner","id":"cms-practitioner-2","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitioner"]},"identifier":[{"system":"http://clinicalharmony.example.org/fhir/sid/provider-id","value":"CHP-PRAC-002"}],"active":true,"name":[{"family":"Lindqvist","given":["Erik"],"prefix":["Dr."]}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('PractitionerRole', 'cms-practitionerrole-1',
     '{"resourceType":"PractitionerRole","id":"cms-practitionerrole-1","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitionerrole"]},"active":true,"practitioner":{"reference":"Practitioner/cms-practitioner-1","display":"Dr. Adaeze Okafor"},"organization":{"reference":"Organization/cms-org-provider","display":"Harmony Family Medicine (synthetic)"},"code":[{"coding":[{"system":"http://nucc.org/provider-taxonomy","code":"207Q00000X","display":"Family Medicine Physician"}]}],"specialty":[{"coding":[{"system":"http://nucc.org/provider-taxonomy","code":"207Q00000X","display":"Family Medicine Physician"}]}],"telecom":[{"system":"phone","value":"512-555-0151","use":"work"}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('PractitionerRole', 'cms-practitionerrole-2',
     '{"resourceType":"PractitionerRole","id":"cms-practitionerrole-2","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitionerrole"]},"active":true,"practitioner":{"reference":"Practitioner/cms-practitioner-2","display":"Dr. Erik Lindqvist"},"organization":{"reference":"Organization/cms-org-provider","display":"Harmony Family Medicine (synthetic)"},"code":[{"coding":[{"system":"http://nucc.org/provider-taxonomy","code":"207RC0000X","display":"Cardiovascular Disease Physician"}]}]}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('MedicationKnowledge', 'cms-formulary-861007',
     '{"resourceType":"MedicationKnowledge","id":"cms-formulary-861007","meta":{"lastUpdated":"2026-11-11T00:00:00Z","profile":["http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug"]},"code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"861007","display":"metformin hydrochloride 500 MG Oral Tablet"},{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"1161610","display":"metformin Oral Product"}]},"status":"active","doseForm":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"317541","display":"Oral Tablet"}]}}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('MedicationKnowledge', 'cms-formulary-617310',
     '{"resourceType":"MedicationKnowledge","id":"cms-formulary-617310","meta":{"lastUpdated":"2026-11-11T00:00:00Z","profile":["http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug"]},"code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"617310","display":"atorvastatin 20 MG Oral Tablet"},{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"1158284","display":"atorvastatin Oral Product"}]},"status":"active","doseForm":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"317541","display":"Oral Tablet"}]}}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb),
    ('MedicationKnowledge', 'cms-formulary-314076',
     '{"resourceType":"MedicationKnowledge","id":"cms-formulary-314076","meta":{"lastUpdated":"2026-11-11T00:00:00Z","profile":["http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug"]},"code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"314076","display":"lisinopril 10 MG Oral Tablet"},{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"1164689","display":"lisinopril Oral Product"}]},"status":"active","doseForm":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"317541","display":"Oral Tablet"}]}}'::jsonb,
     FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb)
ON CONFLICT (resource_type, fhir_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Resources that reference a Silver seed patient
-- ---------------------------------------------------------------------------
INSERT INTO gold.fhir_resources (resource_type, fhir_id, resource_json, is_us_core_compliant, compliance_notes)
SELECT v.resource_type, v.fhir_id,
       replace(v.resource_json, '{{PATIENT}}', 'silver-patient-' || p.id)::jsonb,
       FALSE, '{"status":"PENDING_VALIDATION","seed":"19_seed_cms0057_gold.sql"}'::jsonb
FROM (VALUES
    ('SEED-0001', 'Coverage', 'cms-coverage-seed-0001',
     '{"resourceType":"Coverage","id":"cms-coverage-seed-0001","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-coverage"]},"identifier":[{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0203","code":"MB","display":"Member Number"}]},"system":"http://clinicalharmony.example.org/fhir/sid/member-id","value":"CHP-100001-01"}],"status":"active","type":{"coding":[{"system":"https://nahdo.org/sopt","code":"5","display":"PRIVATE HEALTH INSURANCE"}]},"subscriberId":"CHP-100001","beneficiary":{"reference":"Patient/{{PATIENT}}"},"relationship":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/subscriber-relationship","code":"self","display":"Self"}]},"period":{"start":"2024-01-01"},"payor":[{"reference":"Organization/cms-org-payer","display":"ClinicalHarmony Health Plan (synthetic)"}],"class":[{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/coverage-class","code":"group"}]},"value":"GRP-7001"},{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/coverage-class","code":"plan"}]},"value":"PLN-PPO-GOLD","name":"Gold PPO"}]}'),
    ('SEED-0002', 'Coverage', 'cms-coverage-seed-0002',
     '{"resourceType":"Coverage","id":"cms-coverage-seed-0002","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-coverage"]},"identifier":[{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0203","code":"MB","display":"Member Number"}]},"system":"http://clinicalharmony.example.org/fhir/sid/member-id","value":"CHP-200002-01"}],"status":"active","type":{"coding":[{"system":"https://nahdo.org/sopt","code":"1","display":"MEDICARE"}]},"subscriberId":"CHP-200002","beneficiary":{"reference":"Patient/{{PATIENT}}"},"relationship":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/subscriber-relationship","code":"self","display":"Self"}]},"period":{"start":"2020-10-01"},"payor":[{"reference":"Organization/cms-org-payer","display":"ClinicalHarmony Health Plan (synthetic)"}],"class":[{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/coverage-class","code":"group"}]},"value":"GRP-7001"},{"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/coverage-class","code":"plan"}]},"value":"PLN-MA-SILVER","name":"Medicare Advantage Silver"}]}'),
    ('SEED-0001', 'MeasureReport', 'cms-measurereport-seed-0001',
     '{"resourceType":"MeasureReport","id":"cms-measurereport-seed-0001","meta":{"profile":["http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/indv-measurereport-deqm"]},"extension":[{"url":"http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/extension-measureScoring","valueCodeableConcept":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/measure-scoring","code":"proportion","display":"Proportion"}]}}],"status":"complete","type":"individual","measure":"http://ecqi.healthit.gov/ecqms/Measure/DiabetesHemoglobinA1cHbA1cPoorControl9FHIR|0.1.000","subject":{"reference":"Patient/{{PATIENT}}"},"date":"2025-01-15T00:00:00Z","reporter":{"reference":"Organization/cms-org-provider"},"period":{"start":"2024-01-01","end":"2024-12-31"},"improvementNotation":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/measure-improvement-notation","code":"decrease"}]},"group":[{"population":[{"code":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/measure-population","code":"initial-population"}]},"count":1},{"code":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/measure-population","code":"denominator"}]},"count":1},{"code":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/measure-population","code":"numerator"}]},"count":0}],"measureScore":{"value":0}}]}'),
    ('SEED-0002', 'Device', 'cms-device-seed-0002',
     '{"resourceType":"Device","id":"cms-device-seed-0002","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-implantable-device"]},"udiCarrier":[{"deviceIdentifier":"09504000059118","carrierHRF":"(01)09504000059118(17)141120(10)7654321D(21)10987654d321"}],"status":"active","manufactureDate":"2013-02-01","expirationDate":"2014-11-20","lotNumber":"7654321D","serialNumber":"10987654d321","type":{"coding":[{"system":"http://snomed.info/sct","code":"14106009","display":"Cardiac pacemaker, device (physical object)"}]},"patient":{"reference":"Patient/{{PATIENT}}"}}')
) AS v(source_patient_id, resource_type, fhir_id, resource_json)
JOIN silver.patients p ON p.source_patient_id = v.source_patient_id AND p.source_system LIKE 'SEED\_%'
ON CONFLICT (resource_type, fhir_id) DO NOTHING;
