-- =============================================================================
-- 08_seed_loinc.sql
--
-- Seeds 20 common lab LOINC codes (CBC, basic metabolic panel, lipid panel,
-- liver enzymes, thyroid) covering the observation types most likely to
-- appear in HL7 ORU messages and FHIR Observation resources during demo
-- testing. Curated sample, not a full LOINC distribution — production
-- deployments should load the full Regenstrief release into this table.
-- =============================================================================

INSERT INTO ontology.loinc_codes
    (loinc_num, component, property, time_aspect, system, scale_type, method_type, long_common_name)
VALUES
    ('2345-7',   'Glucose',                       'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Glucose [Mass/volume] in Serum or Plasma'),
    ('4548-4',   'Hemoglobin A1c/Hemoglobin.total','MFr',  'Pt', 'Bld',      'Qn', NULL,                'Hemoglobin A1c/Hemoglobin.total in Blood'),
    ('2160-0',   'Creatinine',                     'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Creatinine [Mass/volume] in Serum or Plasma'),
    ('718-7',    'Hemoglobin',                     'MCnc', 'Pt', 'Bld',      'Qn', NULL,                'Hemoglobin [Mass/volume] in Blood'),
    ('789-8',    'Erythrocytes',                   'NCnc', 'Pt', 'Bld',      'Qn', 'Automated count',   'Erythrocytes [#/volume] in Blood by Automated count'),
    ('4544-3',   'Hematocrit',                      'VFr', 'Pt', 'Bld',      'Qn', 'Automated count',   'Hematocrit [Volume Fraction] of Blood by Automated count'),
    ('6690-2',   'Leukocytes',                     'NCnc', 'Pt', 'Bld',      'Qn', 'Automated count',   'Leukocytes [#/volume] in Blood by Automated count'),
    ('777-3',    'Platelets',                      'NCnc', 'Pt', 'Bld',      'Qn', NULL,                'Platelets [#/volume] in Blood'),
    ('2093-3',   'Cholesterol',                    'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Cholesterol [Mass/volume] in Serum or Plasma'),
    ('2571-8',   'Triglyceride',                   'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Triglyceride [Mass/volume] in Serum or Plasma'),
    ('2085-9',   'Cholesterol in HDL',             'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Cholesterol in HDL [Mass/volume] in Serum or Plasma'),
    ('13457-7',  'Cholesterol in LDL',             'MCnc', 'Pt', 'Ser/Plas', 'Qn', 'Calculated',        'Cholesterol in LDL [Mass/volume] in Serum or Plasma by calculation'),
    ('1742-6',   'Alanine aminotransferase',       'CCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Alanine aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('1920-8',   'Aspartate aminotransferase',     'CCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Aspartate aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('3094-0',   'Urea nitrogen',                  'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Urea nitrogen [Mass/volume] in Serum or Plasma'),
    ('2951-2',   'Sodium',                         'SCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Sodium [Moles/volume] in Serum or Plasma'),
    ('2823-3',   'Potassium',                      'SCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Potassium [Moles/volume] in Serum or Plasma'),
    ('2075-0',   'Chloride',                       'SCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Chloride [Moles/volume] in Serum or Plasma'),
    ('17861-6',  'Calcium',                        'MCnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Calcium [Mass/volume] in Serum or Plasma'),
    ('3016-3',   'Thyrotropin',                    'ACnc', 'Pt', 'Ser/Plas', 'Qn', NULL,                'Thyrotropin [Units/volume] in Serum or Plasma')
ON CONFLICT (loinc_num) DO NOTHING;
